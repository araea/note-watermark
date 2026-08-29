package com.jy.notewatermark;

import android.net.Uri;

/** Shared names used by the settings UI, provider and injected hook. */
final class ConfigContract {
    static final String AUTHORITY = "com.jy.notewatermark.settings";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/config");

    static final String PREFS = "settings";
    static final String KEY_WATERMARK_TEXT = "watermark_text";
    static final String KEY_KEEP_BLANK_SPACE = "keep_blank_space";

    static final String COLUMN_WATERMARK_TEXT = "watermark_text";
    static final String COLUMN_KEEP_BLANK_SPACE = "keep_blank_space";

    private ConfigContract() {
    }
}
