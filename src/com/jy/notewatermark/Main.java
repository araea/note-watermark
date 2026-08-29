package com.jy.notewatermark;

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
 * Custom text: put the text you want in one of CONFIG_PATHS. An absent, empty
 * or blank file means "remove the watermark completely".
 */
public class Main implements IXposedHookLoadPackage {

    private static final String TAG = "[NoteWatermark] ";
    private static final String TARGET_PKG = "com.coloros.note";
    private static final String TARGET_CLASS = "com.nearme.note.activity.edit.SaveImageAndShare";

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

    private static void applyQuietly(Object activity, String from) {
        try {
            apply(activity, from);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "apply() failed in " + from + ": " + t);
        }
    }

    private static void apply(Object activity, String from) {
        String custom = readCustomText();

        Object logoLl = field(activity, "mLogoLinearLayout");
        Object waterMark = field(activity, "mWaterMark");
        Object shareLogo = field(activity, "mShareLogo");
        Object shareLogoOriginal = field(activity, "mShareLogoOriginal");

        if (custom == null) {
            // Hide the whole row: the divider line and both text views live inside it,
            // so no empty gap is left at the bottom of the picture.
            setVisibility(logoLl, 8);
            setVisibility(waterMark, 8);
            setVisibility(shareLogo, 8);
            setVisibility(shareLogoOriginal, 8);
            XposedBridge.log(TAG + from + ": watermark removed");
        } else {
            // "ColorOS" half goes away, the app-name half carries the custom text.
            setVisibility(waterMark, 8);
            setVisibility(logoLl, 0);
            if (shareLogo != null) {
                setText(shareLogo, custom);
                setVisibility(shareLogo, 0);
            }
            if (shareLogoOriginal != null) {
                setText(shareLogoOriginal, custom);
                setVisibility(shareLogoOriginal, 0);
            }
            XposedBridge.log(TAG + from + ": watermark replaced with \"" + custom + "\"");
        }
    }

    /** Returns the custom watermark text, or null when the watermark should just go away. */
    private static String readCustomText() {
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
        return null;
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
