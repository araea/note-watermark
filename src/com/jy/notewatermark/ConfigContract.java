package com.jy.notewatermark;

import android.net.Uri;

/** Shared names used by the settings UI, provider and injected hook. */
final class ConfigContract {
    static final String AUTHORITY = "com.jy.notewatermark.settings";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/config");

    static final String PREFS = "settings";
    static final String KEY_WATERMARK_TEXT = "watermark_text";
    static final String KEY_KEEP_BLANK_SPACE = "keep_blank_space";
    /** UI-only selection: the hook still reads the two existing keys. */
    static final String KEY_SELECTED_MODE = "selected_mode";
    static final String KEY_EXPORT_REQUEST = "export_request";
    /** Remembered so switching away from the custom mode does not throw the text away. */
    static final String KEY_LAST_CUSTOM_TEXT = "last_custom_text";

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

    /**
     * Same trick as the export path, but it only answers "the hook is running here,
     * and this is the version of the code that is loaded". Querying it is how the
     * settings screen can tell an enabled module from a merely installed one.
     */
    static final String STATUS_SEGMENT = "note_watermark_status";
    static final Uri STATUS_URI =
            Uri.parse("content://" + NOTE_AUTHORITY + "/" + STATUS_SEGMENT);

    /**
     * The settings screen appends the current footer settings to the status probe. The
     * hook keeps them inside the Notes process, because ColorOS refuses to start this
     * module's process for the Notes app: without the copy, a share made while the
     * module is not running could not read the settings at all. Hooks from before 4.1
     * ignore the parameters and still answer the probe.
     */
    static final String PARAM_TEXT = "watermark_text";
    static final String PARAM_KEEP_BLANK = "keep_blank_space";
    static final int WATERMARK_MAX_LENGTH = 80;

    static Uri statusUri(String text, boolean keepBlank) {
        return STATUS_URI.buildUpon()
                .appendQueryParameter(PARAM_TEXT, text)
                .appendQueryParameter(PARAM_KEEP_BLANK, keepBlank ? "1" : "0")
                .build();
    }

    static final String COLUMN_MODULE_VERSION = "module_version";
    static final String[] STATUS_COLUMNS = { COLUMN_MODULE_VERSION };

    static final String COLUMN_EXPORT_OK = "ok";
    static final String COLUMN_EXPORT_MESSAGE = "message";
    static final String COLUMN_EXPORT_PATH = "path";
    static final String[] EXPORT_COLUMNS = {
        COLUMN_EXPORT_OK, COLUMN_EXPORT_MESSAGE, COLUMN_EXPORT_PATH,
    };

    /** Where the hook remembers, inside the Notes app, what it already ran. */
    static final String NOTE_PREFS = "note_watermark";
    static final String KEY_EXPORT_HANDLED = "export_handled";
    /** The copy of the footer settings kept inside the Notes process. */
    static final String KEY_SYNCED = "settings_synced";

    private ConfigContract() {
    }
}
