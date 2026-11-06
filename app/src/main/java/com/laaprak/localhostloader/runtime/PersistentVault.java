package com.laaprak.localhostloader.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** User-selected shared folder. Files here remain after the APK is uninstalled. */
public final class PersistentVault {
    public static final String PREFS = "localhost_loader_settings";
    public static final String KEY_TREE = "vault_tree_uri";
    private PersistentVault() { }

    public static Uri getTreeUri(Context context) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, "");
        if (raw == null || raw.isEmpty()) return null;
        try { return Uri.parse(raw); } catch (Exception e) { return null; }
    }

    public static boolean isAttached(Context context) {
        Uri uri = getTreeUri(context);
        if (uri == null) return false;
        DocumentFile root = DocumentFile.fromTreeUri(context, uri);
        return root != null && root.exists() && root.isDirectory() && root.canWrite();
    }

    public static void attach(Context context, Uri treeUri) throws IOException {
        if (treeUri == null) throw new IOException("No storage folder was selected.");
        try {
            context.getContentResolver().takePersistableUriPermission(treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION |
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            throw new IOException("Android did not grant persistent access to this folder.", e);
        }
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null || !root.isDirectory() || !root.canWrite()) {
            throw new IOException("The selected folder is not writable.");
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TREE, treeUri.toString()).apply();
        for (String path : new String[]{"packages", "runtime", "runtime/python/3.13/wheelhouse", "runtime/node/tarballs", "runtime-packs", "exports", "logs"}) {
            ensureDirectory(context, path);
        }
        DocumentFile marker = createFile(context, "LOCALHOST_LOADER_VAULT.json", "application/json");
        try (OutputStream out = context.getContentResolver().openOutputStream(marker.getUri(), "wt")) {
            if (out != null) out.write(("{\"format\":1,\"product\":\"Localhost Loader\",\"purpose\":\"persistent runtime and package vault\"}").getBytes("UTF-8"));
        }
    }

    public static DocumentFile root(Context context) {
        Uri uri = getTreeUri(context);
        return uri == null ? null : DocumentFile.fromTreeUri(context, uri);
    }

    public static DocumentFile ensureDirectory(Context context, String relativePath) throws IOException {
        DocumentFile current = root(context);
        if (current == null) throw new IOException("Choose a permanent vault folder first.");
        String[] parts = relativePath.replace('\\', '/').split("/");
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) throw new IOException("Unsafe vault path.");
            current = child(context, current, part, true, null);
            if (current == null || !current.isDirectory()) throw new IOException("Could not create vault folder: " + part);
        }
        return current;
    }

    public static DocumentFile createFile(Context context, String relativePath, String mimeType) throws IOException {
        String normalized = relativePath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String parent = slash < 0 ? "" : normalized.substring(0, slash);
        String name = slash < 0 ? normalized : normalized.substring(slash + 1);
        if (name.isEmpty() || name.contains("..")) throw new IOException("Invalid file name.");
        DocumentFile directory = parent.isEmpty() ? root(context) : ensureDirectory(context, parent);
        if (directory == null) throw new IOException("Vault is not attached.");
        DocumentFile existing = directory.findFile(name);
        if (existing != null) existing.delete();
        DocumentFile created = directory.createFile(mimeType == null ? "application/octet-stream" : mimeType, name);
        if (created == null) throw new IOException("Could not create vault file: " + name);
        return created;
    }

    public static void copyFileIntoVault(Context context, File source, String relativePath, String mimeType) throws IOException {
        DocumentFile target = createFile(context, relativePath, mimeType);
        try (InputStream in = new FileInputStream(source);
             OutputStream out = context.getContentResolver().openOutputStream(target.getUri(), "w")) {
            if (out == null) throw new IOException("Could not open vault file for writing.");
            copy(in, out);
        }
    }

    public static void copyVaultFileTo(Context context, String relativePath, File target) throws IOException {
        DocumentFile document = find(context, relativePath);
        if (document == null || !document.isFile()) throw new IOException("File is not present in the runtime vault: " + relativePath);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create local runtime folder.");
        try (InputStream in = context.getContentResolver().openInputStream(document.getUri());
             OutputStream out = new java.io.FileOutputStream(target)) {
            if (in == null) throw new IOException("Could not read file from the runtime vault.");
            copy(in, out);
        }
    }

    public static DocumentFile find(Context context, String relativePath) {
        DocumentFile current = root(context);
        if (current == null) return null;
        for (String part : relativePath.replace('\\', '/').split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) return null;
            current = current.findFile(part);
            if (current == null) return null;
        }
        return current;
    }

    public static void mirrorDirectory(Context context, File source, String destination) throws IOException {
        if (source == null || !source.exists()) return;
        if (source.isDirectory()) {
            ensureDirectory(context, destination);
            File[] children = source.listFiles();
            if (children != null) for (File child : children) {
                mirrorDirectory(context, child, destination + "/" + child.getName());
            }
        } else {
            String name = source.getName().toLowerCase(java.util.Locale.ROOT);
            String mime = name.endsWith(".whl") || name.endsWith(".zip") ? "application/zip" :
                name.endsWith(".tgz") ? "application/gzip" : "application/octet-stream";
            copyFileIntoVault(context, source, destination, mime);
        }
    }

    public static void restoreDirectory(Context context, String sourceRelative, File destination) throws IOException {
        DocumentFile source = find(context, sourceRelative);
        if (source == null || !source.isDirectory()) return;
        if (!destination.exists() && !destination.mkdirs()) throw new IOException("Cannot create local restore directory.");
        restoreChildren(context, source, destination, 0);
    }

    private static void restoreChildren(Context context, DocumentFile source, File destination, int depth) throws IOException {
        if (depth > 32) throw new IOException("Vault folder nesting is too deep.");
        DocumentFile[] children = source.listFiles();
        for (DocumentFile child : children) {
            String childName = child.getName() == null ? "item" : child.getName();
            if (childName.isEmpty() || childName.equals(".") || childName.equals("..") || childName.contains("/") || childName.contains("\\")) continue;
            File target = new File(destination, childName).getCanonicalFile();
            String destinationRoot = destination.getCanonicalPath() + File.separator;
            if (!target.getPath().startsWith(destinationRoot)) continue;
            if (child.isDirectory()) {
                if (!target.exists() && !target.mkdirs()) throw new IOException("Cannot restore directory.");
                restoreChildren(context, child, target, depth + 1);
            } else {
                try (InputStream in = context.getContentResolver().openInputStream(child.getUri());
                     OutputStream out = new java.io.FileOutputStream(target)) {
                    if (in == null) continue;
                    copy(in, out);
                }
            }
        }
    }

    public static List<String> listFiles(Context context, String relativeDirectory) {
        ArrayList<String> result = new ArrayList<>();
        DocumentFile directory = relativeDirectory == null || relativeDirectory.isEmpty()
            ? root(context) : find(context, relativeDirectory);
        if (directory == null || !directory.isDirectory()) return result;
        for (DocumentFile child : directory.listFiles()) if (child.isFile() && child.getName() != null) result.add(child.getName());
        return result;
    }


    public static List<String> listDirectories(Context context, String relativeDirectory) {
        ArrayList<String> result = new ArrayList<>();
        DocumentFile directory = relativeDirectory == null || relativeDirectory.isEmpty()
            ? root(context) : find(context, relativeDirectory);
        if (directory == null || !directory.isDirectory()) return result;
        for (DocumentFile child : directory.listFiles()) if (child.isDirectory() && child.getName() != null) result.add(child.getName());
        return result;
    }

    public static void copyVaultFileTo(Context context, DocumentFile document, File target) throws IOException {
        if (document == null || !document.isFile()) throw new IOException("Vault package file is missing.");
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create local package folder.");
        try (InputStream in = context.getContentResolver().openInputStream(document.getUri());
             OutputStream out = new java.io.FileOutputStream(target)) {
            if (in == null) throw new IOException("Cannot read package from the permanent vault.");
            copy(in, out);
        }
    }

    public static void delete(Context context, String relativePath) {
        DocumentFile file = find(context, relativePath);
        if (file != null) file.delete();
    }

    private static DocumentFile child(Context context, DocumentFile parent, String name,
                                      boolean directory, String mimeType) throws IOException {
        DocumentFile existing = parent.findFile(name);
        if (existing != null) return existing;
        DocumentFile created = directory ? parent.createDirectory(name) : parent.createFile(mimeType, name);
        if (created == null) throw new IOException("Could not create " + name);
        return created;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024]; int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        out.flush();
    }
}
