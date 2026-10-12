package com.micklab.llama;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Probes a set of host candidates to determine which can reach the local API server.
 * All results are delivered on the main thread via {@link Callback#onComplete}.
 */
public class ApiConnectivityProber {

    private static final String TAG = "ApiConnectivityProber";
    private static final int TIMEOUT_MS = 2500;
    private static final String PROBE_PATH = "/api/tags";

    /** A labeled IP address to probe (caller constructs these for extra candidates). */
    public static class Candidate {
        public final String label;
        public final String ip;   // raw host string, or "—" if unavailable
        public Candidate(String label, String ip) {
            this.label = label;
            this.ip = ip;
        }
    }

    /** Result for a single host candidate. Fields written on a worker thread before callback. */
    public static class ProbeResult {
        public final String label;
        public final String host;   // "—" when address was unavailable
        public final String url;    // "http://host:port" or "" when host is "—"
        public final int port;
        public boolean reachable;
        public long latencyMs = -1;
        public String errorMsg;     // non-null when !reachable

        ProbeResult(String label, String host, int port) {
            this.label = label;
            this.host = host;
            this.port = port;
            this.url = host.equals("—") ? "" : "http://" + host + ":" + port;
        }
    }

    public interface Callback {
        /** Called on the main thread when all probes finish. */
        void onComplete(List<ProbeResult> results, String bestUrl);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ApiProber");
        t.setDaemon(true);
        return t;
    });

    private volatile Future<?> pendingProbe = null;

    /**
     * Probe all candidates in parallel.
     *
     * @param port        API server port
     * @param lanIp       Wi-Fi IPv4 (null/empty = not connected)
     * @param carrierIp   Cellular IPv4 (null/empty = not connected)
     * @param extras      Additional labeled candidates (Ethernet, hotspot, tether, etc.)
     * @param callback    Delivers results on the main thread
     */
    public void probe(int port, String lanIp, String carrierIp,
                      List<Candidate> extras, Callback callback) {
        Future<?> prev = pendingProbe;
        if (prev != null) prev.cancel(true);

        List<ProbeResult> candidates = buildCandidates(port, lanIp, carrierIp, extras);

        pendingProbe = executor.submit(() -> {
            List<Future<?>> workers = new ArrayList<>();
            for (ProbeResult r : candidates) {
                workers.add(executor.submit(() -> probeOne(r)));
            }
            for (Future<?> f : workers) {
                try { f.get(); } catch (Exception ignored) {}
            }
            if (!Thread.currentThread().isInterrupted()) {
                String best = chooseBestUrl(candidates, port);
                mainHandler.post(() -> callback.onComplete(new ArrayList<>(candidates), best));
            }
        });
    }

    // ── candidate list ───────────────────────────────────────────────────────

    private List<ProbeResult> buildCandidates(int port, String lanIp, String carrierIp,
                                               List<Candidate> extras) {
        List<ProbeResult> list = new ArrayList<>();

        list.add(new ProbeResult("127.0.0.1", "127.0.0.1", port));
        list.add(new ProbeResult("0.0.0.0",   "0.0.0.0",   port));
        list.add(new ProbeResult("localhost",  "localhost",  port));

        addIfPresent(list, "LAN", lanIp, port);
        addIfPresent(list, "Carrier", carrierIp, port);

        if (extras != null) {
            for (Candidate c : extras) {
                addIfPresent(list, c.label, c.ip, port);
            }
        }

        list.add(new ProbeResult("android.local", "android.local", port));
        return list;
    }

    private static void addIfPresent(List<ProbeResult> list, String label, String ip, int port) {
        if (ip != null && !ip.isEmpty()) {
            list.add(new ProbeResult(label + " (" + ip + ")", ip, port));
        } else {
            ProbeResult r = new ProbeResult(label, "—", port);
            r.reachable = false;
            r.errorMsg  = "not connected";
            list.add(r);
        }
    }

    // ── probing ──────────────────────────────────────────────────────────────

    private void probeOne(ProbeResult r) {
        if (r.host.equals("—")) return;
        long start = System.currentTimeMillis();
        try {
            // Raw socket bypasses Android NSC cleartext restrictions for non-whitelisted hosts
            // (NSC only intercepts Java HTTP(S) client APIs, not raw TCP sockets).
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(r.host, r.port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            String req = "GET " + PROBE_PATH + " HTTP/1.0\r\nHost: " + r.host + "\r\nConnection: close\r\n\r\n";
            out.write(req.getBytes("US-ASCII"));
            out.flush();
            InputStream in = socket.getInputStream();
            StringBuilder sb = new StringBuilder();
            int ch;
            while ((ch = in.read()) != -1 && sb.length() < 64) {
                if (ch == '\n') break;
                sb.append((char) ch);
            }
            socket.close();
            String status = sb.toString().trim();
            r.latencyMs = System.currentTimeMillis() - start;
            r.reachable = status.startsWith("HTTP/") && status.contains(" 200");
            if (!r.reachable) {
                r.errorMsg = status.isEmpty() ? "no response" : (status.length() > 20 ? status.substring(0, 20) : status);
            }
        } catch (Exception e) {
            r.latencyMs = System.currentTimeMillis() - start;
            r.reachable = false;
            r.errorMsg  = shortError(e);
            Log.d(TAG, "probe failed: " + r.host + " — " + e.getMessage());
        }
    }

    // ── best URL selection ───────────────────────────────────────────────────

    /**
     * Priority: 127.0.0.1 > localhost > LAN > extra IPs (in order) > Carrier >
     *           android.local > 0.0.0.0
     * 0.0.0.0 is last because it's a listen-all bind address, not a routable destination.
     */
    String chooseBestUrl(List<ProbeResult> results, int port) {
        // Fixed high-priority prefixes
        String[] highPriority = {"127.0.0.1", "localhost", "LAN"};
        for (String prefix : highPriority) {
            for (ProbeResult r : results) {
                if (r.reachable && r.label.startsWith(prefix)) return r.url;
            }
        }
        // Extra candidates in declaration order (Ethernet, hotspot, tether, etc.)
        // — skip the fixed ones already checked and the fixed low-priority ones below
        String[] lowPriority = {"127.0.0.1", "0.0.0.0", "localhost", "LAN", "Carrier", "android.local"};
        outer:
        for (ProbeResult r : results) {
            if (!r.reachable) continue;
            for (String fixed : lowPriority) {
                if (r.label.startsWith(fixed)) continue outer;
            }
            return r.url;  // first reachable extra
        }
        // Low-priority fixed
        String[] lowOrder = {"Carrier", "android.local", "0.0.0.0"};
        for (String prefix : lowOrder) {
            for (ProbeResult r : results) {
                if (r.reachable && r.label.startsWith(prefix)) return r.url;
            }
        }
        return "http://127.0.0.1:" + port;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String shortError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return e.getClass().getSimpleName();
        if (msg.contains("refused")) return "refused";
        if (msg.contains("timeout") || msg.contains("timed out")) return "timeout";
        if (msg.contains("resolve") || msg.contains("hostname") || msg.contains("NXDOMAIN"))
            return "no DNS";
        if (msg.length() > 28) return msg.substring(0, 28);
        return msg;
    }
}
