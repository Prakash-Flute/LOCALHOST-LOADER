package com.laaprak.localhostloader.runtime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;

import com.laaprak.localhostloader.MainActivity;
import com.laaprak.localhostloader.R;

public final class RuntimeNotifications {
    public static final String CHANNEL_ID = "local_runtime_services";
    private RuntimeNotifications() { }

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Local app services", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Shows server apps which you chose to keep running on this device.");
                channel.enableLights(false);
                channel.enableVibration(false);
                manager.createNotificationChannel(channel);
            }
        }
    }

    public static void startForeground(Service service, int id, Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            int type = Build.VERSION.SDK_INT >= 34
                ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
            service.startForeground(id, notification, type);
        } else {
            service.startForeground(id, notification);
        }
    }

    public static Notification build(Service service, String title, String detail, int id) {
        ensureChannel(service);
        Intent open = new Intent(service, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(service, id, open,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(service, CHANNEL_ID) : new Notification.Builder(service);
        builder.setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setColor(Color.rgb(112, 224, 192));
        if (Build.VERSION.SDK_INT >= 24) builder.setShowWhen(false);
        return builder.build();
    }
}
