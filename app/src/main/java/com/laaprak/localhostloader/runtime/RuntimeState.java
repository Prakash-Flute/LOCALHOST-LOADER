package com.laaprak.localhostloader.runtime;

import android.content.Context;
import android.content.Intent;

public final class RuntimeState {
    public static final String ACTION_STATE = "com.laaprak.localhostloader.RUNTIME_STATE";
    public static final String EXTRA_APP_ID = "app_id";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_MESSAGE = "message";

    private RuntimeState() { }

    public static void broadcast(Context context, String appId, String state, String message) {
        Intent intent = new Intent(ACTION_STATE);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_APP_ID, appId == null ? "" : appId);
        intent.putExtra(EXTRA_STATE, state == null ? "UNKNOWN" : state);
        intent.putExtra(EXTRA_MESSAGE, message == null ? "" : message);
        context.sendBroadcast(intent);
    }
}
