package com.limelight.ss

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import com.limelight.R
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

object SsRoutes {
    enum class Status { OFF, CHECKING, ONLINE, DEGRADED }

    enum class HomeMode(val wire: String) {
        DIRECT("direct"),
        RELAY("relay");

        companion object {
            fun fromWire(value: String?): HomeMode = if (value == "relay") RELAY else DIRECT
        }
    }

    /** Sanitized error category that never carries URLs, credentials, or raw YAML. */
    class SsException(val messageRes: Int, val httpCode: Int = 0) :
        Exception("ss-error:$messageRes:$httpCode")

    /**
     * TCP handshake to a node's SS port; [latencyMs] is null when it did not answer.
     * [skipped] nodes (a UDP-only home subscription) cannot be probed over TCP.
     */
    data class Probe(val host: String, val port: Int, val latencyMs: Long?, val skipped: Boolean = false)

    data class Node(val labelRes: Int, val host: String, val port: Int, val udpOnly: Boolean = false)

    private const val MAX_REDIRECTS = 5

    /** Serializes subscription/config/toggle work triggered from the UI. */
    val worker = Executors.newSingleThreadExecutor()
    private val prober = Executors.newFixedThreadPool(3)
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile var enabled = false
        private set

    /** Results in display node order; null until the first probe finishes. */
    @Volatile var probes: List<Probe>? = null
        private set

    @Volatile var probing = false
        private set

    @Volatile private var probedAt = 0L
    @Volatile private var systemVpnActive = false
    @Volatile private var underlyingNetwork: Network? = null
    @Volatile private var underlyingNetHandle = 0L

    private var appContext: Context? = null
    private lateinit var storage: AtomicFile
    private var state = JSONObject()
    private val ciphers = setOf(
        "aes-256-gcm",
        "aes-128-gcm",
        "chacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm",
        "2022-blake3-chacha20-poly1305",
    )

    @Synchronized fun init(context: Context) {
        if (::storage.isInitialized) return
        appContext = context.applicationContext
        storage = AtomicFile(File(appContext!!.noBackupFilesDir, "stream-routes.json"))
        refreshNetworkState()
        registerNetworkMonitor(appContext!!)
        if (!storage.baseFile.exists() && !File(storage.baseFile.path + ".bak").exists()) return
        // Only the config, nodes, and homeMode persist; every new process starts disconnected.
        runCatching {
            val loaded = JSONObject(storage.openRead().use { readText(it) })
            loaded.remove("enabled")
            migrateLegacyState(loaded)
            state = loaded
        }
    }

    private fun migrateLegacyState(s: JSONObject) {
        val legacyNodes = s.optJSONArray("nodes")
        if (legacyNodes != null && legacyNodes.length() >= 3) {
            if (!s.has("directNodes")) {
                s.put("directNodes", JSONArray().put(legacyNodes.getJSONObject(0)).put(legacyNodes.getJSONObject(1)))
            }
            if (!s.has("companyNode")) {
                s.put("companyNode", legacyNodes.getJSONObject(2))
            }
        }
        if (!s.has("relayNodes")) {
            s.optJSONObject("config")?.let { cfg ->
                runCatching { parseRelayNodesStrict(cfg) }.getOrNull()?.let { s.put("relayNodes", it) }
            }
        }
        if (!s.has("homeMode")) {
            s.put("homeMode", HomeMode.DIRECT.wire)
        }
    }

    private fun registerNetworkMonitor(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onNetworkChanged()
                override fun onLost(network: Network) = onNetworkChanged()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onNetworkChanged()
            }
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
        }
    }

    private fun onNetworkChanged() {
        val prevVpn = systemVpnActive
        val prevHandle = underlyingNetHandle
        refreshNetworkState()
        if (enabled && (prevVpn != systemVpnActive || prevHandle != underlyingNetHandle)) {
            pushBypassNetHandle()
            worker.execute {
                synchronized(this) {
                    if (enabled) runCatching { activate(state) }
                }
            }
        }
        if (prevVpn != systemVpnActive || prevHandle != underlyingNetHandle) {
            notifyChanged()
        }
    }

    fun refreshNetworkState() {
        val ctx = appContext
        if (ctx == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            systemVpnActive = false
            underlyingNetwork = null
            underlyingNetHandle = 0L
            return
        }
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            var vpn = false
            cm.activeNetwork?.let { active ->
                cm.getNetworkCapabilities(active)?.let { caps ->
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    ) {
                        vpn = true
                    }
                }
            }
            var bestPhysical: Network? = null
            var bestScore = -1
            @Suppress("DEPRECATION")
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                ) {
                    vpn = true
                    continue
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                var score = 0
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 100
                score += when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 30
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 20
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 10
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    bestPhysical = net
                }
            }
            systemVpnActive = vpn
            if (vpn && bestPhysical != null) {
                underlyingNetwork = bestPhysical
                underlyingNetHandle = bestPhysical.networkHandle
            } else {
                underlyingNetwork = null
                underlyingNetHandle = 0L
            }
        }.onFailure {
            systemVpnActive = false
            underlyingNetwork = null
            underlyingNetHandle = 0L
        }
    }

    private fun pushBypassNetHandle() {
        try {
            SsNative.setBypassNetHandle(if (enabled && systemVpnActive) underlyingNetHandle else 0L)
        } catch (_: UnsatisfiedLinkError) {
        }
    }

    fun isSystemVpnActive(): Boolean {
        refreshNetworkState()
        return systemVpnActive
    }

    fun hasBypassNetwork(): Boolean = systemVpnActive && underlyingNetwork != null && underlyingNetHandle != 0L

    private fun bindSocketToUnderlyingNetwork(socket: Socket) {
        if (!enabled || !systemVpnActive || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val net = underlyingNetwork ?: return
        runCatching { net.bindSocket(socket) }
    }

    /** Resolves a host to IPv4 via the physical non-VPN network when bypassing a VPN (avoids Fake-IP). */
    private fun resolveHost(host: String, preferBypass: Boolean): InetAddress {
        val net = if (preferBypass && systemVpnActive) underlyingNetwork else null
        val addrs = if (net != null) net.getAllByName(host) else InetAddress.getAllByName(host)
        return addrs.firstOrNull { it is Inet4Address } ?: addrs.firstOrNull()
            ?: throw UnknownHostException("empty DNS result")
    }

    internal fun readText(input: InputStream): String {
        val result = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            require(result.size() + n <= 256 * 1024) { "config larger than 256 KiB" }
            result.write(buf, 0, n)
        }
        return result.toString("UTF-8")
    }

    private fun node(o: JSONObject?): JSONObject {
        if (o == null) throw SsException(R.string.ss_err_invalid_node)
        val server = o.optString("server", "")
        val port = o.optInt("port", -1)
        val cipher = o.optString("cipher", "")
        val password = o.optString("password", "")
        if (cipher !in ciphers) throw SsException(R.string.ss_err_unsupported_cipher)
        if (server.isBlank() || server.length > 253 || server.any { it.isWhitespace() || it == '/' } ||
            port !in 1..65535 || password.isEmpty()
        ) {
            throw SsException(R.string.ss_err_invalid_node)
        }
        return JSONObject()
            .put("server", server)
            .put("port", port)
            .put("cipher", cipher)
            .put("password", password)
            .put("tfo", o.optBoolean("tfo"))
    }

    private fun parseRelayNodesStrict(config: JSONObject): JSONArray? {
        val relayTcpRaw = config.optJSONObject("homeRelayTcp")
        val relayUdpRaw = config.optJSONObject("homeRelayUdp")
        val singleRelayRaw = config.optJSONObject("homeRelay")
        if (relayTcpRaw == null && relayUdpRaw == null && singleRelayRaw == null) return null
        val tcp = node(relayTcpRaw ?: singleRelayRaw)
        val udp = node(relayUdpRaw ?: singleRelayRaw ?: relayTcpRaw)
        return JSONArray().put(tcp).put(udp)
    }

    private fun loadSubscription(spec: String, udp: Boolean): JSONObject {
        val url = URL(spec)
        if (!url.protocol.equals("https", ignoreCase = true) || url.host.isBlank()) {
            throw SsException(R.string.ss_err_https_only)
        }
        var yaml: String? = null
        var current = url
        for (hop in 0..MAX_REDIRECTS) {
            val conn = current.openConnection() as HttpsURLConnection
            try {
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = false
                val code = conn.responseCode
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    if (hop == MAX_REDIRECTS) throw SsException(R.string.ss_err_redirect_limit)
                    val location = conn.getHeaderField("Location")?.trim()
                    if (location.isNullOrEmpty()) throw SsException(R.string.ss_err_redirect_invalid)
                    val next = URL(current, location)
                    if (!next.protocol.equals("https", ignoreCase = true) || next.host.isBlank()) {
                        throw SsException(R.string.ss_err_redirect_https_only)
                    }
                    current = next
                    continue
                }
                if (code !in 200..299) throw SsException(R.string.ss_err_http_status, code)
                yaml = conn.inputStream.use { readText(it) }
                break
            } finally {
                conn.disconnect()
            }
        }
        val body = yaml ?: throw SsException(R.string.ss_err_redirect_limit)
        val options = LoaderOptions().apply {
            maxAliasesForCollections = 8
            codePointLimit = 256 * 1024
            isAllowDuplicateKeys = false
        }
        val root = Yaml(SafeConstructor(options)).load<Any>(body) as? Map<*, *>
        val entries = root?.get("proxies") as? List<*> ?: throw SsException(R.string.ss_err_yaml_no_proxies)
        val entry = entries.filterIsInstance<Map<*, *>>().singleOrNull {
            it["type"] == "ss" && (!udp || it["udp"] != false)
        } ?: throw SsException(R.string.ss_err_yaml_node_count)
        if (entry["plugin"] != null || entry["udp-over-tcp"] == true) {
            throw SsException(R.string.ss_err_plugin_unsupported)
        }
        return node(JSONObject().apply {
            for (key in listOf("server", "port", "cipher", "password", "tfo")) put(key, entry[key])
        })
    }

    private fun withResolvedIp(source: JSONObject): JSONObject {
        val copy = JSONObject(source.toString())
        if (systemVpnActive && underlyingNetwork != null) {
            val host = copy.optString("server", "")
            if (host.isNotEmpty()) {
                runCatching { resolveHost(host, true) }.getOrNull()?.let { addr ->
                    if (addr is Inet4Address) copy.put("resolved_ip", addr.hostAddress)
                }
            }
        }
        return copy
    }

    private fun activeRuntimeNodes(s: JSONObject): JSONArray {
        val mode = HomeMode.fromWire(s.optString("homeMode", HomeMode.DIRECT.wire))
        val homePair = if (mode == HomeMode.RELAY) s.optJSONArray("relayNodes") else s.optJSONArray("directNodes")
        if (homePair == null || homePair.length() < 2) {
            throw SsException(if (mode == HomeMode.RELAY) R.string.ss_err_no_relay else R.string.ss_err_no_direct)
        }
        val company = s.optJSONObject("companyNode")
            ?: s.optJSONArray("nodes")?.takeIf { it.length() >= 3 }?.optJSONObject(2)
            ?: throw SsException(R.string.ss_err_invalid_node)
        return JSONArray()
            .put(withResolvedIp(homePair.getJSONObject(0)))
            .put(withResolvedIp(homePair.getJSONObject(1)))
            .put(withResolvedIp(company))
    }

    private fun activate(s: JSONObject) {
        refreshNetworkState()
        if (SsNative.installHooks() <= 0) throw SsException(R.string.ss_err_native_hook)
        try {
            SsNative.setBypassNetHandle(if (systemVpnActive) underlyingNetHandle else 0L)
        } catch (_: UnsatisfiedLinkError) {
        }
        val runtimeNodes = activeRuntimeNodes(s)
        if (!SsNative.setSsRoutes(runtimeNodes.toString().toByteArray())) {
            throw SsException(R.string.ss_err_native_start)
        }
        enabled = true
        probes = null
        probedAt = 0
        notifyChanged()
    }

    private fun deactivate() {
        SsNative.setSsRoutes(null)
        try {
            SsNative.setBypassNetHandle(0L)
        } catch (_: UnsatisfiedLinkError) {
        }
        enabled = false
        probes = null
        probedAt = 0
        notifyChanged()
    }

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    private fun notifyChanged() = main.post { listeners.forEach { it() } }

    @Synchronized fun homeMode(): HomeMode = HomeMode.fromWire(state.optString("homeMode", HomeMode.DIRECT.wire))
    @Synchronized fun hasDirectHomeNodes(): Boolean = (state.optJSONArray("directNodes")?.length() ?: 0) >= 2
    @Synchronized fun hasRelayHomeNodes(): Boolean = (state.optJSONArray("relayNodes")?.length() ?: 0) >= 2
    @Synchronized fun hasSubscriptionUrls(): Boolean {
        val cfg = state.optJSONObject("config") ?: return false
        return cfg.optString("homeTcp", "").isNotBlank() && cfg.optString("homeUdp", "").isNotBlank()
    }

    fun status(): Status {
        if (!enabled) return Status.OFF
        val result = probes ?: return Status.CHECKING
        return if (result.all { it.skipped || it.latencyMs != null }) Status.ONLINE else Status.DEGRADED
    }

    @Synchronized fun nodes(): List<Node> {
        val result = mutableListOf<Node>()
        val mode = homeMode()
        val homePair = if (mode == HomeMode.RELAY) state.optJSONArray("relayNodes") else state.optJSONArray("directNodes")
        if (homePair != null && homePair.length() >= 2) {
            val tcp = homePair.optJSONObject(0)
            val udp = homePair.optJSONObject(1)
            if (mode == HomeMode.RELAY && tcp != null && udp != null &&
                tcp.optString("server") == udp.optString("server") &&
                tcp.optInt("port") == udp.optInt("port")
            ) {
                result += Node(R.string.ss_node_home_relay, tcp.optString("server"), tcp.optInt("port"), false)
            } else {
                if (tcp != null) {
                    result += Node(
                        if (mode == HomeMode.RELAY) R.string.ss_node_home_relay_tcp else R.string.ss_node_home_tcp,
                        tcp.optString("server"),
                        tcp.optInt("port"),
                        false,
                    )
                }
                if (udp != null) {
                    result += Node(
                        if (mode == HomeMode.RELAY) R.string.ss_node_home_relay_udp else R.string.ss_node_home_udp,
                        udp.optString("server"),
                        udp.optInt("port"),
                        true,
                    )
                }
            }
        }
        state.optJSONObject("companyNode")?.let { company ->
            result += Node(R.string.ss_node_company, company.optString("server"), company.optInt("port"), false)
        }
        return result
    }

    /** Measures node reachability in the background; results older than 30 s are refreshed. */
    @Synchronized fun probeAsync(force: Boolean = false) {
        if (!enabled || probing) return
        if (!force && probes != null && System.currentTimeMillis() - probedAt < 30_000) return
        val targets = nodes()
        if (targets.isEmpty()) return
        refreshNetworkState()
        probing = true
        notifyChanged()
        Thread({
            val tasks = targets.map { node ->
                if (node.udpOnly) null else prober.submit<Probe> { probe(node.host, node.port) }
            }
            val result = tasks.mapIndexed { i, task ->
                val node = targets[i]
                if (task == null) Probe(node.host, node.port, null, skipped = true)
                else runCatching { task.get(5, TimeUnit.SECONDS) }.getOrNull() ?: Probe(node.host, node.port, null)
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
        val resolved = resolveHost(host, enabled)
        val address = InetSocketAddress(resolved, port)
        Socket().use { socket ->
            bindSocketToUnderlyingNetwork(socket)
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

    /** Network I/O; run on [worker]. Returns an optional sanitized warning message. */
    fun refresh(context: Context, imported: String? = null): String? {
        val (config, existingDirect, currentMode) = synchronized(this) {
            val cfg = if (imported != null) JSONObject(imported)
            else JSONObject(state.getJSONObject("config").toString())
            Triple(cfg, state.optJSONArray("directNodes"), homeMode())
        }
        val tcpUrl = config.optString("homeTcp", "").trim()
        val udpUrl = config.optString("homeUdp", "").trim()
        val hasSubUrls = tcpUrl.isNotEmpty() && udpUrl.isNotEmpty()
        val company = node(config.optJSONObject("company"))
        val relayNodes = parseRelayNodesStrict(config)

        if (imported == null && !hasSubUrls) throw SsException(R.string.ss_err_no_subscription_urls)
        if (!hasSubUrls && relayNodes == null) throw SsException(R.string.ss_err_no_subscription_urls)

        var directNodes = existingDirect
        var updatedAt = updated()
        var warning: String? = null
        if (hasSubUrls) {
            try {
                directNodes = JSONArray()
                    .put(loadSubscription(tcpUrl, false))
                    .put(loadSubscription(udpUrl, true))
                updatedAt = System.currentTimeMillis()
            } catch (subErr: Exception) {
                if (imported != null && relayNodes != null) {
                    directNodes = null
                    warning = context.getString(R.string.ss_warn_relay_only, describeError(context, subErr))
                } else {
                    throw subErr
                }
            }
        } else {
            directNodes = null
        }

        val nextMode = when {
            currentMode == HomeMode.DIRECT && directNodes == null && relayNodes != null -> HomeMode.RELAY
            currentMode == HomeMode.RELAY && relayNodes == null && directNodes != null -> HomeMode.DIRECT
            else -> currentMode
        }

        val savedConfig = JSONObject().put("company", company).apply {
            if (hasSubUrls) put("homeTcp", tcpUrl).put("homeUdp", udpUrl)
            config.optJSONObject("homeRelay")?.let { put("homeRelay", node(it)) }
            config.optJSONObject("homeRelayTcp")?.let { put("homeRelayTcp", node(it)) }
            config.optJSONObject("homeRelayUdp")?.let { put("homeRelayUdp", node(it)) }
        }

        synchronized(this) {
            val next = JSONObject()
                .put("config", savedConfig)
                .put("companyNode", company)
                .put("homeMode", nextMode.wire)
                .put("updated", updatedAt)
            if (directNodes != null) next.put("directNodes", directNodes)
            if (relayNodes != null) next.put("relayNodes", relayNodes)
            val activeHome = if (nextMode == HomeMode.RELAY) relayNodes else directNodes
            if (activeHome != null && activeHome.length() >= 2) {
                next.put("nodes", JSONArray()
                    .put(activeHome.getJSONObject(0))
                    .put(activeHome.getJSONObject(1))
                    .put(company))
            }
            commit(next)
            if (enabled) activate(next) else notifyChanged()
        }
        probeAsync(force = true)
        return warning
    }

    /** Switches home routing between direct subscriptions and fixed company relay; hot-swaps if active. */
    fun setHomeMode(mode: HomeMode) {
        synchronized(this) {
            if (mode == HomeMode.RELAY && !hasRelayHomeNodes()) throw SsException(R.string.ss_err_no_relay)
            if (mode == HomeMode.DIRECT && !hasDirectHomeNodes()) throw SsException(R.string.ss_err_no_direct)
            if (mode == homeMode()) return
            val next = JSONObject(state.toString()).put("homeMode", mode.wire)
            val activeHome = if (mode == HomeMode.RELAY) next.optJSONArray("relayNodes") else next.optJSONArray("directNodes")
            val company = next.optJSONObject("companyNode")
            if (activeHome != null && activeHome.length() >= 2 && company != null) {
                next.put("nodes", JSONArray()
                    .put(activeHome.getJSONObject(0))
                    .put(activeHome.getJSONObject(1))
                    .put(company))
            }
            commit(next)
            if (enabled) activate(next) else notifyChanged()
        }
        if (enabled) probeAsync(force = true)
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

    @Synchronized fun configured(): Boolean =
        state.has("companyNode") && (hasDirectHomeNodes() || hasRelayHomeNodes())

    @Synchronized fun updated(): Long = state.optLong("updated")

    fun describeError(context: Context, t: Throwable): String = when (t) {
        is SsException -> if (t.httpCode > 0) context.getString(t.messageRes, t.httpCode) else context.getString(t.messageRes)
        is UnknownHostException -> context.getString(R.string.ss_err_dns)
        is SocketTimeoutException -> context.getString(R.string.ss_err_timeout)
        is SSLException -> context.getString(R.string.ss_err_tls)
        is JSONException -> context.getString(R.string.ss_err_json_format)
        else -> context.getString(R.string.ss_error_generic)
    }

    /** Hosts reached through SS are remote: use the WAN profile and leave room for SS 2022 overhead. */
    fun tuneStream(context: com.limelight.nvstream.ConnectionContext, maxPacketSize: Int, address: () -> InetAddress) {
        if (!enabled || !routed(runCatching(address).getOrNull())) return
        context.negotiatedRemoteStreaming = com.limelight.nvstream.StreamConfiguration.STREAM_CFG_REMOTE
        // 1160 B payload + 28 B RTP/NV + 58 B SS 2022 S->C + 28 B IP/UDP = 1274 B (< 1280 B MTU floor)
        context.negotiatedPacketSize = minOf(maxPacketSize, 1160)
    }

    fun routed(addr: InetAddress?): Boolean {
        if (!enabled || addr !is Inet4Address) return false
        val b = addr.address
        return b[0].toInt() == 10 && ((b[1].toInt() == 0 && b[2].toInt() == 0) ||
            (b[1].toInt() == 1 && b[2].toInt() == 1))
    }
}
