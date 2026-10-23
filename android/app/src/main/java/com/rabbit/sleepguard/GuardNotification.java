package com.rabbit.sleepguard;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

public final class GuardNotification {
    private static final String CHANNEL_STATUS = "sleep_guard_status";
    private static final String CHANNEL_CAUGHT = "sleep_guard_caught";
    private static final int STATUS_ID = 2101;
    private static final int CAUGHT_ID = 2102;

    private GuardNotification() {
    }

    public static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel status = new NotificationChannel(CHANNEL_STATUS, "睡眠守卫状态", NotificationManager.IMPORTANCE_LOW);
        status.setDescription("显示守卫是否正在运行");
        NotificationChannel caught = new NotificationChannel(CHANNEL_CAUGHT, "拦截回执", NotificationManager.IMPORTANCE_HIGH);
        caught.setDescription("打开受限应用时留下回执");
        manager.createNotificationChannel(status);
        manager.createNotificationChannel(caught);
    }

    private static boolean allowed(Context context) {
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private static PendingIntent openApp(Context context) {
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static void showActive(Context context, int attempts) {
        if (!allowed(context)) return;
        createChannels(context);
        Notification notification = new Notification.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_guard)
                .setContentTitle("比比正在守着兔酱睡觉")
                .setContentText(attempts == 0 ? "手机放好，安心睡觉。" : "今晚已经拦住 " + attempts + " 次。")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(context))
                .build();
        context.getSystemService(NotificationManager.class).notify(STATUS_ID, notification);
    }

    public static void screenSharing(Context context, boolean enabled) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!enabled) { manager.cancel(2103); return; }
        if (!allowed(context)) return;
        createChannels(context);
        manager.notify(2103, new Notification.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_guard)
                .setContentTitle("屏幕共享已开启")
                .setContentText("伴侣可按需看一张屏幕 · 点此打开应用关闭")
                .setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(openApp(context)).build());
    }

    public static void hideActive(Context context) {
        context.getSystemService(NotificationManager.class).cancel(STATUS_ID);
    }

    public static void caught(Context context, String appName, int attempts) {
        if (!allowed(context)) return;
        createChannels(context);
        String text = attempts <= 1
                ? "抓到了。" + appName + "先放下，回去睡觉。"
                : "第 " + attempts + " 次。手机放好，兔酱该睡了。";
        Notification notification = new Notification.Builder(context, CHANNEL_CAUGHT)
                .setSmallIcon(R.drawable.ic_guard)
                .setContentTitle("被比比抓到了")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build();
        context.getSystemService(NotificationManager.class).notify(CAUGHT_ID, notification);
    }
}

