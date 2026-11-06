package com.laaprak.localhostloader.runtime;

import android.content.Context;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;

/** Deterministic npm installer using package-lock tarball URLs, not shell execution. */
public final class NpmLockInstaller {
    private static final long MAX_TARBALL = 64L * 1024L * 1024L;
    private static final long MAX_EXPANDED_PER_PACKAGE = 256L * 1024L * 1024L;
    private NpmLockInstaller() { }

    public static Result install(Context context, File packageRoot, File nodeModules,
                                 boolean offline, String registryBase) throws Exception {
        File lockFile = new File(packageRoot, "package-lock.json");
        if (!lockFile.isFile()) lockFile = new File(packageRoot, "npm-shrinkwrap.json");
        if (!lockFile.isFile()) {
            if (nodeModules.isDirectory()) return new Result(0, 0, "Using package-provided node_modules.");
            File packageJson = new File(packageRoot, "package.json");
            if (packageJson.isFile()) {
                try (InputStream in = new FileInputStream(packageJson)) {
                    JSONObject metadata = new JSONObject(new String(readAll(in, 4 * 1024 * 1024), StandardCharsets.UTF_8));
                    boolean needsPackages = hasKeys(metadata.optJSONObject("dependencies")) || hasKeys(metadata.optJSONObject("optionalDependencies"));
                    if (!needsPackages) return new Result(0, 0, "No external production dependencies are declared.");
                }
            }
            throw new IllegalStateException("This Node package declares dependencies but has no package-lock.json. Include a lockfile v2/v3 so the loader can install and verify exact package versions.");
        }
        JSONObject lock;
        try (InputStream in = new FileInputStream(lockFile)) {
            byte[] data = readAll(in, 16 * 1024 * 1024);
            lock = new JSONObject(new String(data, StandardCharsets.UTF_8));
        }
        JSONObject packages = lock.optJSONObject("packages");
        if (packages == null) throw new IllegalStateException("Only package-lock version 2 or 3 is supported. Include a modern npm package-lock.json.");

        File cache = new File(context.getFilesDir(), "runtime/node/tarballs");
        if (!cache.exists() && !cache.mkdirs()) throw new IllegalStateException("Cannot create Node package cache.");
        try { PersistentVault.restoreDirectory(context, "runtime/node/tarballs", cache); }
        catch (Exception restoreError) { android.util.Log.w("LocalhostLoader", "Could not restore npm cache", restoreError); }
        if (!nodeModules.exists() && !nodeModules.mkdirs()) throw new IllegalStateException("Cannot create node_modules.");
        int installed = 0, reused = 0;
        Iterator<String> keys = packages.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.startsWith("node_modules/")) continue;
            JSONObject metadata = packages.optJSONObject(key);
            if (metadata == null || metadata.optBoolean("dev", false)) continue;
            String resolved = metadata.optString("resolved", "");
            String name = metadata.optString("name", packageNameFromLockPath(key));
            String version = metadata.optString("version", "0");
            String integrity = metadata.optString("integrity", "");
            if (resolved.isEmpty()) continue;
            if (integrity.isEmpty()) throw new SecurityException("package-lock.json does not provide an integrity hash for " + name + "@" + version + ". Recreate the lockfile with current npm before importing.");
            String relative = key.substring("node_modules/".length());
            if (relative.contains("..") || relative.startsWith("/")) throw new IllegalStateException("Unsafe package path in package-lock.json: " + key);
            File destination = new File(nodeModules, relative).getCanonicalFile();
            String nodeRoot = nodeModules.getCanonicalPath() + File.separator;
            if (!destination.getPath().startsWith(nodeRoot)) throw new IllegalStateException("Package path escapes node_modules.");
            if (new File(destination, "package.json").isFile()) { reused++; continue; }

            String cacheName = safeName(name) + "-" + safeName(version) + ".tgz";
            File tarball = new File(cache, cacheName);
            if (tarball.isFile() && integrity.length() > 0 && !verifyIntegrity(tarball, integrity)) tarball.delete();
            if (!tarball.isFile()) {
                if (offline) throw new IllegalStateException("Offline Node package is missing from the runtime vault: " + name + "@" + version);
                download(context, mirrorUrl(resolved, registryBase), tarball);
                if (integrity.length() > 0 && !verifyIntegrity(tarball, integrity)) {
                    tarball.delete();
                    throw new SecurityException("Integrity check failed for " + name + "@" + version);
                }
                if (PersistentVault.isAttached(context)) {
                    PersistentVault.copyFileIntoVault(context, tarball, "runtime/node/tarballs/" + cacheName, "application/gzip");
                }
            }
            extractPackage(tarball, destination);
            installed++;
        }
        return new Result(installed, reused, "Installed " + installed + " package(s); reused " + reused + " existing package(s).");
    }

    private static boolean hasKeys(JSONObject object) {
        return object != null && object.length() > 0;
    }

    private static String packageNameFromLockPath(String key) {
        String value = key.substring(key.lastIndexOf("node_modules/") + "node_modules/".length());
        if (value.startsWith("@")) {
            int second = value.indexOf('/', 1);
            return second < 0 ? value : value.substring(0, second);
        }
        int slash = value.indexOf('/');
        return slash < 0 ? value : value.substring(0, slash);
    }

    private static String safeName(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }

    private static String mirrorUrl(String resolved, String registryBase) {
        if (registryBase == null || registryBase.trim().isEmpty() || resolved == null) return resolved;
        String base = registryBase.trim();
        if (!base.endsWith("/")) base += "/";
        String official = "https://registry.npmjs.org/";
        return resolved.startsWith(official) ? base + resolved.substring(official.length()) : resolved;
    }

    private static void download(Context context, String address, File output) throws Exception {
        URL url = new URL(address);
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new SecurityException("Node package URLs must use HTTPS.");
        HttpURLConnection connection = openHttps(url, 5);
        connection.setConnectTimeout(20000); connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "LocalhostLoader/1.0");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("npm package download failed: HTTP " + code);
        if (connection.getContentLengthLong() > MAX_TARBALL) throw new IllegalStateException("npm tarball exceeds 64 MB.");
        File parent = output.getParentFile(); if (parent != null) parent.mkdirs();
        try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(output)) {
            byte[] buffer = new byte[32 * 1024]; long total = 0; int n;
            while ((n = in.read(buffer)) != -1) {
                total += n; if (total > MAX_TARBALL) throw new IllegalStateException("npm tarball exceeds 64 MB.");
                out.write(buffer, 0, n);
            }
        } finally { connection.disconnect(); }
    }

    private static HttpURLConnection openHttps(URL start, int maxRedirects) throws Exception {
        URL current = start;
        for (int attempt = 0; attempt <= maxRedirects; attempt++) {
            if (!"https".equalsIgnoreCase(current.getProtocol())) throw new SecurityException("npm redirects must remain HTTPS.");
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(20000); connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "LocalhostLoader/1.0");
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location"); connection.disconnect();
                if (location == null || attempt == maxRedirects) throw new IllegalStateException("Too many or invalid npm redirects.");
                current = new URL(current, location);
                continue;
            }
            return connection;
        }
        throw new IllegalStateException("Too many npm redirects.");
    }

    private static boolean verifyIntegrity(File file, String integrity) throws Exception {
        String[] parts = integrity.split("-", 2);
        if (parts.length != 2) throw new SecurityException("Unsupported npm integrity format.");
        String algorithm = parts[0].toUpperCase(Locale.ROOT).replace("SHA", "SHA-");
        if (!algorithm.matches("SHA-1|SHA-256|SHA-384|SHA-512")) throw new SecurityException("Unsupported npm integrity algorithm: " + parts[0]);
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[32 * 1024]; int n;
            while ((n = in.read(b)) != -1) digest.update(b, 0, n);
        }
        byte[] expected = Base64.getDecoder().decode(parts[1].split(" ")[0]);
        return MessageDigest.isEqual(digest.digest(), expected);
    }

    private static void extractPackage(File tarball, File destination) throws Exception {
        if (!destination.exists() && !destination.mkdirs()) throw new IllegalStateException("Cannot create npm package directory.");
        long expanded = 0;
        try (InputStream raw = new BufferedInputStream(new FileInputStream(tarball));
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(raw);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            byte[] buffer = new byte[32 * 1024];
            while ((entry = tar.getNextTarEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("package/")) name = name.substring("package/".length());
                if (name.isEmpty() || name.equals(".")) continue;
                if (name.startsWith("/") || name.equals("..") || name.contains("../") || entry.isSymbolicLink() || entry.isLink())
                    throw new SecurityException("Unsafe path or link in npm package archive: " + entry.getName());
                File output = new File(destination, name).getCanonicalFile();
                String base = destination.getCanonicalPath() + File.separator;
                if (!output.getPath().startsWith(base)) throw new SecurityException("npm archive tried to leave its package folder.");
                if (entry.isDirectory()) { output.mkdirs(); continue; }
                if (!entry.isFile()) continue;
                File parent = output.getParentFile(); if (parent != null) parent.mkdirs();
                try (OutputStream out = new FileOutputStream(output)) {
                    int count;
                    while ((count = tar.read(buffer)) != -1) {
                        expanded += count;
                        if (expanded > MAX_EXPANDED_PER_PACKAGE) throw new IllegalStateException("npm package expanded beyond 256 MB.");
                        out.write(buffer, 0, count);
                    }
                }
            }
        }
        if (!new File(destination, "package.json").isFile()) throw new IllegalStateException("Downloaded npm package did not contain package.json.");
    }

    private static byte[] readAll(InputStream in, long limit) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n; long total = 0;
        while ((n = in.read(b)) != -1) { total += n; if (total > limit) throw new IllegalStateException("package-lock.json is too large."); out.write(b, 0, n); }
        return out.toByteArray();
    }

    public static final class Result {
        public final int installed;
        public final int reused;
        public final String message;
        Result(int installed, int reused, String message) { this.installed = installed; this.reused = reused; this.message = message; }
    }
}
