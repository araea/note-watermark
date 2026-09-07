package com.jy.notewatermark;

import android.net.Uri;

/** Shared names used by the settings UI, provider and injected hook. */
final class ConfigContract {
    static final String AUTHORITY = "com.jy.notewatermark.settings";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/config");

    static final String PREFS = "settings";
    static final String KEY_WATERMARK_TEXT = "watermark_text";
    static final String KEY_KEEP_BLANK_SPACE = "keep_blank_space";
    static final String KEY_EXPORT_REQUEST = "export_request";

    static final String COLUMN_WATERMARK_TEXT = "watermark_text";
    static final String COLUMN_KEEP_BLANK_SPACE = "keep_blank_space";
    static final String COLUMN_EXPORT_REQUEST = "export_request";

    /** Notes app package and the exported provider used to reach into it. */
    static final String NOTE_PKG = "com.coloros.note";
    static final String NOTE_AUTHORITY = "com.oneplus.provider.Note";

    /**
     * Querying this path is intercepted by the injected hook, which runs the
     * export inside the Notes process and answers with {@link #EXPORT_COLUMNS}.
     * The path is meaningless to the Notes app itself.
     */
    static final String EXPORT_SEGMENT = "note_watermark_export";
    static final Uri EXPORT_URI =
            Uri.parse("content://" + NOTE_AUTHORITY + "/" + EXPORT_SEGMENT);

    static final String COLUMN_EXPORT_OK = "ok";
    static final String COLUMN_EXPORT_MESSAGE = "message";
    static final String COLUMN_EXPORT_PATH = "path";
    static final String[] EXPORT_COLUMNS = {
        COLUMN_EXPORT_OK, COLUMN_EXPORT_MESSAGE, COLUMN_EXPORT_PATH,
    };

    /** Where the hook remembers, inside the Notes app, what it already ran. */
    static final String NOTE_PREFS = "note_watermark";
    static final String KEY_EXPORT_HANDLED = "export_handled";

    private ConfigContract() {
    }
}
