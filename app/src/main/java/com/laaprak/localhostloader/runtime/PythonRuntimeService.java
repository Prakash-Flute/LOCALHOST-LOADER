package com.laaprak.localhostloader.runtime;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;

import com.chaquo.python.AndroidPlatform;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.Kwarg;
import com.laaprak.localhostloader.MainActivity;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PythonRuntimeService extends Service {
    public static final String ACTION_START = "com.laaprak.localhostloader.PYTHON_START";
    public static final String ACTION_STOP = "com.laaprak.localhostloader.PYTHON_STOP";
    public static final String EXTRA_ROOT = "root";
    public static final String EXTRA_ENTRY = "entry";
    public static final String EXTRA_APP_ID = "app_id";
    public static final String EXTRA_APP_NAME = "app_name";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_ENV = "environment_json";

    private ExecutorService worker;
    private volatile String currentAppId = "";

    @Override public void onCreate() {
        super.onCreate();
        worker = Executors.newSingleThreadExecutor();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            RuntimeState.broadcast(this, currentAppId, "STOPPING", "Stopping Python runtime");
            stopForeground(true);
            stopSelf();
            // The embedded interpreter is executing a blocking server entry in this process.
            // Isolate this runtime in its own Android process so stopping it cannot affect the UI.
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
        if (root == null || entry == null || port <= 0) {
            RuntimeState.broadcast(this, currentAppId, "FAILED", "Missing Python startup configuration");
            stopSelf();
            return START_NOT_STICKY;
        }

        int notificationId = 7101 + Math.abs((currentAppId == null ? 0 : currentAppId.hashCode()) % 3000);
        RuntimeNotifications.startForeground(this, notificationId, RuntimeNotifications.build(this,
            "Local app running", name == null ? "Python server active" : name + " · Python", notificationId));
        RuntimeState.broadcast(this, currentAppId, "STARTING", "Preparing Python environment");
        worker.execute(() -> runPython(root, entry, currentAppId, name, port, host, envJson));
        return START_NOT_STICKY;
    }

    private void runPython(String root, String entry, String appId, String name,
                           int port, String host, String envJson) {
        File rootDir = new File(root);
        File runtime = new File(getFilesDir(), "runtime/python/3.13");
        File wheelhouse = new File(runtime, "wheelhouse");
        try { PersistentVault.restoreDirectory(this, "runtime/python/3.13/wheelhouse", wheelhouse); }
        catch (Exception restoreError) { android.util.Log.w("LocalhostLoader", "Could not restore Python wheel cache", restoreError); }
        File sitePackages = new File(runtime, "apps/" + safeId(appId) + "/site-packages");
        File logs = new File(getFilesDir(), "logs");
        File log = new File(logs, safeId(appId) + ".log");
        File requirements = new File(rootDir, "requirements.txt");
        if (!logs.exists()) logs.mkdirs();
        if (!wheelhouse.exists()) wheelhouse.mkdirs();
        if (!sitePackages.exists()) sitePackages.mkdirs();

        try {
            RuntimeState.broadcast(this, appId, "INSTALLING", "Checking Python dependencies");
            if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
            Python py = Python.getInstance();
            PyObject helper = py.getModule("ll_runtime");
            String index = getSharedPreferences("localhost_loader_settings", MODE_PRIVATE)
                .getString("python_index", "https://pypi.org/simple");
            boolean offline = getSharedPreferences("localhost_loader_settings", MODE_PRIVATE)
                .getBoolean("offline_only", false);
            int installResult = helper.callAttr("install_requirements",
                requirements.getAbsolutePath(), sitePackages.getAbsolutePath(), wheelhouse.getAbsolutePath(),
                index, offline, log.getAbsolutePath()).toInt();
            if (installResult != 0) {
                RuntimeState.broadcast(this, appId, "FAILED", "Python dependency installation failed. Open Logs for details.");
                return;
            }
            if (PersistentVault.isAttached(this)) {
                try { PersistentVault.mirrorDirectory(this, wheelhouse, "runtime/python/3.13/wheelhouse"); }
                catch (Exception vaultError) { android.util.Log.w("LocalhostLoader", "Could not persist Python wheel cache", vaultError); }
            }
            if (!sitePackages.isDirectory()) sitePackages.mkdirs();
            RuntimeState.broadcast(this, appId, "RUNNING", "Python server process started");
            helper.callAttr("run_entry", rootDir.getAbsolutePath(), entry,
                sitePackages.getAbsolutePath(), port, host == null ? "127.0.0.1" : host,
                name == null ? "Local App" : name, log.getAbsolutePath(),
                envJson == null ? "{}" : envJson);
            RuntimeState.broadcast(this, appId, "STOPPED", "Python entry file exited");
        } catch (Throwable t) {
            RuntimeState.broadcast(this, appId, "FAILED", t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            android.util.Log.e("LocalhostLoader", "Python server failed", t);
        } finally {
            new android.os.Handler(getMainLooper()).post(() -> {
                stopForeground(true);
                stopSelf();
            });
        }
    }

    private static String safeId(String value) {
        if (value == null || value.isEmpty()) return "unknown";
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    @Override public void onDestroy() {
        if (worker != null) worker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
