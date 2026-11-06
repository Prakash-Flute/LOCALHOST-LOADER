package com.laaprak.localhostloader.runtime;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One loopback gateway, multiple named apps. Backend ports never appear in app chrome. */
public final class LocalGatewayServer {
    private static final String TAG = "LocalhostGateway";
    private final Map<String, Target> targets = new ConcurrentHashMap<>();
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private ServerSocket listener;
    private volatile boolean running;
    private volatile String defaultAppId = "";
    private int port;

    public static final class Target {
        public final String id;
        public final File staticRoot;
        public final int backendPort;
        public Target(String id, File staticRoot, int backendPort) {
            this.id = id; this.staticRoot = staticRoot; this.backendPort = backendPort;
        }
    }

    public synchronized void start() throws Exception {
        if (running) return;
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 64);
        port = listener.getLocalPort();
        running = true;
        Thread accept = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = listener.accept();
                    socket.setSoTimeout(0);
                    clients.execute(() -> handle(socket));
                } catch (Exception e) {
                    if (running) Log.w(TAG, "Gateway accept failed", e);
                }
            }
        }, "LL-Gateway-Accept");
        accept.setDaemon(true); accept.start();
    }

    public int getPort() { return port; }
    public boolean isRunning() { return running && listener != null && !listener.isClosed(); }
    public void registerStatic(String id, File root) throws Exception {
        File canonical = root.getCanonicalFile();
        if (!canonical.isDirectory() || !new File(canonical, "index.html").isFile())
            throw new IllegalArgumentException("The static web root must contain index.html.");
        targets.put(id, new Target(id, canonical, 0));
        defaultAppId = id;
    }
    public void registerBackend(String id, int backendPort) {
        if (backendPort < 1 || backendPort > 65535) throw new IllegalArgumentException("Invalid backend port");
        targets.put(id, new Target(id, null, backendPort));
        defaultAppId = id;
    }
    public void unregister(String id) { targets.remove(id); }
    public Map<String, Target> snapshot() { return new ConcurrentHashMap<>(targets); }

    public synchronized void stop() {
        running = false;
        try { if (listener != null) listener.close(); } catch (Exception ignored) { }
        clients.shutdownNow();
        targets.clear();
    }

    private void handle(Socket client) {
        try (Socket c = client) {
            c.setTcpNoDelay(true);
            BufferedInputStream input = new BufferedInputStream(c.getInputStream());
            byte[] header = readHeader(input, 48 * 1024);
            if (header == null) return;
            String headerText = new String(header, StandardCharsets.ISO_8859_1);
            String[] lines = headerText.split("\r\n");
            if (lines.length == 0) return;
            String[] request = lines[0].split(" ", 3);
            if (request.length < 2) { response(c.getOutputStream(), 400, "Bad request", "text/plain", false); return; }
            String originalTarget = request[1];
            String path = originalTarget.split("\\?", 2)[0];
            String id;
            String appPath;
            String prefix = "/app/";
            if (path.startsWith(prefix)) {
                String[] pathParts = path.split("/", 4);
                if (pathParts.length < 3 || pathParts[2].isEmpty()) {
                    response(c.getOutputStream(), 404, "Local app route not found", "text/plain; charset=utf-8", false); return;
                }
                id = pathParts[2];
                appPath = pathParts.length > 3 ? "/" + pathParts[3] : "/";
            } else {
                // Many web apps reference assets and APIs from the origin root (e.g. /assets/app.js).
                // Resolve those requests against the app currently selected in the integrated browser.
                id = defaultAppId;
                appPath = originalTarget;
            }
            Target target = targets.get(id);
            if (target == null) { response(c.getOutputStream(), 404, "This local app is not registered", "text/plain; charset=utf-8", false); return; }
            if (target.backendPort > 0) {
                proxy(c, input, header, lines, request, target, id);
            } else {
                serveStatic(c, target, request[0], appPath, input);
            }
        } catch (Exception e) {
            Log.d(TAG, "Request closed: " + e.getMessage());
        }
    }

    private void serveStatic(Socket socket, Target target, String method, String requestTarget, InputStream ignored) throws Exception {
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            response(socket.getOutputStream(), 405, "Only GET and HEAD are supported by static packages", "text/plain; charset=utf-8", "HEAD".equals(method));
            return;
        }
        String route = requestTarget.split("\\?", 2)[0].split("#", 2)[0];
        String prefix = "/app/" + target.id;
        String relative = route.startsWith(prefix) ? route.substring(prefix.length()) : route;
        if (relative.isEmpty()) relative = "/";
        File file = resolve(target.staticRoot, relative);
        if (file == null) { response(socket.getOutputStream(), 403, "Forbidden", "text/plain", "HEAD".equals(method)); return; }
        if (file.isDirectory()) file = new File(file, "index.html");
        if (!file.isFile()) {
            String clean = relative;
            if (!clean.contains(".")) file = new File(target.staticRoot, "index.html");
        }
        if (!file.isFile() || !file.getCanonicalPath().startsWith(target.staticRoot.getCanonicalPath() + File.separator)) {
            response(socket.getOutputStream(), 404, "Not found", "text/plain; charset=utf-8", "HEAD".equals(method)); return;
        }
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + mime(file.getName()) +
            "\r\nContent-Length: " + file.length() +
            "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
        if (!"HEAD".equals(method)) {
            try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(file))) {
                byte[] buffer = new byte[32 * 1024]; int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
        }
        out.flush();
    }

    private void proxy(Socket client, InputStream clientInput, byte[] originalHeader, String[] lines,
                       String[] request, Target target, String id) throws Exception {
        String prefix = "/app/" + id;
        String requestedPath = request[1];
        String translated = requestedPath.startsWith(prefix) ? requestedPath.substring(prefix.length()) : requestedPath;
        if (translated.isEmpty()) translated = "/";
        if (translated.startsWith("?")) translated = "/" + translated;
        StringBuilder replacement = new StringBuilder();
        replacement.append(request[0]).append(' ').append(translated).append(' ')
            .append(request.length > 2 ? request[2] : "HTTP/1.1").append("\r\n");
        boolean upgrade = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            String key = colon > 0 ? line.substring(0, colon).trim() : "";
            if ("host".equalsIgnoreCase(key) || "proxy-connection".equalsIgnoreCase(key)) continue;
            if ("upgrade".equalsIgnoreCase(key)) upgrade = true;
            replacement.append(line).append("\r\n");
        }
        final boolean upgradeConnection = upgrade;
        replacement.append("Host: 127.0.0.1:").append(target.backendPort).append("\r\n");
        if (!upgradeConnection) replacement.append("Connection: close\r\n");
        replacement.append("\r\n");

        try (Socket upstream = new Socket()) {
            upstream.connect(new InetSocketAddress("127.0.0.1", target.backendPort), 3500);
            upstream.setTcpNoDelay(true);
            OutputStream upstreamOut = new BufferedOutputStream(upstream.getOutputStream());
            InputStream upstreamIn = new BufferedInputStream(upstream.getInputStream());
            upstreamOut.write(replacement.toString().getBytes(StandardCharsets.ISO_8859_1));
            upstreamOut.flush();

            Thread upload = new Thread(() -> {
                try {
                    byte[] buffer = new byte[16 * 1024]; int count;
                    while ((count = clientInput.read(buffer)) != -1) { upstreamOut.write(buffer, 0, count); upstreamOut.flush(); }
                    if (!upgradeConnection) upstream.shutdownOutput();
                } catch (Exception ignored) { }
            }, "LL-Gateway-Upload");
            upload.setDaemon(true); upload.start();

            OutputStream clientOut = new BufferedOutputStream(client.getOutputStream());
            byte[] buffer = new byte[32 * 1024]; int count;
            while ((count = upstreamIn.read(buffer)) != -1) { clientOut.write(buffer, 0, count); clientOut.flush(); }
            try { client.shutdownOutput(); } catch (Exception ignored) { }
            try { upload.join(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    private static byte[] readHeader(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int state = 0;
        while (out.size() < limit) {
            int value = input.read();
            if (value < 0) return out.size() == 0 ? null : out.toByteArray();
            out.write(value);
            if ((state == 0 || state == 2) && value == '\r') state++;
            else if ((state == 1 || state == 3) && value == '\n') {
                if (state == 3) return out.toByteArray();
                state++;
            } else state = value == '\r' ? 1 : 0;
        }
        throw new IllegalArgumentException("HTTP headers are too large");
    }

    private static File resolve(File root, String relative) throws Exception {
        String decoded = URLDecoder.decode(relative, "UTF-8");
        if (decoded.indexOf('\\') >= 0 || decoded.indexOf('\0') >= 0) return null;
        while (decoded.startsWith("/")) decoded = decoded.substring(1);
        File candidate = new File(root, decoded).getCanonicalFile();
        String base = root.getCanonicalPath();
        if (!candidate.getPath().equals(base) && !candidate.getPath().startsWith(base + File.separator)) return null;
        return candidate;
    }

    private static void response(OutputStream raw, int status, String body, String type, boolean head) throws Exception {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = new BufferedOutputStream(raw);
        out.write(("HTTP/1.1 " + status + " " + reason(status) + "\r\nContent-Type: " + type +
            "\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        if (!head) out.write(data);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) { case 400: return "Bad Request"; case 403: return "Forbidden"; case 404: return "Not Found"; case 405: return "Method Not Allowed"; default: return "Error"; }
    }

    private static String mime(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=utf-8";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".ico")) return "image/x-icon";
        if (n.endsWith(".wasm")) return "application/wasm";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".woff")) return "font/woff";
        if (n.endsWith(".ttf")) return "font/ttf";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }
}
