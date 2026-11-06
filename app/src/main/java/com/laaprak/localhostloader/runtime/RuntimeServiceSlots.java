package com.laaprak.localhostloader.runtime;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import java.util.HashMap;
import java.util.Map;

/** Assigns each long-running server its own Android process slot. */
public final class RuntimeServiceSlots {
    private static final String PREFS = "localhost_loader_runtime_slots";
    private static final String PREFIX = "slot_";
    private static final int SLOT_COUNT = 4;
    private static final Map<String, Class<?>> PYTHON = new HashMap<>();
    private static final Map<String, Class<?>> NODE = new HashMap<>();
    static {
        PYTHON.put("1", PythonRuntimeService.class);
        PYTHON.put("2", PythonRuntimeService2.class);
        PYTHON.put("3", PythonRuntimeService3.class);
        PYTHON.put("4", PythonRuntimeService4.class);
        NODE.put("1", NodeRuntimeService.class);
        NODE.put("2", NodeRuntimeService2.class);
        NODE.put("3", NodeRuntimeService3.class);
        NODE.put("4", NodeRuntimeService4.class);
    }
    private RuntimeServiceSlots() { }

    public static synchronized Intent acquire(Context context, String runtime, String appId, String action) {
        String key = runtime.toLowerCase() + ":" + appId;
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String existing = prefs.getString(key, "");
        Map<String, Class<?>> slots = "python".equals(runtime) ? PYTHON : NODE;
        if (!existing.isEmpty() && slots.containsKey(existing)) {
            return new Intent(context, slots.get(existing)).setAction(action);
        }
        for (int i = 1; i <= SLOT_COUNT; i++) {
            String candidate = String.valueOf(i);
            boolean used = false;
            for (String k : prefs.getAll().keySet()) {
                if ((runtime + ":").equals(k.substring(0, Math.min(k.length(), runtime.length() + 1)))) {
                    String value = prefs.getString(k, "");
                    if (candidate.equals(value)) { used = true; break; }
                }
            }
            if (!used) {
                prefs.edit().putString(key, candidate).apply();
                return new Intent(context, slots.get(candidate)).setAction(action);
            }
        }
        return null;
    }

    public static synchronized void release(Context context, String runtime, String appId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(runtime.toLowerCase() + ":" + appId).apply();
    }

    public static synchronized String assignedSlot(Context context, String runtime, String appId) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(runtime.toLowerCase() + ":" + appId, "");
    }
}
