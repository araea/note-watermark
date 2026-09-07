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
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Removes (or replaces) the "ColorOS 便签" watermark that the ColorOS Notes app
 * stamps at the bottom of a note when it is shared as a picture.
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
 * Settings are edited in the module's launcher activity and read through a
 * read-only provider. The old watermark.txt files remain as a fallback.
 *
 * The same injection also carries the one-tap note export: the module UI cannot
 * read the Notes database, but code running inside the Notes process can, so the
 * export is triggered from here and written by {@link NoteExporter}.
 */
public class Main implements IXposedHookLoadPackage {

    private static final String TAG = "[NoteWatermark] ";
    private static final String TARGET_PKG = "com.coloros.note";
    private static final String TARGET_CLASS = "com.nearme.note.activity.edit.SaveImageAndShare";
    /** Exported, permission-free provider of the Notes app; see AndroidManifest. */
    private static final String BACKUP_PROVIDER =
            "com.oplus.migrate.backuprestore.NoteBackupRestoreProvider";
    private static final String MODULE_PKG = "com.jy.notewatermark";

    private static final String[] CONFIG_PATHS = {
        "/sdcard/Android/data/com.coloros.note/files/watermark.txt",
        "/sdcard/note_watermark.txt",
        "/sdcard/Download/note_watermark.txt",
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }

        // Primary hook: right after the app has filled in the logo/watermark views.
        try {
            XposedHelpers.findAndHookMethod(TARGET_CLASS, lpparam.classLoader, "setLogo",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            applyQuietly(param.thisObject, "setLogo");
                        }
                    });
            XposedBridge.log(TAG + "hooked " + TARGET_CLASS + ".setLogo()");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "could not hook setLogo(): " + t);
        }

        hookExportTrigger(lpparam);
        hookPendingExport();

        // Safety net: run again just before the bitmap is drawn, in case the app
        // re-shows the views after setLogo() (e.g. on a skin change).
        try {
            XposedHelpers.findAndHookMethod(TARGET_CLASS, lpparam.classLoader, "createImageFile",
                    int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            applyQuietly(param.thisObject, "createImageFile");
                        }
                    });
            XposedBridge.log(TAG + "hooked " + TARGET_CLASS + ".createImageFile(int,int,int)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "could not hook createImageFile(): " + t);
        }
    }

    /**
     * The module settings screen asks for an export by querying a made-up path on
     * the Notes app's own exported provider. Answering it here means the export
     * runs in the process that can read the notes, and that a query also starts
     * the Notes process when it is not running.
     */
    private static void hookExportTrigger(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(BACKUP_PROVIDER, lpparam.classLoader, "query",
                    Uri.class, String[].class, String.class, String[].class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Uri uri = (Uri) param.args[0];
                            if (uri == null || !ConfigContract.EXPORT_SEGMENT
                                    .equals(uri.getLastPathSegment())) {
                                return;
                            }
                            Context context = ((ContentProvider) param.thisObject).getContext();
                            param.setResult(exportCursor(context));
                        }
                    });
            XposedBridge.log(TAG + "hooked " + BACKUP_PROVIDER + ".query()");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "could not hook the export trigger: " + t);
        }
    }

    private static MatrixCursor exportCursor(Context context) {
        NoteExporter.Result result;
        if (context == null || !callerAllowed(context)) {
            result = new NoteExporter.Result(false, "导出失败：调用方不是本模块", "");
        } else {
            result = NoteExporter.export(context);
        }
        XposedBridge.log(TAG + "export: " + result.message);
        MatrixCursor cursor = new MatrixCursor(ConfigContract.EXPORT_COLUMNS);
        cursor.addRow(new Object[] { result.ok ? 1 : 0, result.message, result.path });
        return cursor;
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
            XposedBridge.log(TAG + "could not resolve the caller: " + t);
        }
        return false;
    }

    /**
     * Fallback path: when the settings screen cannot reach the provider it leaves
     * a request behind and opens the Notes app instead, so the export happens the
     * next time this process starts.
     */
    private static void hookPendingExport() {
        try {
            XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate",
                    Application.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Application app = (Application) param.args[0];
                            if (app != null && TARGET_PKG.equals(app.getPackageName())) {
                                runPendingExport(app);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "could not hook application start: " + t);
        }
    }

    /** Off the main thread: nothing here may slow down starting the Notes app. */
    private static void runPendingExport(final Context context) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                long request = readExportRequest(context);
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

                NoteExporter.Result result = NoteExporter.export(context);
                XposedBridge.log(TAG + "pending export: " + result.message);
                toast(context, result.message);
            }
        }, "note-watermark-export").start();
    }

    private static long readExportRequest(Context context) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    ConfigContract.URI, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(ConfigContract.COLUMN_EXPORT_REQUEST);
                return column >= 0 ? cursor.getLong(column) : 0L;
            }
            XposedBridge.log(TAG + "the module's settings provider answered nothing");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "could not read the export request: " + t);
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
                    XposedBridge.log(TAG + "could not show a toast: " + t);
                }
            }
        });
    }

    private static void applyQuietly(Object activity, String from) {
        try {
            apply(activity, from);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "apply() failed in " + from + ": " + t);
        }
    }

    private static void apply(Object activity, String from) {
        Config config = readConfig(activity);
        String custom = config.watermarkText;

        Object logoLl = field(activity, "mLogoLinearLayout");
        Object line = field(activity, "mLine");
        Object waterMark = field(activity, "mWaterMark");
        Object shareLogo = field(activity, "mShareLogo");
        Object shareLogoOriginal = field(activity, "mShareLogoOriginal");

        if (custom.length() == 0 && !config.keepBlankSpace) {
            // Hide the whole row: the divider line and both text views live inside it,
            // so no empty gap is left at the bottom of the picture.
            setVisibility(logoLl, 8);
            setVisibility(line, 8);
            setVisibility(waterMark, 8);
            setVisibility(shareLogo, 8);
            setVisibility(shareLogoOriginal, 8);
            XposedBridge.log(TAG + from + ": watermark removed");
        } else if (custom.length() == 0) {
            // INVISIBLE keeps the original measured watermark row (roughly two text
            // lines) as clean bottom padding without changing the note itself.
            setVisibility(logoLl, 0);
            setVisibility(line, 4);
            setVisibility(waterMark, 4);
            setVisibility(shareLogo, 4);
            setVisibility(shareLogoOriginal, 4);
            XposedBridge.log(TAG + from + ": blank watermark spacing kept");
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
            XposedBridge.log(TAG + from + ": custom watermark applied");
        }
    }

    private static Config readConfig(Object activity) {
        if (activity instanceof Context) {
            Cursor cursor = null;
            try {
                cursor = ((Context) activity).getContentResolver().query(
                        ConfigContract.URI, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int textColumn = cursor.getColumnIndex(
                            ConfigContract.COLUMN_WATERMARK_TEXT);
                    int spaceColumn = cursor.getColumnIndex(
                            ConfigContract.COLUMN_KEEP_BLANK_SPACE);
                    String text = textColumn >= 0 ? cursor.getString(textColumn) : "";
                    boolean keepSpace = spaceColumn < 0 || cursor.getInt(spaceColumn) != 0;
                    return new Config(text == null ? "" : text.trim(), keepSpace);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + "could not read settings provider: " + t);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        // Compatibility fallback for old installs and frameworks that block the
        // exported settings provider.
        return new Config(readLegacyCustomText(), true);
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

    private static final class Config {
        final String watermarkText;
        final boolean keepBlankSpace;

        Config(String watermarkText, boolean keepBlankSpace) {
            this.watermarkText = watermarkText;
            this.keepBlankSpace = keepBlankSpace;
        }
    }

    private static Object field(Object obj, String name) {
        try {
            return XposedHelpers.getObjectField(obj, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setVisibility(Object view, int visibility) {
        if (view == null) {
            return;
        }
        try {
            view.getClass().getMethod("setVisibility", int.class).invoke(view, visibility);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "setVisibility failed: " + t);
        }
    }

    private static void setText(Object view, CharSequence text) {
        try {
            view.getClass().getMethod("setText", CharSequence.class).invoke(view, text);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "setText failed: " + t);
        }
    }
}
