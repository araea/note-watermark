package com.jy.notewatermark;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * Looks for a new release once a day and says so in one quiet notification, once per
 * version. The system picks the moment (online, not in deep sleep); turning automatic
 * checks off cancels the job.
 */
public final class UpdateJob extends JobService {
    private static final int JOB_ID = 0x5175;
    private static final int NOTICE_AVAILABLE = 1, NOTICE_UPDATED = 2;
    private static final String CHANNEL = "update";
    private static final long PERIOD_MS = 24L * 60 * 60 * 1000;

    /** Schedules or cancels; an existing schedule is kept, so opening the app does not reset the clock. */
    static void schedule(Context context, boolean on) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        if (!on) {
            scheduler.cancel(JOB_ID);
            return;
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return;
        try {
            scheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, UpdateJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build());
        } catch (RuntimeException refused) {
            // Only the background reminder is lost; the screen still checks when opened.
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        final Updater updater = Updater.get(this);
        if (!updater.auto()) {
            schedule(this, false);
            return false;
        }
        updater.checkThen(() -> {
            Updater.State state = updater.state();
            if (!state.newer) {
                cancelAvailable(this);
            } else if (state.phase == Updater.IDLE && !updater.notified(state.release.tag)
                    && !ConfigActivity.visible() && available(this, state.release)) {
                updater.markNotified(state.release.tag);
            }
            jobFinished(params, false);
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }

    /** 「素笺 4.3.0 可以更新」, with a button that opens the screen and starts right away. */
    static boolean available(Context context, Release release) {
        NotificationManager manager = channel(context);
        if (manager == null) return false;
        String summary = release.summary();
        Notification.Builder notice = builder(context)
                .setContentTitle(context.getString(R.string.update_notice_title, release.version))
                .setContentText(summary.isEmpty() ? context.getString(R.string.update_notice_text) : summary)
                .setStyle(new Notification.BigTextStyle().bigText(summary.isEmpty()
                        ? context.getString(R.string.update_notice_text) : summary))
                .setContentIntent(open(context, false))
                .addAction(new Notification.Action.Builder(null,
                        context.getString(R.string.update_now), open(context, true)).build());
        manager.notify(NOTICE_AVAILABLE, notice.build());
        return true;
    }

    /**
     * After the screen updated this app. When the system installs without asking, the
     * app simply disappears from under the user's finger, so this is the only word they get.
     */
    static void updated(Context context, String version) {
        NotificationManager manager = channel(context);
        if (manager == null) return;
        manager.cancel(NOTICE_AVAILABLE);
        manager.notify(NOTICE_UPDATED, builder(context)
                .setContentTitle(context.getString(R.string.update_done_title, version))
                .setContentText(context.getString(R.string.update_done_text))
                .setStyle(new Notification.BigTextStyle().bigText(context.getString(R.string.update_done_text)))
                .setContentIntent(open(context, false))
                .build());
    }

    /** That version is installed now, one way or another. */
    static void cancelAvailable(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTICE_AVAILABLE);
    }

    /** The screen is up and says it itself. */
    static void clear(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancelAll();
    }

    private static NotificationManager channel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return null;
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.update_channel_detail));
        manager.createNotificationChannel(channel);
        return manager;
    }

    private static Notification.Builder builder(Context context) {
        return new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_update)
                .setColor(context.getColor(R.color.primary))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true);
    }

    private static PendingIntent open(Context context, boolean update) {
        Intent intent = new Intent(context, ConfigActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(ConfigActivity.EXTRA_UPDATE, update);
        return PendingIntent.getActivity(context, update ? 1 : 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
