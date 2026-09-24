package com.jy.notewatermark;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;

/**
 * The settings screen's one conversation with the hook: it hands the Notes process the
 * current footer settings and learns which build of the hook answered. Blocking; call
 * it off the main thread.
 */
final class NotesBridge {
    static final int ACTIVE = 1, STALE = 2, INACTIVE = 3, MISSING = 4;

    static int sync(Context context) {
        try {
            context.getPackageManager().getPackageInfo(ConfigContract.NOTE_PKG, 0);
        } catch (Exception missing) {
            return MISSING;
        }
        SharedPreferences prefs = context.getSharedPreferences(ConfigContract.PREFS, 0);
        String text = prefs.getString(ConfigContract.KEY_WATERMARK_TEXT, "");
        boolean keepBlank = prefs.getBoolean(ConfigContract.KEY_KEEP_BLANK_SPACE, true);
        try (Cursor cursor = context.getContentResolver().query(
                ConfigContract.statusUri(text, keepBlank), null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return INACTIVE;
            int column = cursor.getColumnIndex(ConfigContract.COLUMN_MODULE_VERSION);
            if (column < 0 || cursor.isNull(column)) return INACTIVE;
            return BuildConfig.VERSION_NAME.equals(cursor.getString(column)) ? ACTIVE : STALE;
        } catch (Exception unhooked) {
            return INACTIVE;
        }
    }

    private NotesBridge() { }
}
