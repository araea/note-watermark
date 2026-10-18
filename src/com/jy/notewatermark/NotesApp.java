package com.jy.notewatermark;

import android.net.Uri;

/**
 * The two ColorOS note apps this module reaches into, and the door it knocks on for each.
 *
 * The module's own process cannot read either app's data, and ColorOS will not start the
 * module on their behalf, so everything goes through a provider the app already exports
 * without a permission: the hook answers a made-up path on it from inside the app.
 */
final class NotesApp {
    /** 「便签」, the long-standing notes app. */
    static final NotesApp NOTES = new NotesApp("com.coloros.note", "com.oneplus.provider.Note",
            "com.oplus.migrate.backuprestore.NoteBackupRestoreProvider");

    /**
     * 「笔记」, the AI notes app that arrived with ColorOS 17. Its own providers are either
     * not exported or behind signature permissions; the one open door is a logging SDK's
     * provider, whose {@code query} is an empty stub in the shared bridge base class.
     */
    static final NotesApp PAPER = new NotesApp("com.oplus.aipaper",
            "com.oplus.aipaper.hlog.HLOG_SDK_PROVIDER",
            "com.opos.process.bridge.server.ProcessBridgeProvider");

    static final NotesApp[] ALL = { PAPER, NOTES };

    final String pkg;
    final String authority;
    /** The class whose {@code query()} the hook answers on; the exported provider inherits or declares it. */
    final String bridgeClass;

    private NotesApp(String pkg, String authority, String bridgeClass) {
        this.pkg = pkg;
        this.authority = authority;
        this.bridgeClass = bridgeClass;
    }

    static NotesApp forPackage(String pkg) {
        for (NotesApp app : ALL) {
            if (app.pkg.equals(pkg)) return app;
        }
        return null;
    }

    /** Querying this is intercepted by the hook, which runs the export and answers with its outcome. */
    Uri exportUri() {
        return Uri.parse("content://" + authority + "/" + ConfigContract.EXPORT_SEGMENT);
    }

    /**
     * The status probe also carries the current footer settings: the hook keeps them inside
     * the app, because a share made while the module is not running could not read them
     * from the module otherwise. Hooks from before 4.1 ignore the parameters.
     */
    Uri statusUri(String text, boolean keepBlank) {
        return Uri.parse("content://" + authority + "/" + ConfigContract.STATUS_SEGMENT).buildUpon()
                .appendQueryParameter(ConfigContract.PARAM_TEXT, text)
                .appendQueryParameter(ConfigContract.PARAM_KEEP_BLANK, keepBlank ? "1" : "0")
                .build();
    }
}
