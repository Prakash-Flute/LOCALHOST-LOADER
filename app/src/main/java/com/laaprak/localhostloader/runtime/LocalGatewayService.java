package com.laaprak.localhostloader.runtime;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.File;

public class LocalGatewayService extends Service {
    public static final String ACTION_REGISTER_STATIC = "com.laaprak.localhostloader.GATEWAY_STATIC";
    public static final String ACTION_REGISTER_BACKEND = "com.laaprak.localhostloader.GATEWAY_BACKEND";
    public static final String ACTION_UNREGISTER = "com.laaprak.localhostloader.GATEWAY_UNREGISTER";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_ROOT = "root";
    public static final String EXTRA_PORT = "backend_port";

    private static volatile LocalGatewayServer gateway;
    private static volatile int gatewayPort;

    public static int getPort() { return gatewayPort; }
    public static String route(String id, String homepage) {
        String path = homepage == null || homepage.isEmpty() ? "/" : homepage;
        if (!path.startsWith("/")) path = "/" + path;
        return "/app/" + id + path;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            ensureGateway();
            if (intent != null) {
                String action = intent.getAction();
                String id = intent.getStringExtra(EXTRA_ID);
                if (ACTION_REGISTER_STATIC.equals(action)) {
                    gateway.registerStatic(id, new File(intent.getStringExtra(EXTRA_ROOT)));
                } else if (ACTION_REGISTER_BACKEND.equals(action)) {
                    gateway.registerBackend(id, intent.getIntExtra(EXTRA_PORT, 0));
                } else if (ACTION_UNREGISTER.equals(action)) {
                    gateway.unregister(id);
                }
            }
            if (Build.VERSION.SDK_INT >= 26) {
                RuntimeNotifications.startForeground(this, 7200,
                    RuntimeNotifications.build(this, "Localhost Loader is active", "Your local apps are ready inside the built-in browser", 7200));
            }
        } catch (Exception e) {
            android.util.Log.e("LocalhostLoader", "Gateway failed", e);
        }
        return START_STICKY;
    }

    private synchronized void ensureGateway() throws Exception {
        if (gateway == null || !gateway.isRunning()) {
            gateway = new LocalGatewayServer();
            gateway.start();
            gatewayPort = gateway.getPort();
            getSharedPreferences("localhost_loader_runtime", MODE_PRIVATE).edit().putInt("gateway_port", gatewayPort).apply();
        }
    }

    @Override public void onDestroy() {
        if (gateway != null) { gateway.stop(); gateway = null; gatewayPort = 0; }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
