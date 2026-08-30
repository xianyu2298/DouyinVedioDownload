package com.xianyu.vediodownload.util;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.xianyu.vediodownload.MainActivity;
import com.xianyu.vediodownload.R;

/**
 * 后台下载通知。
 *
 * 设计目的：在用户点击"后台下载"把进度弹窗隐藏之后，把下载进度通过系统通知持续告知用户。
 * 不启动前台服务 —— 下载任务仍然由 {@link DownloaderBridge} 的 ExecutorService 执行，
 * 这里只负责通知的创建、刷新和收尾。
 *
 * API 形状：
 *  - start(context, total)            —— 批次开始，创建通道，发布首条"开始"通知。
 *  - update(context, currentIndex, currentFilePercent) —— 每个文件进度变化时调用。
 *  - finish(context, successCount, failureCount) —— 整批结束，发布"完成"通知。
 *  - cancel(context)                  —— 取消/删除通知（用于页面回到前台时清除"完成"通知）。
 */
public final class ForegroundDownloadNotifier {

    public static final String CHANNEL_ID = "batch_download_progress";
    public static final int NOTIFICATION_ID = 0xD071;

    private ForegroundDownloadNotifier() {}

    /** 创建通知通道，幂等。仅 API 26+ 可用（更早版本无需通道，通知自动生效）。 */
    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notify_channel_download_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(context.getString(R.string.notify_channel_download_desc));
        channel.setShowBadge(false);
        channel.enableLights(false);
        channel.enableVibration(false);
        nm.createNotificationChannel(channel);
    }

    private static boolean hasPostPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static PendingIntent contentPendingIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(context, 0, intent, flags);
    }

    /**
     * 批次开始：贴一条"开始下载"通知。
     */
    public static void start(Context context, int total) {
        ensureChannel(context);
        if (!hasPostPermission(context)) return;
        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        NotificationCompat.Builder builder = baseBuilder(context)
                .setContentTitle(context.getString(R.string.notify_title_downloading, 1, total))
                .setContentText(context.getString(R.string.notify_text_downloading, 1, total))
                .setProgress(100, 0, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        try {
            nm.notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException ignored) {
            // 极端兜底：权限刚好被撤销
        }
    }

    /**
     * 刷新进度通知。currentIndex 从 1 开始计数。
     */
    public static void update(Context context, int currentIndex, int total, int percentOfCurrentFile) {
        if (!hasPostPermission(context)) return;
        // 不让 UI 进度条在每个文件开始时"回退"太多观感更顺：每文件独立 0-100，但整体标题是 current/total
        int safeCurrent = Math.max(1, currentIndex);
        int safeTotal = Math.max(safeCurrent, total);
        int percent = Math.max(0, Math.min(100, percentOfCurrentFile));
        NotificationCompat.Builder builder = baseBuilder(context)
                .setContentTitle(context.getString(R.string.notify_title_downloading, safeCurrent, safeTotal))
                .setContentText(context.getString(R.string.notify_text_downloading, safeCurrent, safeTotal))
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException ignored) {
        }
    }

    /**
     * 批次完成：贴一条"下载完成"通知，并设 autoCancel 让用户可以划掉。
     */
    public static void finish(Context context, int successCount, int failureCount) {
        if (!hasPostPermission(context)) return;
        String title = context.getString(R.string.notify_title_done);
        String text;
        if (failureCount == 0) {
            text = context.getString(R.string.notify_text_done_success, successCount);
        } else {
            text = context.getString(R.string.notify_text_done_partial, successCount, failureCount);
        }
        NotificationCompat.Builder builder = baseBuilder(context)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false);
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException ignored) {
        }
    }

    /** 用户主动清除（如关闭应用回到前台时） */
    public static void cancel(Context context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID);
        NotificationManagerCompat.from(context).cancel(COMPOSE_NOTIFICATION_ID);
    }

    // ====================== 合成阶段通知（独立 ID，不与下载通知冲突） ======================

    public static final int COMPOSE_NOTIFICATION_ID = 0xD072;

    /**
     * 合成开始：贴一条"正在合成 Live 图 (1/N)"通知。
     */
    public static void startCompose(Context context, int total) {
        ensureChannel(context);
        if (!hasPostPermission(context)) return;
        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        NotificationCompat.Builder builder = baseBuilder(context)
                .setContentTitle(context.getString(R.string.notify_title_composing, 1, total))
                .setContentText(context.getString(R.string.notify_text_composing, 1, total))
                .setProgress(100, 0, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        try {
            nm.notify(COMPOSE_NOTIFICATION_ID, builder.build());
        } catch (SecurityException ignored) {
        }
    }

    public static void updateCompose(Context context, int currentIndex, int total) {
        if (!hasPostPermission(context)) return;
        int safeCurrent = Math.max(1, currentIndex);
        int safeTotal = Math.max(safeCurrent, total);
        NotificationCompat.Builder builder = baseBuilder(context)
                .setContentTitle(context.getString(R.string.notify_title_composing, safeCurrent, safeTotal))
                .setContentText(context.getString(R.string.notify_text_composing, safeCurrent, safeTotal))
                .setProgress(100, (int) (((long) safeCurrent * 100L) / safeTotal), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        try {
            NotificationManagerCompat.from(context).notify(COMPOSE_NOTIFICATION_ID, builder.build());
        } catch (SecurityException ignored) {
        }
    }

    public static void finishCompose(Context context, int successCount, int failureCount) {
        if (!hasPostPermission(context)) return;
        String title = context.getString(R.string.notify_title_compose_done);
        String text;
        if (failureCount == 0) {
            text = context.getString(R.string.notify_text_compose_done_success, successCount);
        } else {
            text = context.getString(R.string.notify_text_compose_done_partial, successCount, failureCount);
        }
        NotificationCompat.Builder builder = baseBuilder(context)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false);
        try {
            NotificationManagerCompat.from(context).notify(COMPOSE_NOTIFICATION_ID, builder.build());
        } catch (SecurityException ignored) {
        }
    }

    private static NotificationCompat.Builder baseBuilder(Context context) {
        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(contentPendingIntent(context));
    }
}
