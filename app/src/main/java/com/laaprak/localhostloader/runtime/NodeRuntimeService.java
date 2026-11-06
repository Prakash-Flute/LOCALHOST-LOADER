package com.laaprak.localhostloader.runtime;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;

import com.laaprak.localhostloader.MainActivity;

import java.io.File;

public class NodeRuntimeService extends Service {
    public static final String ACTION_START = "com.laaprak.localhostloader.NODE_START";
    public static final String ACTION_STOP = "com.laaprak.localhostloader.NODE_STOP";
    public static final String EXTRA_ROOT = "root";
    public static final String EXTRA_ENTRY = "entry";
    public static final String EXTRA_APP_ID = "app_id";
    public static final String EXTRA_APP_NAME = "app_name";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_ENV = "environment_json";

    private volatile String currentAppId = "";

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            RuntimeState.broadcast(this, currentAppId, "STOPPING", "Stopping Node.js runtime");
            stopForeground(true);
            stopSelf();
            Process.killProcess(Process.myPid());
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;

        String root = intent.getStringExtra(EXTRA_ROOT);
        String entry = intent.getStringExtra(EXTRA_ENTRY);
        currentAppId = intent.getStringExtra(EXTRA_APP_ID);
        String name = intent.getStringExtra(EXTRA_APP_NAME);
        int port = intent.getIntExtra(EXTRA_PORT, 0);
        String host = intent.getStringExtra(EXTRA_HOST);
        String envJson = intent.getStringExtra(EXTRA_ENV);

        int notificationId = 4102 + Math.abs((currentAppId == null ? 0 : currentAppId.hashCode()) % 3000);
        RuntimeNotifications.startForeground(this, notificationId, RuntimeNotifications.build(this,
            "Local app running", name == null ? "Node.js server active" : name + " · Node.js", notificationId));
        if (!NodeBridge.isAvailable()) {
            RuntimeState.broadcast(this, currentAppId, "FAILED", "Node engine is not present in this APK. Build with the Node mobile runtime bundle.");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (root == null || entry == null || port <= 0) {
            RuntimeState.broadcast(this, currentAppId, "FAILED", "Missing Node.js startup configuration");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        RuntimeState.broadcast(this, currentAppId, "INSTALLING", "Preparing Node.js package");
        Thread thread = new Thread(() -> runNode(root, entry, currentAppId, name, port, host, envJson), "LL-Node-Runtime");
        thread.setDaemon(true);
        thread.start();
        return START_NOT_STICKY;
    }

    private void runNode(String root, String entry, String appId, String name,
                         int port, String host, String envJson) {
        try {
            File rootDir = new File(root).getCanonicalFile();
            File entryFile = new File(rootDir, entry).getCanonicalFile();
            if (!entryFile.isFile() || !entryFile.getPath().startsWith(rootDir.getPath() + File.separator)) {
                throw new IllegalArgumentException("Node entry file is missing or outside the package");
            }
            File home = new File(getFilesDir(), "runtime/node/home");
            File logDir = new File(getFilesDir(), "logs");
            home.mkdirs(); logDir.mkdirs();
            String log = new File(logDir, (appId == null ? "node" : appId.replaceAll("[^A-Za-z0-9._-]", "_")) + ".log").getAbsolutePath();
            String env = envJson == null ? "{}" : envJson;
            try { PersistentVault.restoreDirectory(this, "runtime/node/tarballs", new File(getFilesDir(), "runtime/node/tarballs")); }
            catch (Exception restoreError) { android.util.Log.w("LocalhostLoader", "Could not restore npm tarball cache", restoreError); }
            java.util.ArrayList<String> environment = new java.util.ArrayList<>();
            environment.add("PORT=" + port);
            environment.add("LOCALHOST_PORT=" + port);
            environment.add("HOST=" + (host == null ? "127.0.0.1" : host));
            environment.add("LOCALHOST_HOST=" + (host == null ? "127.0.0.1" : host));
            environment.add("LOCALHOST_APP_ROOT=" + rootDir.getAbsolutePath());
            environment.add("LOCALHOST_APP_NAME=" + (name == null ? "Local App" : name));
            environment.add("HOME=" + home.getAbsolutePath());
            environment.add("NODE_PATH=" + new File(rootDir, "node_modules").getAbsolutePath());
            environment.add("LOCALHOST_APP_LOG=" + log);
            environment.add("LOCALHOST_ENV_JSON=" + env);
            environment.addAll(new JSONObjectExtra(env).environment());
            RuntimeState.broadcast(this, appId, "RUNNING", "Node.js server process started");
            JSONObjectExtra extra = new JSONObjectExtra(env);
            java.util.ArrayList<String> argv = new java.util.ArrayList<>();
            argv.add("node"); argv.add(entryFile.getAbsolutePath());
            argv.addAll(extra.arguments());
            int exit = NodeBridge.startNode(argv.toArray(new String[0]), rootDir.getAbsolutePath(), environment.toArray(new String[0]));
            RuntimeState.broadcast(this, appId, "STOPPED", "Node.js process exited with code " + exit);
        } catch (Throwable t) {
            RuntimeState.broadcast(this, appId, "FAILED", t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            android.util.Log.e("LocalhostLoader", "Node.js server failed", t);
        } finally {
            // Node Mobile documents one engine instance per process. Kill this dedicated
            // slot process after the engine returns so its next use starts fresh.
            new android.os.Handler(getMainLooper()).post(() -> {
                stopForeground(true);
                stopSelf();
                Process.killProcess(Process.myPid());
            });
        }
    }

    private static final class JSONObjectExtra {
        private final String raw;
        JSONObjectExtra(String raw) { this.raw = raw; }
        java.util.List<String> arguments() {
            java.util.ArrayList<String> result = new java.util.ArrayList<>();
            try {
                org.json.JSONArray args = new org.json.JSONObject(raw == null ? "{}" : raw).optJSONArray("args");
                if (args != null) for (int i = 0; i < Math.min(args.length(), 32); i++) {
                    String value = args.optString(i, "");
                    if (!value.contains("\u0000") && value.length() <= 2048) result.add(value);
                }
            } catch (Exception ignored) { }
            return result;
        }
        java.util.List<String> environment() {
            java.util.ArrayList<String> result = new java.util.ArrayList<>();
            try {
                org.json.JSONObject json = new org.json.JSONObject(raw == null ? "{}" : raw);
                org.json.JSONObject env = json.optJSONObject("environment");
                if (env != null) {
                    java.util.Iterator<String> keys = env.keys();
                    while (keys.hasNext()) {
                        String key = keys.next(); Object value = env.opt(key);
                        if (key.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") && value != null && (value instanceof String || value instanceof Number || value instanceof Boolean)) {
                            String text = String.valueOf(value);
                            if (text.length() <= 4096 && !text.contains("\u0000") && !key.equals("PORT") && !key.equals("HOST") && !key.equals("HOME")) result.add(key + "=" + text);
                        }
                    }
                }
            } catch (Exception ignored) { }
            return result;
        }
    }

    @Override public void onDestroy() { super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
