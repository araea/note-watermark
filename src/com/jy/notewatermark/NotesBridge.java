package com.jy.notewatermark;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;

/**
 * The settings screen's one conversation with a hook: it hands a notes app's process the
 * current footer settings and learns which build of the hook answered. Blocking; call
 * it off the main thread.
 */
final class NotesBridge {
    /**
     * ACTIVE: the hook answered. STALE: it answered with an older version. INACTIVE: nobody
     * answered and nothing says why. MISSING: the app is not installed. IDLE: enabled and in
     * scope, but the app is not running, so there is nothing to answer yet. NEEDS_SCOPE: the
     * framework does not apply the module to the app.
     */
    static final int ACTIVE = 1, STALE = 2, INACTIVE = 3, MISSING = 4, IDLE = 5, NEEDS_SCOPE = 6;

    /**
     * The state to show for an app: the probe's answer when there is one, otherwise what the
     * framework knows. The probe cannot wake an app that is not running (ColorOS refuses to
     * start another app's process for a content provider), so silence alone is not proof
     * that the module is off.
     */
    static int resolve(Context context, NotesApp app) {
        int probe = sync(context, app);
        if (probe != INACTIVE) return probe;
        if (!Remote.bound()) return INACTIVE;
        java.util.List<String> scope = Remote.scope();
        if (scope != null && !scope.contains(app.pkg)) return NEEDS_SCOPE;
        Remote.Running running = Remote.running(app);
        if (running == null || !running.any) return IDLE;
        return running.stale ? STALE : ACTIVE;
    }

    static int sync(Context context, NotesApp app) {
        try {
            context.getPackageManager().getPackageInfo(app.pkg, 0);
        } catch (Exception missing) {
            return MISSING;
        }
        SharedPreferences prefs = context.getSharedPreferences(ConfigContract.PREFS, 0);
        String text = prefs.getString(ConfigContract.KEY_WATERMARK_TEXT, "");
        boolean keepBlank = prefs.getBoolean(ConfigContract.KEY_KEEP_BLANK_SPACE, true);
        try (Cursor cursor = context.getContentResolver().query(
                app.statusUri(text, keepBlank), null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return INACTIVE;
            int column = cursor.getColumnIndex(ConfigContract.COLUMN_MODULE_VERSION);
            if (column < 0 || cursor.isNull(column)) return INACTIVE;
            return BuildConfig.VERSION_NAME.equals(cursor.getString(column)) ? ACTIVE : STALE;
        } catch (Exception unhooked) {
            return INACTIVE;
        }
    }

    static boolean installed(Context context, NotesApp app) {
        try {
            context.getPackageManager().getPackageInfo(app.pkg, 0);
            return true;
        } catch (Exception missing) {
            return false;
        }
    }

    private NotesBridge() { }
}
