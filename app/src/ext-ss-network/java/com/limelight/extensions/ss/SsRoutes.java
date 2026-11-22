// EXTENSION DEVELOPMENT [EXT-SS-NETWORK] [ADDED]
package com.limelight.extensions.ss;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;

import com.limelight.nvstream.ConnectionContext;
import com.limelight.nvstream.StreamConfiguration;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

/**
 * Persistent SS routing state: imported config, the three resolved nodes
 * (home TCP, home UDP, company) and node reachability.
 *
 * Routes: 10.0.0.0/24 -> home (TCP via home TCP node, UDP via home UDP node),
 *         10.1.1.0/24 -> company node.
 */
public final class SsRoutes {
    public enum Status { UNAVAILABLE, OFF, CHECKING, ONLINE, DEGRADED }

    /** TCP handshake to a node's SS port; latencyMs is -1 when it did not answer. */
    public static final class Probe {
        public final String host;
        public final int port;
        public final long latencyMs;
        /** The UDP-only home subscription cannot be probed over TCP. */
        public final boolean skipped;

        Probe(String host, int port, long latencyMs, boolean skipped) {
            this.host = host;
            this.port = port;
            this.latencyMs = latencyMs;
            this.skipped = skipped;
        }

        public boolean reachable() {
            return skipped || latencyMs >= 0;
        }
    }

    public static final class Node {
        public final String host;
        public final int port;

        Node(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /** Node order is home TCP, home UDP, company; the home UDP node only serves UDP. */
    private static final int UDP_ONLY_NODE = 1;
    private static final Set<String> CIPHERS =
            new HashSet<>(Arrays.asList("aes-256-gcm", "aes-128-gcm", "chacha20-ietf-poly1305"));

    /** Serializes subscription/config work triggered from the UI. */
    static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final ExecutorService prober = Executors.newFixedThreadPool(3);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Set<Runnable> listeners = new CopyOnWriteArraySet<>();

    private static volatile boolean enabled;
    private static volatile List<Probe> probes;
    private static volatile boolean probing;
    private static volatile long probedAt;
    private static Boolean nativeAvailable;
    private static AtomicFile storage;
    private static JSONObject state = new JSONObject();

    private SsRoutes() {}

    public static synchronized void init(Context context) {
        if (storage != null) return;
        storage = new AtomicFile(new File(context.getNoBackupFilesDir(), "stream-routes.json"));
        if (!storage.getBaseFile().exists() && !new File(storage.getBaseFile().getPath() + ".bak").exists()) {
            return;
        }
        try (InputStream in = storage.openRead()) {
            // Only the config and nodes persist; every new process starts disconnected.
            state = new JSONObject(readText(in));
            state.remove("enabled");
        } catch (Throwable ignored) {
            // A corrupt state simply starts unconfigured.
        }
    }

    /** The SS libraries are only packaged for ARM ABIs. */
    public static synchronized boolean nativeAvailable() {
        if (nativeAvailable == null) {
            boolean ok;
            try {
                Class.forName(SsNative.class.getName(), true, SsRoutes.class.getClassLoader());
                ok = true;
            } catch (Throwable t) {
                ok = false;
            }
            nativeAvailable = ok;
        }
        return nativeAvailable;
    }

    static String readText(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = input.read(buf)) >= 0) {
            if (result.size() + n > 256 * 1024) throw new IOException("config larger than 256 KiB");
            result.write(buf, 0, n);
        }
        return new String(result.toByteArray(), StandardCharsets.UTF_8);
    }

    private static JSONObject node(JSONObject o) throws JSONException {
        String server = o.getString("server");
        int port = o.getInt("port");
        String cipher = o.getString("cipher");
        String password = o.getString("password");
        boolean validHost = !server.trim().isEmpty() && server.length() <= 253;
        for (int i = 0; validHost && i < server.length(); i++) {
            char c = server.charAt(i);
            if (Character.isWhitespace(c) || c == '/') validHost = false;
        }
        if (!validHost || port < 1 || port > 65535 || !CIPHERS.contains(cipher) || password.isEmpty()) {
            throw new IllegalArgumentException("invalid SS node or unsupported cipher");
        }
        return new JSONObject().put("server", server).put("port", port).put("cipher", cipher)
                .put("password", password).put("tfo", o.optBoolean("tfo"));
    }

    private static JSONObject loadSubscription(String spec, boolean udp) throws Exception {
        URL url = new URL(spec);
        if (!"https".equals(url.getProtocol()) || url.getHost().isEmpty()) {
            throw new IllegalArgumentException("subscriptions must use HTTPS");
        }
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        String yaml;
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            if (code < 200 || code > 299) throw new IOException("subscription HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                yaml = readText(in);
            }
        } finally {
            conn.disconnect();
        }
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(8);
        options.setCodePointLimit(256 * 1024);
        options.setAllowDuplicateKeys(false);
        Object root = new Yaml(new SafeConstructor(options)).load(yaml);
        Object entries = root instanceof Map ? ((Map<?, ?>) root).get("proxies") : null;
        if (!(entries instanceof List)) throw new IllegalArgumentException("subscription has no proxies");
        Map<?, ?> entry = null;
        for (Object item : (List<?>) entries) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) item;
            if (!"ss".equals(m.get("type")) || (udp && Boolean.FALSE.equals(m.get("udp")))) continue;
            if (entry != null) throw new IllegalArgumentException("subscription needs exactly one SS node");
            entry = m;
        }
        if (entry == null) throw new IllegalArgumentException("subscription needs exactly one SS node");
        if (entry.get("plugin") != null || Boolean.TRUE.equals(entry.get("udp-over-tcp"))) {
            throw new IllegalArgumentException("node needs an extra transport plugin");
        }
        JSONObject raw = new JSONObject();
        for (String key : new String[]{"server", "port", "cipher", "password", "tfo"}) {
            raw.put(key, entry.get(key));
        }
        return node(raw);
    }

    private static void activate(JSONObject s) throws JSONException {
        if (SsNative.installHooks() <= 0) throw new IllegalStateException("cannot hook libmoonlight-core");
        byte[] nodes = s.getJSONArray("nodes").toString().getBytes(StandardCharsets.UTF_8);
        if (!SsNative.setSsRoutes(nodes)) throw new IllegalStateException("cannot start SS");
        enabled = true;
        probes = null;
        probedAt = 0;
        notifyChanged();
    }

    private static void deactivate() {
        if (nativeAvailable()) SsNative.setSsRoutes(null);
        enabled = false;
        probes = null;
        probedAt = 0;
        notifyChanged();
    }

    public static void addListener(Runnable listener) { listeners.add(listener); }
    public static void removeListener(Runnable listener) { listeners.remove(listener); }

    private static void notifyChanged() {
        main.post(() -> {
            for (Runnable listener : listeners) listener.run();
        });
    }

    public static boolean isEnabled() { return enabled; }
    public static boolean isProbing() { return probing; }
    public static List<Probe> probes() { return probes; }

    public static Status status() {
        if (!nativeAvailable()) return Status.UNAVAILABLE;
        if (!enabled) return Status.OFF;
        List<Probe> result = probes;
        if (result == null) return Status.CHECKING;
        for (Probe p : result) {
            if (!p.reachable()) return Status.DEGRADED;
        }
        return Status.ONLINE;
    }

    public static synchronized List<Node> nodes() {
        JSONArray nodes = state.optJSONArray("nodes");
        if (nodes == null) return Collections.emptyList();
        List<Node> result = new ArrayList<>();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n != null) result.add(new Node(n.optString("server"), n.optInt("port")));
        }
        return result;
    }

    public static void probeAsync() { probeAsync(false); }

    /** Measures node reachability in the background; results older than 30 s are refreshed. */
    public static synchronized void probeAsync(boolean force) {
        if (!enabled || probing) return;
        if (!force && probes != null && System.currentTimeMillis() - probedAt < 30_000) return;
        final List<Node> targets = nodes();
        if (targets.isEmpty()) return;
        probing = true;
        notifyChanged();
        new Thread(() -> {
            List<Future<Probe>> tasks = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                final Node n = targets.get(i);
                tasks.add(i == UDP_ONLY_NODE ? null : prober.submit((Callable<Probe>) () -> probe(n.host, n.port)));
            }
            List<Probe> result = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                Node n = targets.get(i);
                Future<Probe> task = tasks.get(i);
                Probe p;
                if (task == null) {
                    p = new Probe(n.host, n.port, -1, true);
                } else {
                    try {
                        p = task.get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        p = new Probe(n.host, n.port, -1, false);
                    }
                }
                result.add(p);
            }
            if (enabled) {
                probes = Collections.unmodifiableList(result);
                probedAt = System.currentTimeMillis();
            }
            probing = false;
            notifyChanged();
        }, "ss-probe").start();
    }

    private static Probe probe(String host, int port) {
        try {
            InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(host), port);
            try (Socket socket = new Socket()) {
                long start = System.nanoTime();
                socket.connect(address, 3000);
                return new Probe(host, port, (System.nanoTime() - start) / 1_000_000, false);
            }
        } catch (Exception e) {
            return new Probe(host, port, -1, false);
        }
    }

    private static void commit(JSONObject next) throws IOException {
        FileOutputStream stream = storage.startWrite();
        try {
            stream.write(next.toString().getBytes(StandardCharsets.UTF_8));
            storage.finishWrite(stream);
        } catch (IOException e) {
            storage.failWrite(stream);
            throw e;
        }
        state = next;
    }

    /** Network I/O; run on {@link #worker}. Publishes both home subscriptions together. */
    static void refresh(String imported) throws Exception {
        JSONObject config;
        synchronized (SsRoutes.class) {
            config = imported != null ? new JSONObject(imported)
                    : new JSONObject(state.getJSONObject("config").toString());
        }
        String tcpUrl = config.getString("homeTcp");
        String udpUrl = config.getString("homeUdp");
        JSONObject company = node(config.getJSONObject("company"));
        JSONArray nodes = new JSONArray().put(loadSubscription(tcpUrl, false))
                .put(loadSubscription(udpUrl, true)).put(company);
        synchronized (SsRoutes.class) {
            JSONObject next = new JSONObject().put("nodes", nodes).put("updated", System.currentTimeMillis())
                    .put("config", new JSONObject().put("homeTcp", tcpUrl).put("homeUdp", udpUrl).put("company", company));
            commit(next);
            if (enabled) activate(next);
            else notifyChanged();
        }
        probeAsync(true);
    }

    /** The connection itself is not persisted: it lasts until toggled off or the process ends. */
    static synchronized void toggle() throws Exception {
        if (!enabled) {
            activate(state);
            probeAsync(true);
        } else {
            deactivate();
        }
    }

    public static synchronized boolean configured() { return state.has("nodes"); }
    public static synchronized long updated() { return state.optLong("updated"); }

    public static long receivedBytes() {
        return enabled ? SsNative.ssReceivedBytes() : 0;
    }

    /** Integration hook for NvConnection: hosts reached through SS use the WAN profile. */
    public interface AddressSource {
        InetAddress get() throws IOException;
    }

    public static void tuneStream(ConnectionContext context, int maxPacketSize, AddressSource address) {
        if (!enabled) return;
        InetAddress resolved;
        try {
            resolved = address.get();
        } catch (IOException e) {
            return;
        }
        if (!routed(resolved)) return;
        context.negotiatedRemoteStreaming = StreamConfiguration.STREAM_CFG_REMOTE;
        context.negotiatedPacketSize = Math.min(maxPacketSize, 1024);
    }

    public static boolean routed(InetAddress addr) {
        if (!enabled || !(addr instanceof Inet4Address)) return false;
        byte[] b = addr.getAddress();
        return b[0] == 10 && ((b[1] == 0 && b[2] == 0) || (b[1] == 1 && b[2] == 1));
    }
}
