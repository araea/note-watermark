package com.jy.notewatermark;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;
import android.util.Log;
import dalvik.system.DexFile;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Enumeration;

/** Conservative adaptation for relocated Notes share screens. Never hooks unrelated activities. */
final class ShareAdapt {
    private static final String TAG = "NoteWatermark";
    private static final String[] NAMES = {
        "com.nearme.note.activity.edit.SaveImageAndShare",
        "com.coloros.note.activity.edit.SaveImageAndShare",
        "com.oplus.note.activity.edit.SaveImageAndShare",
        "com.nearme.note.feature.share.SaveImageAndShare",
        "com.coloros.note.feature.share.SaveImageAndShare",
        "com.oplus.note.feature.share.SaveImageAndShare",
        "com.nearme.note.share.ui.SaveImageAndShare",
        "com.coloros.note.share.ui.SaveImageAndShare",
        "com.oplus.note.share.ui.SaveImageAndShare"
    };

    static final class Target {
        final Class<?> type;
        final Method logo, image;
        Target(Class<?> type, Method logo, Method image) {
            this.type = type;
            this.logo = logo;
            this.image = image;
        }
    }

    static Target discover(ClassLoader loader) {
        for (String name : NAMES) {
            try {
                Target target = inspect(loader.loadClass(name));
                if (target != null) return target;
            } catch (ClassNotFoundException ignored) {
                // Try other known package locations.
            } catch (Throwable t) {
                Log.w(TAG, "candidate unavailable: " + name, t);
            }
        }
        // Only scan when none of the known classes has a usable hook. Avoid loading
        // every class in the host: class initialization and verification are expensive.
        try {
            Field pathList = fieldDef(loader.getClass(), "pathList");
            if (pathList == null) return null;
            pathList.setAccessible(true);
            Object list = pathList.get(loader);
            if (list == null) return null;
            Field elements = fieldDef(list.getClass(), "dexElements");
            if (elements == null) return null;
            elements.setAccessible(true);
            Object[] entries = (Object[]) elements.get(list);
            if (entries == null) return null;
            int examined = 0;
            for (Object entry : entries) {
                if (entry == null) continue;
                Field dexField = fieldDef(entry.getClass(), "dexFile");
                if (dexField == null) continue;
                dexField.setAccessible(true);
                Object dex = dexField.get(entry);
                if (!(dex instanceof DexFile)) continue;
                Enumeration<String> names = ((DexFile) dex).entries();
                while (names.hasMoreElements()) {
                    String name = names.nextElement();
                    if (!candidateName(name)) continue;
                    if (++examined > 128) {
                        Log.w(TAG, "share class search limit reached");
                        return null;
                    }
                    try {
                        Class<?> type = loader.loadClass(name);
                        // DEX fallback requires structural evidence, not just a similar name.
                        if (!hasWatermarkFields(type)) continue;
                        Target target = inspect(type);
                        if (target != null) return target;
                    } catch (Throwable ignored) {
                        // One incompatible class must not stop the other candidates.
                    }
                }
            }
        } catch (Throwable t) {
            // Some runtimes restrict access to BaseDexClassLoader internals.
            Log.w(TAG, "DEX discovery unavailable: " + t);
        }
        return null;
    }

    private static boolean candidateName(String name) {
        if (!(name.startsWith("com.nearme.note.") || name.startsWith("com.coloros.note.")
                || name.startsWith("com.oplus.note."))) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("saveimage") || lower.contains("shareimage")
                || lower.contains("noteshare") || lower.contains("watermark");
    }

    private static boolean hasWatermarkFields(Class<?> type) {
        boolean logo = false, mark = false;
        for (Field f : type.getDeclaredFields()) {
            String name = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("logo")) logo = true;
            if (name.contains("watermark")) mark = true;
        }
        return logo && mark;
    }

    private static Target inspect(Class<?> type) {
        Method logo = null, image = null;
        for (Method method : type.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            Class<?>[] args = method.getParameterTypes();
            if (args.length == 0 && method.getReturnType() == void.class
                    && (name.equals("setlogo") || name.equals("setwatermark"))) logo = method;
            if (args.length == 3 && args[0] == int.class && args[1] == int.class
                    && args[2] == int.class && name.equals("createimagefile")) image = method;
        }
        return logo == null && image == null ? null : new Target(type, logo, image);
    }

    private static Field fieldDef(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { /* inherited field */ }
        }
        return null;
    }

    /** Resolve only inside the share footer; never match an unrelated note or toolbar. */
    static final class Views {
        View row, line, watermark, share, original;
    }

    static Views resolve(Object owner) {
        Views views = new Views();
        views.row = value(owner, "mLogoLinearLayout");
        views.line = value(owner, "mLine");
        views.watermark = value(owner, "mWaterMark");
        views.share = value(owner, "mShareLogo");
        views.original = value(owner, "mShareLogoOriginal");
        if (owner instanceof Activity) {
            Activity activity = (Activity) owner;
            if (views.row == null) {
                int id = id(activity, "logo_ll");
                if (id != 0) views.row = activity.findViewById(id);
            }
            // IDs are resolved relative to the footer; a generic "line" ID elsewhere
            // in the screen must never be hidden as a watermark separator.
            View row = views.row;
            if (row != null) {
                if (views.line == null) views.line = child(activity, row, "line");
                if (views.watermark == null) views.watermark = textChild(activity, row, "water_mark");
                if (views.share == null) views.share = textChild(activity, row, "share_logo");
                if (views.original == null) views.original = textChild(activity, row, "share_logo_original");
            }
        }
        return views;
    }

    private static View value(Object owner, String name) {
        if (owner == null) return null;
        try {
            Field f = fieldDef(owner.getClass(), name);
            if (f == null) return null;
            f.setAccessible(true);
            Object v = f.get(owner);
            return v instanceof View ? (View) v : null;
        } catch (Throwable ignored) { return null; }
    }

    private static int id(Activity activity, String name) {
        return activity.getResources().getIdentifier(name, "id", activity.getPackageName());
    }

    private static View child(Activity activity, View row, String name) {
        int id = id(activity, name);
        return id == 0 ? null : row.findViewById(id);
    }

    private static TextView textChild(Activity activity, View row, String name) {
        View view = child(activity, row, name);
        return view instanceof TextView ? (TextView) view : null;
    }

    private ShareAdapt() { }
}
