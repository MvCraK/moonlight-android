// EXTENSION DEVELOPMENT [EXT-SS-NETWORK] [ADDED]
package com.limelight.extensions.ss;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;

import com.limelight.R;
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
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
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
import javax.net.ssl.SSLException;

/**
 * Persistent SS routing state: imported config, direct home subscription nodes,
 * optional fixed company-relay home nodes, company node, system VPN detection,
 * non-VPN socket binding to prevent double-tunneling, and node reachability.
 *
 * Routes: 10.0.0.0/24 -> home (direct subscriptions or fixed company relay),
 *         10.1.1.0/24 -> company node.
 */
public final class SsRoutes {
    public enum Status { UNAVAILABLE, OFF, CHECKING, ONLINE, DEGRADED }

    public enum HomeMode {
        DIRECT("direct"),
        RELAY("relay");

        final String wire;
        HomeMode(String wire) { this.wire = wire; }

        static HomeMode fromWire(String value) {
            return "relay".equals(value) ? RELAY : DIRECT;
        }
    }

    /** Sanitized error category that never carries URLs, credentials, or raw YAML. */
    public static final class SsException extends Exception {
        public final int messageRes;
        public final int httpCode;

        SsException(int messageRes) {
            this(messageRes, 0);
        }

        SsException(int messageRes, int httpCode) {
            super("ss-error:" + messageRes + ":" + httpCode);
            this.messageRes = messageRes;
            this.httpCode = httpCode;
        }
    }

    /** TCP handshake to a node's SS port; latencyMs is -1 when it did not answer. */
    public static final class Probe {
        public final String host;
        public final int port;
        public final long latencyMs;
        /** A UDP-only home subscription cannot be probed over TCP. */
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
        public final int labelRes;
        public final String host;
        public final int port;
        public final boolean udpOnly;

        Node(int labelRes, String host, int port, boolean udpOnly) {
            this.labelRes = labelRes;
            this.host = host;
            this.port = port;
            this.udpOnly = udpOnly;
        }
    }

    private static final int MAX_REDIRECTS = 5;
    private static final Set<String> CIPHERS =
            new HashSet<>(Arrays.asList(
                    "aes-256-gcm",
                    "aes-128-gcm",
                    "chacha20-ietf-poly1305",
                    "2022-blake3-aes-128-gcm",
                    "2022-blake3-aes-256-gcm",
                    "2022-blake3-chacha20-poly1305"
            ));

    /** Serializes subscription/config/toggle work triggered from the UI. */
    static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final ExecutorService prober = Executors.newFixedThreadPool(3);
    private static volatile Handler main;
    private static final Set<Runnable> listeners = new CopyOnWriteArraySet<>();

    private static volatile boolean enabled;
    private static volatile List<Probe> probes;
    private static volatile boolean probing;
    private static volatile long probedAt;
    private static volatile boolean systemVpnActive;
    private static volatile Network underlyingNetwork;
    private static volatile long underlyingNetHandle;
    private static Boolean nativeAvailable;
    private static Context appContext;
    private static AtomicFile storage;
    private static JSONObject state = new JSONObject();

    private SsRoutes() {}

    public static synchronized void init(Context context) {
        if (storage != null) return;
        appContext = context.getApplicationContext();
        storage = new AtomicFile(new File(appContext.getNoBackupFilesDir(), "stream-routes.json"));
        refreshNetworkState();
        registerNetworkMonitor(appContext);
        if (!storage.getBaseFile().exists() && !new File(storage.getBaseFile().getPath() + ".bak").exists()) {
            return;
        }
        try (InputStream in = storage.openRead()) {
            // Only the config, nodes, and homeMode persist; every new process starts disconnected.
            JSONObject loaded = new JSONObject(readText(in));
            loaded.remove("enabled");
            migrateLegacyState(loaded);
            state = loaded;
        } catch (Throwable ignored) {
            // A corrupt state simply starts unconfigured.
        }
    }

    private static void migrateLegacyState(JSONObject s) throws JSONException {
        JSONArray legacyNodes = s.optJSONArray("nodes");
        if (legacyNodes != null && legacyNodes.length() >= 3) {
            if (!s.has("directNodes")) {
                s.put("directNodes", new JSONArray()
                        .put(legacyNodes.getJSONObject(0))
                        .put(legacyNodes.getJSONObject(1)));
            }
            if (!s.has("companyNode")) {
                s.put("companyNode", legacyNodes.getJSONObject(2));
            }
        }
        if (!s.has("relayNodes")) {
            JSONObject cfg = s.optJSONObject("config");
            if (cfg != null) {
                JSONArray relay = parseRelayNodes(cfg);
                if (relay != null) s.put("relayNodes", relay);
            }
        }
        if (!s.has("homeMode")) {
            s.put("homeMode", HomeMode.DIRECT.wire);
        }
    }

    private static void registerNetworkMonitor(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { onNetworkChanged(); }
                @Override public void onLost(Network network) { onNetworkChanged(); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { onNetworkChanged(); }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder().build(), cb);
        } catch (Throwable ignored) {
        }
    }

    private static void onNetworkChanged() {
        boolean prevVpn = systemVpnActive;
        long prevHandle = underlyingNetHandle;
        refreshNetworkState();
        if (enabled && (prevVpn != systemVpnActive || prevHandle != underlyingNetHandle)) {
            pushBypassNetHandle();
            worker.execute(() -> {
                synchronized (SsRoutes.class) {
                    if (enabled) {
                        try {
                            activate(state);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
        }
        if (prevVpn != systemVpnActive || prevHandle != underlyingNetHandle) {
            notifyChanged();
        }
    }

    static void refreshNetworkState() {
        Context ctx = appContext;
        if (ctx == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            systemVpnActive = false;
            underlyingNetwork = null;
            underlyingNetHandle = 0L;
            return;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            boolean vpn = false;
            Network active = cm.getActiveNetwork();
            if (active != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))) {
                    vpn = true;
                }
            }
            Network bestPhysical = null;
            int bestScore = -1;
            Network[] all = cm.getAllNetworks();
            if (all != null) {
                for (Network net : all) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                    if (caps == null) continue;
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                            || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                        vpn = true;
                        continue;
                    }
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        continue;
                    }
                    int score = 0;
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 100;
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) score += 30;
                    else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) score += 20;
                    else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) score += 10;
                    if (score > bestScore) {
                        bestScore = score;
                        bestPhysical = net;
                    }
                }
            }
            systemVpnActive = vpn;
            if (vpn && bestPhysical != null) {
                underlyingNetwork = bestPhysical;
                underlyingNetHandle = bestPhysical.getNetworkHandle();
            } else {
                underlyingNetwork = null;
                underlyingNetHandle = 0L;
            }
        } catch (Throwable ignored) {
            systemVpnActive = false;
            underlyingNetwork = null;
            underlyingNetHandle = 0L;
        }
    }

    private static void pushBypassNetHandle() {
        if (!nativeAvailable()) return;
        try {
            SsNative.setBypassNetHandle(enabled && systemVpnActive ? underlyingNetHandle : 0L);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    public static boolean isSystemVpnActive() {
        refreshNetworkState();
        return systemVpnActive;
    }

    public static boolean hasBypassNetwork() {
        return systemVpnActive && underlyingNetwork != null && underlyingNetHandle != 0L;
    }

    static void bindSocketToUnderlyingNetwork(Socket socket) {
        if (!enabled || !systemVpnActive || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        Network net = underlyingNetwork;
        if (net == null) return;
        try {
            net.bindSocket(socket);
        } catch (IOException ignored) {
        }
    }

    static void bindSocketToUnderlyingNetwork(DatagramSocket socket) {
        if (!enabled || !systemVpnActive || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        Network net = underlyingNetwork;
        if (net == null) return;
        try {
            net.bindSocket(socket);
        } catch (IOException ignored) {
        }
    }

    /** Resolves a host to IPv4 via the physical non-VPN network when bypassing a VPN (avoids Fake-IP). */
    private static InetAddress resolveHost(String host, boolean preferBypass) throws IOException {
        Network net = (preferBypass && systemVpnActive) ? underlyingNetwork : null;
        InetAddress[] addrs = net != null ? net.getAllByName(host) : InetAddress.getAllByName(host);
        for (InetAddress addr : addrs) {
            if (addr instanceof Inet4Address) return addr;
        }
        if (addrs.length > 0) return addrs[0];
        throw new UnknownHostException("empty DNS result");
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

    private static JSONObject node(JSONObject o) throws SsException, JSONException {
        if (o == null) throw new SsException(R.string.ss_err_invalid_node);
        String server = o.optString("server", "");
        int port = o.optInt("port", -1);
        String cipher = o.optString("cipher", "");
        String password = o.optString("password", "");
        boolean validHost = !server.trim().isEmpty() && server.length() <= 253;
        for (int i = 0; validHost && i < server.length(); i++) {
            char c = server.charAt(i);
            if (Character.isWhitespace(c) || c == '/') validHost = false;
        }
        if (!CIPHERS.contains(cipher)) {
            throw new SsException(R.string.ss_err_unsupported_cipher);
        }
        if (!validHost || port < 1 || port > 65535 || password.isEmpty()) {
            throw new SsException(R.string.ss_err_invalid_node);
        }
        return new JSONObject().put("server", server).put("port", port).put("cipher", cipher)
                .put("password", password).put("tfo", o.optBoolean("tfo"));
    }

    private static JSONArray parseRelayNodes(JSONObject config) throws JSONException {
        JSONObject relayTcpRaw = config.optJSONObject("homeRelayTcp");
        JSONObject relayUdpRaw = config.optJSONObject("homeRelayUdp");
        JSONObject singleRelayRaw = config.optJSONObject("homeRelay");
        if (relayTcpRaw == null && relayUdpRaw == null && singleRelayRaw == null) {
            return null;
        }
        try {
            JSONObject tcp = node(relayTcpRaw != null ? relayTcpRaw : singleRelayRaw);
            JSONObject udp = node(relayUdpRaw != null ? relayUdpRaw : (singleRelayRaw != null ? singleRelayRaw : relayTcpRaw));
            return new JSONArray().put(tcp).put(udp);
        } catch (SsException e) {
            return null;
        }
    }

    private static JSONArray parseRelayNodesStrict(JSONObject config) throws SsException, JSONException {
        JSONObject relayTcpRaw = config.optJSONObject("homeRelayTcp");
        JSONObject relayUdpRaw = config.optJSONObject("homeRelayUdp");
        JSONObject singleRelayRaw = config.optJSONObject("homeRelay");
        if (relayTcpRaw == null && relayUdpRaw == null && singleRelayRaw == null) {
            return null;
        }
        JSONObject tcp = node(relayTcpRaw != null ? relayTcpRaw : singleRelayRaw);
        JSONObject udp = node(relayUdpRaw != null ? relayUdpRaw : (singleRelayRaw != null ? singleRelayRaw : relayTcpRaw));
        return new JSONArray().put(tcp).put(udp);
    }

    private static JSONObject loadSubscription(String spec, boolean udp) throws Exception {
        URL url = new URL(spec);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()) {
            throw new SsException(R.string.ss_err_https_only);
        }
        String yaml = null;
        URL current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpsURLConnection conn = (HttpsURLConnection) current.openConnection();
            try {
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setInstanceFollowRedirects(false);
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    if (hop == MAX_REDIRECTS) {
                        throw new SsException(R.string.ss_err_redirect_limit);
                    }
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) {
                        throw new SsException(R.string.ss_err_redirect_invalid);
                    }
                    URL next = new URL(current, location.trim());
                    if (!"https".equalsIgnoreCase(next.getProtocol()) || next.getHost().isEmpty()) {
                        throw new SsException(R.string.ss_err_redirect_https_only);
                    }
                    current = next;
                    continue;
                }
                if (code < 200 || code > 299) {
                    throw new SsException(R.string.ss_err_http_status, code);
                }
                try (InputStream in = conn.getInputStream()) {
                    yaml = readText(in);
                }
                break;
            } finally {
                conn.disconnect();
            }
        }
        if (yaml == null) {
            throw new SsException(R.string.ss_err_redirect_limit);
        }
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(8);
        options.setCodePointLimit(256 * 1024);
        options.setAllowDuplicateKeys(false);
        Object root = new Yaml(new SafeConstructor(options)).load(yaml);
        Object entries = root instanceof Map ? ((Map<?, ?>) root).get("proxies") : null;
        if (!(entries instanceof List)) {
            throw new SsException(R.string.ss_err_yaml_no_proxies);
        }
        Map<?, ?> entry = null;
        for (Object item : (List<?>) entries) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) item;
            if (!"ss".equals(m.get("type")) || (udp && Boolean.FALSE.equals(m.get("udp")))) continue;
            if (entry != null) throw new SsException(R.string.ss_err_yaml_node_count);
            entry = m;
        }
        if (entry == null) throw new SsException(R.string.ss_err_yaml_node_count);
        if (entry.get("plugin") != null || Boolean.TRUE.equals(entry.get("udp-over-tcp"))) {
            throw new SsException(R.string.ss_err_plugin_unsupported);
        }
        JSONObject raw = new JSONObject();
        for (String key : new String[]{"server", "port", "cipher", "password", "tfo"}) {
            raw.put(key, entry.get(key));
        }
        return node(raw);
    }

    private static JSONArray activeRuntimeNodes(JSONObject s) throws SsException, JSONException {
        HomeMode mode = HomeMode.fromWire(s.optString("homeMode", HomeMode.DIRECT.wire));
        JSONArray homePair = mode == HomeMode.RELAY ? s.optJSONArray("relayNodes") : s.optJSONArray("directNodes");
        if (homePair == null || homePair.length() < 2) {
            throw new SsException(mode == HomeMode.RELAY ? R.string.ss_err_no_relay : R.string.ss_err_no_direct);
        }
        JSONObject company = s.optJSONObject("companyNode");
        if (company == null) {
            JSONArray legacy = s.optJSONArray("nodes");
            if (legacy != null && legacy.length() >= 3) company = legacy.optJSONObject(2);
        }
        if (company == null) {
            throw new SsException(R.string.ss_err_invalid_node);
        }
        JSONArray active = new JSONArray()
                .put(withResolvedIp(homePair.getJSONObject(0)))
                .put(withResolvedIp(homePair.getJSONObject(1)))
                .put(withResolvedIp(company));
        return active;
    }

    private static JSONObject withResolvedIp(JSONObject source) throws JSONException {
        JSONObject copy = new JSONObject(source.toString());
        if (systemVpnActive && underlyingNetwork != null) {
            String host = copy.optString("server", "");
            if (!host.isEmpty()) {
                try {
                    InetAddress addr = resolveHost(host, true);
                    if (addr instanceof Inet4Address) {
                        copy.put("resolved_ip", addr.getHostAddress());
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return copy;
    }

    private static void activate(JSONObject s) throws SsException, JSONException {
        refreshNetworkState();
        if (SsNative.installHooks() <= 0) {
            throw new SsException(R.string.ss_err_native_hook);
        }
        try {
            SsNative.setBypassNetHandle(systemVpnActive ? underlyingNetHandle : 0L);
        } catch (UnsatisfiedLinkError ignored) {
        }
        JSONArray runtimeNodes = activeRuntimeNodes(s);
        byte[] nodes = runtimeNodes.toString().getBytes(StandardCharsets.UTF_8);
        if (!SsNative.setSsRoutes(nodes)) {
            throw new SsException(R.string.ss_err_native_start);
        }
        enabled = true;
        probes = null;
        probedAt = 0;
        notifyChanged();
    }

    private static void deactivate() {
        if (nativeAvailable()) {
            SsNative.setSsRoutes(null);
            try {
                SsNative.setBypassNetHandle(0L);
            } catch (UnsatisfiedLinkError ignored) {
            }
        }
        enabled = false;
        probes = null;
        probedAt = 0;
        notifyChanged();
    }

    public static void addListener(Runnable listener) { listeners.add(listener); }
    public static void removeListener(Runnable listener) { listeners.remove(listener); }

    private static Handler mainHandler() {
        Handler h = main;
        if (h == null) {
            synchronized (SsRoutes.class) {
                h = main;
                if (h == null) {
                    h = new Handler(Looper.getMainLooper());
                    main = h;
                }
            }
        }
        return h;
    }

    private static void notifyChanged() {
        mainHandler().post(() -> {
            for (Runnable listener : listeners) listener.run();
        });
    }

    public static boolean isEnabled() { return enabled; }
    public static boolean isProbing() { return probing; }
    public static List<Probe> probes() { return probes; }

    public static synchronized HomeMode homeMode() {
        return HomeMode.fromWire(state.optString("homeMode", HomeMode.DIRECT.wire));
    }

    public static synchronized boolean hasDirectHomeNodes() {
        JSONArray arr = state.optJSONArray("directNodes");
        return arr != null && arr.length() >= 2;
    }

    public static synchronized boolean hasRelayHomeNodes() {
        JSONArray arr = state.optJSONArray("relayNodes");
        return arr != null && arr.length() >= 2;
    }

    public static synchronized boolean hasSubscriptionUrls() {
        JSONObject cfg = state.optJSONObject("config");
        return cfg != null
                && !cfg.optString("homeTcp", "").trim().isEmpty()
                && !cfg.optString("homeUdp", "").trim().isEmpty();
    }

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
        List<Node> result = new ArrayList<>();
        HomeMode mode = homeMode();
        JSONArray homePair = mode == HomeMode.RELAY
                ? state.optJSONArray("relayNodes")
                : state.optJSONArray("directNodes");
        if (homePair != null && homePair.length() >= 2) {
            JSONObject tcp = homePair.optJSONObject(0);
            JSONObject udp = homePair.optJSONObject(1);
            if (mode == HomeMode.RELAY && tcp != null && udp != null
                    && tcp.optString("server").equals(udp.optString("server"))
                    && tcp.optInt("port") == udp.optInt("port")) {
                result.add(new Node(R.string.ss_node_home_relay,
                        tcp.optString("server"), tcp.optInt("port"), false));
            } else {
                if (tcp != null) {
                    result.add(new Node(
                            mode == HomeMode.RELAY ? R.string.ss_node_home_relay_tcp : R.string.ss_node_home_tcp,
                            tcp.optString("server"), tcp.optInt("port"), false));
                }
                if (udp != null) {
                    result.add(new Node(
                            mode == HomeMode.RELAY ? R.string.ss_node_home_relay_udp : R.string.ss_node_home_udp,
                            udp.optString("server"), udp.optInt("port"), true));
                }
            }
        }
        JSONObject company = state.optJSONObject("companyNode");
        if (company != null) {
            result.add(new Node(R.string.ss_node_company,
                    company.optString("server"), company.optInt("port"), false));
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
        refreshNetworkState();
        probing = true;
        notifyChanged();
        new Thread(() -> {
            List<Future<Probe>> tasks = new ArrayList<>();
            for (Node n : targets) {
                tasks.add(n.udpOnly ? null : prober.submit((Callable<Probe>) () -> probe(n.host, n.port)));
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
            InetAddress resolved = resolveHost(host, enabled);
            InetSocketAddress address = new InetSocketAddress(resolved, port);
            try (Socket socket = new Socket()) {
                bindSocketToUnderlyingNetwork(socket);
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

    /**
     * Network I/O; run on {@link #worker}. Returns an optional sanitized warning message
     * (null when everything succeeded cleanly).
     */
    static String refresh(Context context, String imported) throws Exception {
        JSONObject config;
        JSONArray existingDirect;
        HomeMode currentMode;
        synchronized (SsRoutes.class) {
            config = imported != null ? new JSONObject(imported)
                    : new JSONObject(state.getJSONObject("config").toString());
            existingDirect = state.optJSONArray("directNodes");
            currentMode = homeMode();
        }
        String tcpUrl = config.optString("homeTcp", "").trim();
        String udpUrl = config.optString("homeUdp", "").trim();
        boolean hasSubUrls = !tcpUrl.isEmpty() && !udpUrl.isEmpty();
        JSONObject company = node(config.optJSONObject("company"));
        JSONArray relayNodes = parseRelayNodesStrict(config);

        if (imported == null && !hasSubUrls) {
            throw new SsException(R.string.ss_err_no_subscription_urls);
        }
        if (!hasSubUrls && relayNodes == null) {
            throw new SsException(R.string.ss_err_no_subscription_urls);
        }

        JSONArray directNodes = existingDirect;
        long updatedAt = updated();
        String warning = null;
        if (hasSubUrls) {
            try {
                directNodes = new JSONArray()
                        .put(loadSubscription(tcpUrl, false))
                        .put(loadSubscription(udpUrl, true));
                updatedAt = System.currentTimeMillis();
            } catch (Exception subErr) {
                if (imported != null && relayNodes != null) {
                    // Keep fixed relay usable even if dynamic home subscriptions are unreachable.
                    directNodes = null;
                    warning = context.getString(R.string.ss_warn_relay_only, describeError(context, subErr));
                } else {
                    throw subErr;
                }
            }
        } else {
            directNodes = null;
        }

        HomeMode nextMode = currentMode;
        if (nextMode == HomeMode.DIRECT && directNodes == null && relayNodes != null) {
            nextMode = HomeMode.RELAY;
        } else if (nextMode == HomeMode.RELAY && relayNodes == null && directNodes != null) {
            nextMode = HomeMode.DIRECT;
        }

        JSONObject savedConfig = new JSONObject().put("company", company);
        if (hasSubUrls) {
            savedConfig.put("homeTcp", tcpUrl).put("homeUdp", udpUrl);
        }
        if (config.optJSONObject("homeRelay") != null) {
            savedConfig.put("homeRelay", node(config.optJSONObject("homeRelay")));
        }
        if (config.optJSONObject("homeRelayTcp") != null) {
            savedConfig.put("homeRelayTcp", node(config.optJSONObject("homeRelayTcp")));
        }
        if (config.optJSONObject("homeRelayUdp") != null) {
            savedConfig.put("homeRelayUdp", node(config.optJSONObject("homeRelayUdp")));
        }

        synchronized (SsRoutes.class) {
            JSONObject next = new JSONObject()
                    .put("config", savedConfig)
                    .put("companyNode", company)
                    .put("homeMode", nextMode.wire)
                    .put("updated", updatedAt);
            if (directNodes != null) next.put("directNodes", directNodes);
            if (relayNodes != null) next.put("relayNodes", relayNodes);
            JSONArray activeHome = nextMode == HomeMode.RELAY ? relayNodes : directNodes;
            if (activeHome != null && activeHome.length() >= 2) {
                next.put("nodes", new JSONArray()
                        .put(activeHome.getJSONObject(0))
                        .put(activeHome.getJSONObject(1))
                        .put(company));
            }
            commit(next);
            if (enabled) activate(next);
            else notifyChanged();
        }
        probeAsync(true);
        return warning;
    }

    /** Switches home routing between direct subscriptions and fixed company relay; hot-swaps if active. */
    static void setHomeMode(HomeMode mode) throws Exception {
        synchronized (SsRoutes.class) {
            if (mode == HomeMode.RELAY && !hasRelayHomeNodes()) {
                throw new SsException(R.string.ss_err_no_relay);
            }
            if (mode == HomeMode.DIRECT && !hasDirectHomeNodes()) {
                throw new SsException(R.string.ss_err_no_direct);
            }
            if (mode == homeMode()) return;
            JSONObject next = new JSONObject(state.toString());
            next.put("homeMode", mode.wire);
            JSONArray activeHome = mode == HomeMode.RELAY
                    ? next.optJSONArray("relayNodes")
                    : next.optJSONArray("directNodes");
            JSONObject company = next.optJSONObject("companyNode");
            if (activeHome != null && activeHome.length() >= 2 && company != null) {
                next.put("nodes", new JSONArray()
                        .put(activeHome.getJSONObject(0))
                        .put(activeHome.getJSONObject(1))
                        .put(company));
            }
            commit(next);
            if (enabled) activate(next);
            else notifyChanged();
        }
        if (enabled) probeAsync(true);
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

    public static synchronized boolean configured() {
        return state.has("companyNode") && (hasDirectHomeNodes() || hasRelayHomeNodes());
    }

    public static synchronized long updated() { return state.optLong("updated"); }

    public static long receivedBytes() {
        return enabled ? SsNative.ssReceivedBytes() : 0;
    }

    /** Maps exceptions to sanitized, user-facing diagnostics without leaking URLs or secrets. */
    public static String describeError(Context context, Throwable t) {
        if (t instanceof SsException) {
            SsException se = (SsException) t;
            if (se.httpCode > 0) return context.getString(se.messageRes, se.httpCode);
            return context.getString(se.messageRes);
        }
        if (t instanceof UnknownHostException) {
            return context.getString(R.string.ss_err_dns);
        }
        if (t instanceof SocketTimeoutException) {
            return context.getString(R.string.ss_err_timeout);
        }
        if (t instanceof SSLException) {
            return context.getString(R.string.ss_err_tls);
        }
        if (t instanceof JSONException) {
            return context.getString(R.string.ss_err_json_format);
        }
        return context.getString(R.string.ss_error_generic);
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
        // 1160 B payload + 28 B RTP/NV + 58 B SS 2022 S->C + 28 B IP/UDP = 1274 B (< 1280 B MTU floor)
        context.negotiatedPacketSize = Math.min(maxPacketSize, 1160);
    }

    public static boolean routed(InetAddress addr) {
        if (!enabled || !(addr instanceof Inet4Address)) return false;
        byte[] b = addr.getAddress();
        return b[0] == 10 && ((b[1] == 0 && b[2] == 0) || (b[1] == 1 && b[2] == 1));
    }
}
