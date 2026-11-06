package com.laaprak.localhostloader.runtime;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs checksummed .llpack archives into pre-defined cache areas. */
public final class RuntimePackInstaller {
    private static final long MAX_EXPANDED = 750L * 1024L * 1024L;
    private RuntimePackInstaller() { }

    public static Result install(Context context, File archive) throws Exception {
        File temp = new File(context.getCacheDir(), "llpack-" + System.nanoTime());
        if (!temp.mkdirs()) throw new IllegalStateException("Could not create pack staging directory");
        try {
            long total = 0;
            int count = 0;
            try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
                ZipEntry entry;
                byte[] buffer = new byte[32 * 1024];
                while ((entry = zin.getNextEntry()) != null) {
                    if (++count > 18000) throw new IllegalStateException("Pack has too many files");
                    String name = entry.getName().replace('\\', '/');
                    if (name.startsWith("/") || name.equals("..") || name.contains("../") || name.matches("^[A-Za-z]:.*"))
                        throw new SecurityException("Unsafe path in runtime pack: " + name);
                    File target = new File(temp, name).getCanonicalFile();
                    if (!target.getPath().startsWith(temp.getCanonicalPath() + File.separator)) throw new SecurityException("Pack path escapes staging folder");
                    if (entry.isDirectory()) { target.mkdirs(); continue; }
                    File parent = target.getParentFile(); if (parent != null) parent.mkdirs();
                    try (OutputStream out = new FileOutputStream(target)) {
                        int n; while ((n = zin.read(buffer)) != -1) { total += n; if (total > MAX_EXPANDED) throw new IllegalStateException("Runtime pack exceeds 750 MB expanded"); out.write(buffer, 0, n); }
                    }
                }
            }
            File manifestFile = new File(temp, "pack.json");
            if (!manifestFile.isFile()) throw new IllegalArgumentException("Runtime pack must contain pack.json");
            JSONObject manifest = new JSONObject(new String(readAll(manifestFile, 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8));
            if (manifest.optInt("schema", 0) != 1) throw new IllegalArgumentException("Unsupported runtime pack schema");
            String kind = manifest.optString("kind", "");
            String version = manifest.optString("version", "");
            if (version.isEmpty()) throw new IllegalArgumentException("Pack version is required");
            String destination;
            if ("python-wheels".equals(kind)) destination = "python/3.13/wheelhouse";
            else if ("node-tarballs".equals(kind)) destination = "node/tarballs";
            else if ("runtime-assets".equals(kind)) destination = "packs/" + safe(manifest.optString("id", version)) + "/" + safe(version);
            else throw new IllegalArgumentException("Supported pack kinds: python-wheels, node-tarballs, runtime-assets");

            JSONArray files = manifest.optJSONArray("files");
            if (files == null || files.length() == 0) throw new IllegalArgumentException("pack.json must list files with SHA-256 checksums");
            File runtimeRoot = new File(context.getFilesDir(), "runtime");
            int installed = 0;
            for (int i = 0; i < files.length(); i++) {
                JSONObject item = files.getJSONObject(i);
                String relative = item.optString("path", "").replace('\\', '/');
                String digest = item.optString("sha256", "").toLowerCase(Locale.ROOT);
                if (relative.isEmpty() || relative.startsWith("/") || relative.contains("../") || !digest.matches("[a-f0-9]{64}"))
                    throw new SecurityException("Each pack file needs a safe path and SHA-256 digest");
                File src = new File(temp, "payload/" + relative).getCanonicalFile();
                if (!src.isFile() || !src.getPath().startsWith(new File(temp, "payload").getCanonicalPath() + File.separator))
                    throw new IllegalArgumentException("Pack payload is missing: " + relative);
                if (!digest.equals(sha256(src))) throw new SecurityException("SHA-256 mismatch for " + relative);
                File out = new File(runtimeRoot, destination + "/" + relative).getCanonicalFile();
                if (!out.getPath().startsWith(runtimeRoot.getCanonicalPath() + File.separator)) throw new SecurityException("Pack target path is unsafe");
                File outParent = out.getParentFile(); if (outParent != null) outParent.mkdirs();
                copy(src, out);
                installed++;
            }
            if (PersistentVault.isAttached(context)) {
                PersistentVault.mirrorDirectory(context, new File(runtimeRoot, destination), "runtime/" + destination);
                String packFileName = safe(manifest.optString("id", kind)) + "-" + safe(version) + ".llpack";
                PersistentVault.copyFileIntoVault(context, archive, "runtime-packs/" + packFileName, "application/zip");
            }
            String packId = manifest.optString("id", kind);
            android.content.SharedPreferences prefs = context.getSharedPreferences("localhost_loader_settings", Context.MODE_PRIVATE);
            org.json.JSONObject versions;
            try { versions = new org.json.JSONObject(prefs.getString("installed_pack_versions", "{}")); } catch (Exception e) { versions = new org.json.JSONObject(); }
            versions.put(packId, version);
            prefs.edit().putString("installed_pack_versions", versions.toString()).apply();
            return new Result(kind, version, installed);
        } finally { deleteTree(temp); }
    }

    private static byte[] readAll(File file, long max) throws Exception {
        if (file.length() > max) throw new IllegalArgumentException("Pack manifest too large");
        try (InputStream in = new FileInputStream(file); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toByteArray();
        }
    }
    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) { byte[] b = new byte[32768]; int n; while ((n = in.read(b)) != -1) digest.update(b, 0, n); }
        StringBuilder s = new StringBuilder(); for (byte b : digest.digest()) s.append(String.format(Locale.ROOT, "%02x", b)); return s.toString();
    }
    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static void copy(File source, File target) throws Exception {
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) { byte[] b = new byte[32768]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); }
    }
    private static void deleteTree(File f) { if (f == null || !f.exists()) return; if (f.isDirectory()) { File[] c = f.listFiles(); if (c != null) for (File x : c) deleteTree(x); } f.delete(); }

    public static final class Result {
        public final String kind, version; public final int installed;
        Result(String kind, String version, int installed) { this.kind = kind; this.version = version; this.installed = installed; }
    }
}
