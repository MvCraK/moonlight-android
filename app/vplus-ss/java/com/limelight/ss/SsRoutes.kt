package com.limelight.ss

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

object SsRoutes {
    enum class Status { OFF, CHECKING, ONLINE, DEGRADED }

    /**
     * TCP handshake to a node's SS port; [latencyMs] is null when it did not answer.
     * [skipped] nodes (the UDP-only home subscription) cannot be probed over TCP.
     */
    data class Probe(val host: String, val port: Int, val latencyMs: Long?, val skipped: Boolean = false)

    /** Node order is home TCP, home UDP, company; the home UDP node only serves UDP. */
    private const val UDP_ONLY_NODE = 1

    /** Serializes subscription/config work triggered from the UI. */
    val worker = Executors.newSingleThreadExecutor()
    private val prober = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile var enabled = false
        private set
    /** Results in node order (home TCP, home UDP, company); null until the first probe finishes. */
    @Volatile var probes: List<Probe>? = null
        private set
    @Volatile var probing = false
        private set
    @Volatile private var probedAt = 0L
    private lateinit var storage: AtomicFile
    private var state = JSONObject()
    private val ciphers = setOf("aes-256-gcm", "aes-128-gcm", "chacha20-ietf-poly1305")

    @Synchronized fun init(context: Context) {
        storage = AtomicFile(File(context.noBackupFilesDir, "stream-routes.json"))
        if (!storage.baseFile.exists() && !File(storage.baseFile.path + ".bak").exists()) return
        // Only the config and nodes persist; every new process starts disconnected.
        runCatching {
            state = JSONObject(storage.openRead().use { readText(it) })
            state.remove("enabled")
        }
    }

    internal fun readText(input: InputStream): String {
        val result = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            require(result.size() + n <= 256 * 1024) { "配置文件超过 256 KiB" }
            result.write(buf, 0, n)
        }
        return result.toString("UTF-8")
    }

    private fun node(o: JSONObject): JSONObject {
        val server = o.getString("server")
        val port = o.getInt("port")
        val cipher = o.getString("cipher")
        val password = o.getString("password")
        require(server.isNotBlank() && server.length <= 253 && server.none { it.isWhitespace() || it == '/' } &&
            port in 1..65535 && cipher in ciphers && password.isNotEmpty()) { "SS 节点参数无效或加密方式不受支持" }
        return JSONObject().put("server", server).put("port", port).put("cipher", cipher).put("password", password).put("tfo", o.optBoolean("tfo"))
    }

    private fun loadSubscription(spec: String, udp: Boolean): JSONObject {
        val url = URL(spec)
        require(url.protocol == "https" && url.host.isNotBlank()) { "订阅需要 HTTPS 地址" }
        val conn = url.openConnection() as HttpsURLConnection
        val yaml = try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = false
            check(conn.responseCode in 200..299) { "订阅服务器返回 HTTP ${conn.responseCode}" }
            conn.inputStream.use { readText(it) }
        } finally {
            conn.disconnect()
        }
        val options = LoaderOptions().apply {
            maxAliasesForCollections = 8
            codePointLimit = 256 * 1024
            isAllowDuplicateKeys = false
        }
        val root = Yaml(SafeConstructor(options)).load<Any>(yaml) as? Map<*, *>
        val entries = root?.get("proxies") as? List<*> ?: error("订阅中缺少 proxies")
        val entry = entries.filterIsInstance<Map<*, *>>().singleOrNull {
            it["type"] == "ss" && (!udp || it["udp"] != false)
        } ?: error("每份订阅需要一个可用 SS 节点")
        require(entry["plugin"] == null && entry["udp-over-tcp"] != true) { "节点要求额外的传输插件" }
        return node(JSONObject().apply {
            for (key in listOf("server", "port", "cipher", "password", "tfo")) put(key, entry[key])
        })
    }

    private fun activate(s: JSONObject) {
        check(SsNative.installHooks() > 0) { "无法接管 Moonlight 网络调用" }
        check(SsNative.setSsRoutes(s.getJSONArray("nodes").toString().toByteArray())) { "无法启动内置 SS" }
        enabled = true
        probes = null
        probedAt = 0
        notifyChanged()
    }

    private fun deactivate() {
        SsNative.setSsRoutes(null)
        enabled = false
        probes = null
        probedAt = 0
        notifyChanged()
    }

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    private fun notifyChanged() = main.post { listeners.forEach { it() } }

    fun status(): Status {
        if (!enabled) return Status.OFF
        val result = probes ?: return Status.CHECKING
        return if (result.all { it.skipped || it.latencyMs != null }) Status.ONLINE else Status.DEGRADED
    }

    @Synchronized fun nodes(): List<Pair<String, Int>> {
        val nodes = state.optJSONArray("nodes") ?: return emptyList()
        return (0 until nodes.length()).map { nodes.getJSONObject(it).let { n -> n.getString("server") to n.getInt("port") } }
    }

    /** Measures node reachability in the background; results older than 30 s are refreshed. */
    @Synchronized fun probeAsync(force: Boolean = false) {
        if (!enabled || probing) return
        if (!force && probes != null && System.currentTimeMillis() - probedAt < 30_000) return
        val targets = nodes()
        if (targets.isEmpty()) return
        probing = true
        notifyChanged()
        Thread({
            val result = targets.mapIndexed { i, (host, port) ->
                if (i == UDP_ONLY_NODE) null else prober.submit<Probe> { probe(host, port) }
            }.mapIndexed { i, task ->
                val (host, port) = targets[i]
                if (task == null) Probe(host, port, null, skipped = true)
                else runCatching { task.get(5, TimeUnit.SECONDS) }.getOrNull() ?: Probe(host, port, null)
            }
            if (enabled) {
                probes = result
                probedAt = System.currentTimeMillis()
            }
            probing = false
            notifyChanged()
        }, "ss-probe").start()
    }

    private fun probe(host: String, port: Int): Probe = runCatching {
        val address = InetSocketAddress(InetAddress.getByName(host), port)
        Socket().use { socket ->
            val start = System.nanoTime()
            socket.connect(address, 3000)
            Probe(host, port, (System.nanoTime() - start) / 1_000_000)
        }
    }.getOrElse { Probe(host, port, null) }

    private fun commit(next: JSONObject) {
        val stream = storage.startWrite()
        try {
            stream.write(next.toString().toByteArray())
            storage.finishWrite(stream)
        } catch (e: Exception) {
            storage.failWrite(stream)
            throw e
        }
        state = next
    }

    // Network I/O happens on the panel's single worker. Publish both subscriptions together.
    fun refresh(imported: String? = null) {
        val config = if (imported != null) JSONObject(imported) else synchronized(this) {
            JSONObject(state.getJSONObject("config").toString())
        }
        val tcpUrl = config.getString("homeTcp")
        val udpUrl = config.getString("homeUdp")
        val company = node(config.getJSONObject("company"))
        val nodes = JSONArray().put(loadSubscription(tcpUrl, false)).put(loadSubscription(udpUrl, true)).put(company)
        synchronized(this) {
            val next = JSONObject().put("nodes", nodes).put("updated", System.currentTimeMillis())
                .put("config", JSONObject().put("homeTcp", tcpUrl).put("homeUdp", udpUrl).put("company", company))
            commit(next)
            if (enabled) activate(next) else notifyChanged()
        }
        probeAsync(force = true)
    }

    /** The connection itself is not persisted: it lasts until toggled off or the process ends. */
    @Synchronized fun toggle() {
        if (!enabled) {
            activate(state)
            probeAsync(force = true)
        } else {
            deactivate()
        }
    }

    @Synchronized fun configured() = state.has("nodes")
    @Synchronized fun updated() = state.optLong("updated")
    /** Hosts reached through SS are remote: use the WAN profile and leave room for SS overhead. */
    fun tuneStream(context: com.limelight.nvstream.ConnectionContext, maxPacketSize: Int, address: () -> InetAddress) {
        if (!enabled || !routed(runCatching(address).getOrNull())) return
        context.negotiatedRemoteStreaming = com.limelight.nvstream.StreamConfiguration.STREAM_CFG_REMOTE
        context.negotiatedPacketSize = minOf(maxPacketSize, 1024)
    }

    fun routed(addr: InetAddress?): Boolean {
        if (!enabled || addr !is Inet4Address) return false
        val b = addr.address
        return b[0].toInt() == 10 && ((b[1].toInt() == 0 && b[2].toInt() == 0) ||
            (b[1].toInt() == 1 && b[2].toInt() == 1))
    }
}
