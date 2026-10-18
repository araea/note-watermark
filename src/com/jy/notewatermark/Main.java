package com.jy.notewatermark;

import android.app.Application;
import android.app.Instrumentation;
import android.content.ContentProvider;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Removes (or replaces) the "ColorOS 便签" / "笔记" watermark that the ColorOS notes
 * apps stamp at the bottom of a note when it is shared as a picture. Two apps are
 * covered: 「便签」 (com.coloros.note) and the AI notes app that arrived with ColorOS 17,
 * 「笔记」 (com.oplus.aipaper), which reuses the same share screen next to a web-based one
 * (see {@link PaperShare}).
 *
 * The watermark lives inside the layout color_os_logo.xml, which is inflated by
 * com.nearme.note.activity.edit.SaveImageAndShare:
 *
 *   logo_ll (mLogoLinearLayout)
 *     line          (mLine)
 *     water_mark_rl (mWaterMarkContainer)
 *       water_mark_ll (mWaterMarkLinearLayout)
 *         water_mark  (mWaterMark)       -> @string/coloros_text  = "ColorOS"
 *         share_logo  (mShareLogo)       -> @string/app_name      = "便签"
 *
 * Devices that are not on ColorOS take the other branch and only get
 * share_logo_original (mShareLogoOriginal).
 *
 * The shared bitmap is drawn from skin_container, which contains logo_ll, so
 * hiding logo_ll keeps the watermark out of the exported picture as well as out
 * of the preview.
 *
 * Settings are edited in the module's launcher activity, which hands them to this
 * process over the bridge below; the copy lives in the Notes app's own preferences.
 * ColorOS will not start the module's process on behalf of the Notes app, so its
 * read-only provider is only a second source for installs that have not synced yet,
 * and the old watermark.txt files the last.
 *
 * The same injection also carries the one-tap note export: the module UI cannot
 * read either app's database, but code running inside the app's process can, so the
 * export is triggered from here and written by {@link NoteExporter} (便签) or
 * {@link PaperExporter} (笔记).
 *
 * Built against the modern libxposed API 102: the entry class extends
 * {@link XposedModule}, hooks are interceptor chains, and no
 * de.robv.android.xposed classes are used. The framework supplies the API at
 * runtime; the module never bundles it.
 */
public class Main extends XposedModule {

    private static final String TAG = "NoteWatermark";
    private static final String MODULE_PKG = "com.jy.notewatermark";

    private static final String[] CONFIG_PATHS = {
        "/sdcard/Android/data/com.coloros.note/files/watermark.txt",
        "/sdcard/note_watermark.txt",
        "/sdcard/Download/note_watermark.txt",
    };

    /**
     * The framework instantiates one entry per process; the static helpers below
     * route their logging through it. It is only set once the framework has
     * attached the interface in {@link #onModuleLoaded}.
     */
    private static volatile Main sInstance;
    /** The hosting app's Application, once it exists; a context for hooks that are not handed one. */
    private static volatile Application sApp;
    private static volatile SharedPreferences sRemote;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        sInstance = this;
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        NotesApp app = NotesApp.forPackage(param.getPackageName());
        if (app == null) {
            return;
        }
        installHooks(app, param.getClassLoader());
    }

    private void installHooks(NotesApp app, ClassLoader classLoader) {
        // The export/status bridge must work even if a newer build moves the
        // share screen and no watermark hooks can be installed.
        hookModuleBridge(app, classLoader);
        hookPendingExport();
        if (app == NotesApp.PAPER) {
            PaperShare.install(this, classLoader);
        }
        ShareAdapt.Target target = ShareAdapt.discover(classLoader);
        if (target == null) {
            log("share screen not found; export and status remain available");
            return;
        }
        log("share screen: " + target.type.getName());
        if (target.logo != null) hookSetLogo(target.logo);
        if (target.image != null) hookCreateImageFile(target.image);
    }

    /** Primary hook: right after the app has filled in the logo/watermark views. */
    private void hookSetLogo(Method setLogo) {
        try {
            hook(setLogo).setId("setLogo").intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    applyQuietly(chain.getThisObject(), "setLogo");
                    return result;
                }
            });
            log("hooked " + setLogo);
        } catch (Throwable t) {
            log("could not hook setLogo(): " + t);
        }
    }

    /** Safety net: run again just before the bitmap is drawn, in case the app
     * re-shows the views after setLogo() (e.g. on a skin change). */
    private void hookCreateImageFile(Method createImageFile) {
        try {
            hook(createImageFile).setId("createImageFile").intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    applyQuietly(chain.getThisObject(), "createImageFile");
                    return chain.proceed();
                }
            });
            log("hooked " + createImageFile);
        } catch (Throwable t) {
            log("could not hook createImageFile(): " + t);
        }
    }

    /**
     * The module settings screen talks to the injected code by querying made-up
     * paths on the app's own exported provider. Answering them here means the
     * work runs in the process that can read the notes, and that a query also starts
     * the app's process when it is not running.
     *
     * Two paths are served: an export request, and a status probe that reports which
     * build of the hook is actually loaded in this process.
     */
    private void hookModuleBridge(final NotesApp app, ClassLoader classLoader) {
        try {
            Method query = classLoader.loadClass(app.bridgeClass).getDeclaredMethod(
                    "query", Uri.class, String[].class, String.class, String[].class, String.class);
            hook(query).setId("moduleBridge").intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Uri uri = (Uri) chain.getArg(0);
                    String segment = uri == null ? null : uri.getLastPathSegment();
                    if (ConfigContract.STATUS_SEGMENT.equals(segment)) {
                        receiveSettings(uri, ((ContentProvider) chain.getThisObject()).getContext());
                        return statusCursor();
                    }
                    if (!ConfigContract.EXPORT_SEGMENT.equals(segment)) {
                        return chain.proceed();
                    }
                    Context context = ((ContentProvider) chain.getThisObject()).getContext();
                    return exportCursor(app, context);
                }
            });
            log("hooked " + app.bridgeClass + ".query()");
        } catch (Throwable t) {
            log("could not hook the module bridge: " + t);
        }
    }

    /**
     * Reports the version baked into the dex that is loaded right now, which is what
     * the settings screen compares against the installed APK: an updated module whose
     * host process has not restarted still answers with the old version.
     */
    private static MatrixCursor statusCursor() {
        MatrixCursor cursor = new MatrixCursor(ConfigContract.STATUS_COLUMNS);
        cursor.addRow(new Object[] { BuildConfig.VERSION_NAME });
        return cursor;
    }

    /** Keeps the settings the module sent along with a status probe, if the module sent them. */
    private static void receiveSettings(Uri uri, Context context) {
        String text = uri.getQueryParameter(ConfigContract.PARAM_TEXT);
        String keepBlank = uri.getQueryParameter(ConfigContract.PARAM_KEEP_BLANK);
        if (text == null || keepBlank == null || context == null) return;
        if (!callerAllowed(context)) {
            log("ignored settings from another app");
            return;
        }
        storeSettings(context, new FooterConfig(clean(text), !"0".equals(keepBlank)));
    }

    private static void storeSettings(Context context, FooterConfig config) {
        try {
            settings(context).edit()
                    .putBoolean(ConfigContract.KEY_SYNCED, true)
                    .putString(ConfigContract.KEY_WATERMARK_TEXT, config.watermarkText)
                    .putBoolean(ConfigContract.KEY_KEEP_BLANK_SPACE, config.keepBlankSpace)
                    .commit();
        } catch (Throwable t) {
            log("could not keep the settings: " + t);
        }
    }

    /**
     * The share screen and the provider may run in different Notes processes, so the
     * file is re-read whenever another process has changed it.
     */
    @SuppressWarnings("deprecation")
    private static SharedPreferences settings(Context context) {
        return context.getSharedPreferences(ConfigContract.NOTE_PREFS, Context.MODE_MULTI_PROCESS);
    }

    /** One line, at most the length the settings screen allows. */
    private static String clean(String text) {
        String line = text.replace('\n', ' ').replace('\r', ' ').trim();
        return line.length() > ConfigContract.WATERMARK_MAX_LENGTH
                ? line.substring(0, ConfigContract.WATERMARK_MAX_LENGTH).trim() : line;
    }

    private static MatrixCursor exportCursor(NotesApp app, Context context) {
        ExportKit.Result result;
        if (context == null || !callerAllowed(context)) {
            result = new ExportKit.Result(false, "导出失败：调用方不是本模块", "");
        } else {
            result = exportFrom(app, context);
        }
        log("export: " + result.message);
        MatrixCursor cursor = new MatrixCursor(ConfigContract.EXPORT_COLUMNS);
        cursor.addRow(new Object[] { result.ok ? 1 : 0, result.message, result.path });
        return cursor;
    }

    private static ExportKit.Result exportFrom(NotesApp app, Context context) {
        return app == NotesApp.PAPER ? PaperExporter.export(context) : NoteExporter.export(context);
    }

    /**
     * Only the module may ask for an export; every other app on the device would
     * otherwise be able to drop the user's notes into the Download folder. The
     * shell is allowed through so the feature can be tested with `content query`.
     */
    private static boolean callerAllowed(Context context) {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid() || uid == 0 || uid == 2000) {
            return true;
        }
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages != null) {
                for (String name : packages) {
                    if (MODULE_PKG.equals(name)) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            log("could not resolve the caller: " + t);
        }
        return false;
    }

    /**
     * Fallback path: when the settings screen cannot reach the provider it leaves
     * a request behind and opens the notes app instead, so the export happens the
     * next time this process starts.
     */
    private void hookPendingExport() {
        try {
            Method callApplicationOnCreate = Instrumentation.class
                    .getDeclaredMethod("callApplicationOnCreate", Application.class);
            hook(callApplicationOnCreate).setId("pendingExport")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            Application app = (Application) chain.getArg(0);
                            NotesApp host = app == null ? null : NotesApp.forPackage(app.getPackageName());
                            if (host != null) {
                                sApp = app;
                                runPendingExport(app, host);
                            }
                            return result;
                        }
                    });
        } catch (Throwable t) {
            log("could not hook application start: " + t);
        }
    }

    /** Off the main thread: nothing here may slow down starting the Notes app. */
    private static void runPendingExport(final Context context, final NotesApp app) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                long request = readExportRequest(context, app);
                if (request <= 0) {
                    return;
                }
                SharedPreferences prefs = context.getSharedPreferences(
                        ConfigContract.NOTE_PREFS, Context.MODE_PRIVATE);
                if (prefs.getLong(ConfigContract.KEY_EXPORT_HANDLED, 0L) >= request) {
                    return;
                }
                // Claim it first: a crash mid-export must not loop on every start.
                prefs.edit().putLong(ConfigContract.KEY_EXPORT_HANDLED, request).apply();

                ExportKit.Result result = exportFrom(app, context);
                log("pending export: " + result.message);
                toast(context, result.message);
            }
        }, "note-watermark-export").start();
    }

    /** The pending request's time, or 0 when there is none or it was left for the other app. */
    private static long readExportRequest(Context context, NotesApp app) {
        SharedPreferences remote = remote();
        if (remote != null && remote.contains(ConfigContract.KEY_EXPORT_REQUEST)) {
            String wanted = remote.getString(ConfigContract.KEY_EXPORT_TARGET, NotesApp.NOTES.pkg);
            return app.pkg.equals(wanted) ? remote.getLong(ConfigContract.KEY_EXPORT_REQUEST, 0L) : 0L;
        }
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    ConfigContract.URI, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int target = cursor.getColumnIndex(ConfigContract.COLUMN_EXPORT_TARGET);
                // A module from before 4.2 only ever meant the 便签 app.
                String wanted = target >= 0 && !cursor.isNull(target)
                        ? cursor.getString(target) : NotesApp.NOTES.pkg;
                if (!app.pkg.equals(wanted)) {
                    return 0L;
                }
                int column = cursor.getColumnIndex(ConfigContract.COLUMN_EXPORT_REQUEST);
                return column >= 0 ? cursor.getLong(column) : 0L;
            }
            log("the module's settings provider answered nothing");
        } catch (Throwable t) {
            log("could not read the export request: " + t);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return 0L;
    }

    private static void toast(final Context context, final String message) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    log("could not show a toast: " + t);
                }
            }
        });
    }

    private static void applyQuietly(Object activity, String from) {
        try {
            apply(activity, from);
        } catch (Throwable t) {
            log("apply() failed in " + from + ": " + t);
        }
    }

    private static void apply(Object activity, String from) {
        if (ShareAdapt.paintsOwnFooter(activity)) {
            // The page carries its own footer (see PaperShare); the app's legacy footer under
            // it is a preview leftover that was never part of the picture.
            ShareAdapt.Views leftover = ShareAdapt.resolve(activity);
            setVisibility(leftover.row, 8);
            setVisibility(leftover.watermark, 8);
            setVisibility(leftover.share, 8);
            setVisibility(leftover.original, 8);
            return;
        }
        FooterConfig config = readConfig(activity);
        String custom = config.watermarkText;

        ShareAdapt.Views views = ShareAdapt.resolve(activity);
        Object logoLl = views.row;
        Object line = views.line;
        Object waterMark = views.watermark;
        Object shareLogo = views.share;
        Object shareLogoOriginal = views.original;
        if (logoLl == null && waterMark == null && shareLogo == null
                && shareLogoOriginal == null) return;

        if (custom.length() == 0 && !config.keepBlankSpace) {
            // Hide the whole row: the divider line and both text views live inside it,
            // so no empty gap is left at the bottom of the picture.
            setVisibility(logoLl, 8);
            setVisibility(line, 8);
            setVisibility(waterMark, 8);
            setVisibility(shareLogo, 8);
            setVisibility(shareLogoOriginal, 8);
            log(from + ": watermark removed");
        } else if (custom.length() == 0) {
            // INVISIBLE keeps the original measured watermark row (roughly two text
            // lines) as clean bottom padding without changing the note itself.
            setVisibility(logoLl, 0);
            setVisibility(line, 4);
            setVisibility(waterMark, 4);
            setVisibility(shareLogo, 4);
            setVisibility(shareLogoOriginal, 4);
            log(from + ": blank watermark spacing kept");
        } else {
            // "ColorOS" half goes away, the app-name half carries the custom text.
            setVisibility(waterMark, 8);
            setVisibility(logoLl, 0);
            setVisibility(line, 0);
            if (shareLogo != null) {
                setText(shareLogo, custom);
                setVisibility(shareLogo, 0);
            }
            if (shareLogoOriginal != null) {
                setText(shareLogoOriginal, custom);
                setVisibility(shareLogoOriginal, 0);
            }
            log(from + ": custom watermark applied");
        }
    }

    /**
     * The framework's copy of the module's settings, which the settings screen keeps current
     * whether or not this app was running at the time. Null where there is none.
     */
    private static SharedPreferences remote() {
        SharedPreferences kept = sRemote;
        if (kept != null) return kept;
        Main instance = sInstance;
        if (instance == null) return null;
        try {
            return sRemote = instance.getRemotePreferences(ConfigContract.REMOTE_GROUP);
        } catch (Throwable t) {
            return null;
        }
    }

    static FooterConfig readConfig(Object activity) {
        SharedPreferences remote = remote();
        if (remote != null && remote.contains(ConfigContract.KEY_WATERMARK_TEXT)) {
            return new FooterConfig(clean(remote.getString(ConfigContract.KEY_WATERMARK_TEXT, "")),
                    remote.getBoolean(ConfigContract.KEY_KEEP_BLANK_SPACE, true));
        }
        if (activity == null) {
            activity = sApp;
        }
        if (activity instanceof Context) {
            Context context = (Context) activity;
            try {
                SharedPreferences kept = settings(context);
                if (kept.getBoolean(ConfigContract.KEY_SYNCED, false)) {
                    return new FooterConfig(
                            clean(kept.getString(ConfigContract.KEY_WATERMARK_TEXT, "")),
                            kept.getBoolean(ConfigContract.KEY_KEEP_BLANK_SPACE, true));
                }
            } catch (Throwable t) {
                log("could not read the kept settings: " + t);
            }
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(
                        ConfigContract.URI, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int textColumn = cursor.getColumnIndex(
                            ConfigContract.COLUMN_WATERMARK_TEXT);
                    int spaceColumn = cursor.getColumnIndex(
                            ConfigContract.COLUMN_KEEP_BLANK_SPACE);
                    String text = textColumn >= 0 ? cursor.getString(textColumn) : "";
                    boolean keepSpace = spaceColumn < 0 || cursor.getInt(spaceColumn) != 0;
                    FooterConfig config = new FooterConfig(text == null ? "" : clean(text), keepSpace);
                    storeSettings(context, config);
                    return config;
                }
                log("settings not synced yet and the module's provider answered nothing");
            } catch (Throwable t) {
                log("could not read settings provider: " + t);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        // Compatibility fallback for old installs and frameworks that block the
        // exported settings provider.
        return new FooterConfig(readLegacyCustomText(), true);
    }

    private static String readLegacyCustomText() {
        for (String path : CONFIG_PATHS) {
            try {
                File f = new File(path);
                if (!f.isFile() || !f.canRead() || f.length() > 4096) {
                    continue;
                }
                FileInputStream in = new FileInputStream(f);
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[1024];
                int n;
                try {
                    while ((n = in.read(chunk)) > 0) {
                        buf.write(chunk, 0, n);
                    }
                } finally {
                    in.close();
                }
                String text = new String(buf.toByteArray(), "UTF-8");
                int nl = text.indexOf('\n');
                if (nl >= 0) {
                    text = text.substring(0, nl);
                }
                text = text.trim();
                if (text.length() > 0) {
                    return text;
                }
            } catch (Throwable t) {
                // unreadable config is not an error - fall through to "remove"
            }
        }
        return "";
    }

    private static void setVisibility(Object view, int visibility) {
        if (view == null) {
            return;
        }
        try {
            view.getClass().getMethod("setVisibility", int.class).invoke(view, visibility);
        } catch (Throwable t) {
            log("setVisibility failed: " + t);
        }
    }

    private static void setText(Object view, CharSequence text) {
        try {
            view.getClass().getMethod("setText", CharSequence.class).invoke(view, text);
        } catch (Throwable t) {
            log("setText failed: " + t);
        }
    }

    /** Logs through the framework once it is attached, to logcat before that. */
    static void log(String message) {
        Main instance = sInstance;
        if (instance != null) {
            try {
                instance.log(Log.INFO, TAG, message);
                return;
            } catch (Throwable t) {
                // fall through to logcat
            }
        }
        Log.i(TAG, message);
    }
}
