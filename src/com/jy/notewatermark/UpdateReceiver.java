package com.jy.notewatermark;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/**
 * Two messages about an update: the system installer reporting on the session (including
 * "the user has to confirm", with the screen to show), and the new copy of this app
 * having replaced the old one.
 */
public final class UpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Updater.ACTION_STATUS.equals(action)) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            Intent confirm = status == PackageInstaller.STATUS_PENDING_USER_ACTION
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT) : null;
            Updater.get(context).status(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), confirm);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Updater updater = Updater.get(context);
            String version = updater.replaced();
            if (!version.isEmpty()) {
                // A screen the system already brought back says it itself; otherwise a notification.
                if (!ConfigActivity.visible()) UpdateJob.updated(context, version);
                updater.announce();
            }
            UpdateJob.schedule(context, updater.auto());
        }
    }
}
