package com.jy.notewatermark.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Map;

/** Device UI smoke test, isolated from QQ and the real Notes database. */
public final class DesignSmoke extends Instrumentation {
    private boolean dark;
    private float fontScale = 1f;
    private int density;
    private Activity activity;
    private SharedPreferences preferences;
    private Map<String, ?> original;
    private final StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public Activity newActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Activity activity = super.newActivity(loader, name, intent);
        Configuration config = new Configuration();
        config.uiMode = dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        config.fontScale = fontScale;
        if (density != 0) config.densityDpi = density;
        activity.applyOverrideConfiguration(config);
        return activity;
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            preferences = getTargetContext().getSharedPreferences("settings", 0);
            original = preferences.getAll();
            preferences.edit().remove("watermark_text").remove("keep_blank_space").commit();
            launch();
            runOnMainSync(() -> {
                check(!view("saveButton").isEnabled(), "clean settings disable save");
                edit().setText("  M3 Expressive  ");
                check(view("saveButton").isEnabled(), "draft enables save");
                check(text("previewWatermark").getText().toString().equals("M3 Expressive"), "live trimmed preview");
                view("saveButton").performClick();
                check(preferences.getString("watermark_text", "").equals("M3 Expressive"), "save persists trimmed text");
                view("clearButton").performClick();
                check(preferences.getString("watermark_text", "x").isEmpty(), "clear persists empty watermark");
                toggle().setChecked(false);
                check(view("previewFooter").getVisibility() == View.GONE, "empty footer collapses");
                toggle().setChecked(true);
                check(view("previewFooter").getVisibility() == View.VISIBLE, "blank spacing returns");
                edit().setText("Draft survives recreation");
                Object job = field(activity, "exportJob");
                set(job, "running", true);
                set(job, "message", "正在整理便签与附件，请稍候…");
            });
            ActivityMonitor monitor = addMonitor("com.jy.notewatermark.ConfigActivity", null, false);
            runOnMainSync(() -> activity.recreate());
            Activity replacement = monitor.waitForActivityWithTimeout(10000);
            check(replacement != null, "activity recreated");
            activity = replacement;
            removeMonitor(monitor);
            waitForIdleSync();
            runOnMainSync(() -> {
                check(edit().getText().toString().equals("Draft survives recreation"), "draft survives recreation");
                check(!view("exportButton").isEnabled(), "in-flight export stays disabled after recreation");
                check(view("exportProgress").getVisibility() == View.VISIBLE, "export progress survives recreation");
                Object job = field(activity, "exportJob");
                set(job, "running", false);
                set(job, "message", "");
                view("clearButton").performClick();
            });
            close();
            launch();
            capture("light");
            close();
            dark = true;
            launch();
            capture("dark");
            close();
            dark = false;
            fontScale = 2f;
            density = Math.round(getTargetContext().getResources().getDisplayMetrics().widthPixels / 320f * 160);
            launch();
            runOnMainSync(() -> {
                checkTextBounds(activity.getWindow().getDecorView());
                edit().setText("ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFG");
                check(edit().length() == 80, "80-character input limit");
                view("saveButton").performClick();
            });
            capture("large-text-320dp");
            close();
            result.putString("stream", "\n" + report + "PASS: expressive settings UI\n");
            restore();
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            try { if (activity != null) close(); restore(); } catch (Throwable ignored) {}
            result.putString("stream", "\n" + report + "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void launch() {
        Intent intent = new Intent().setClassName(getTargetContext(), "com.jy.notewatermark.ConfigActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity = startActivitySync(intent);
        waitForIdleSync();
    }

    private void close() { runOnMainSync(() -> activity.finish()); waitForIdleSync(); activity = null; }

    private void restore() {
        if (original == null) return;
        SharedPreferences.Editor edit = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : original.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) edit.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) edit.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Long) edit.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Integer) edit.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Float) edit.putFloat(entry.getKey(), (Float) value);
        }
        edit.commit();
    }

    private void capture(String name) {
        waitForIdleSync();
        runOnMainSync(() -> {
            ScrollView scroll = (ScrollView) view("scroll");
            View content = scroll.getChildAt(0);
            check(content.getWidth() > 0 && content.getHeight() > 0, "measured " + name);
            float scale = 600f / content.getWidth();
            Bitmap image = Bitmap.createBitmap(600, Math.round(content.getHeight() * scale), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            canvas.scale(scale, scale);
            Object ui = field(activity, "ui");
            canvas.drawColor((Integer) field(ui, "surface"));
            content.draw(canvas);
            checkContrast(ui, "ink", "surface");
            checkContrast(ui, "muted", "container");
            checkContrast(ui, "onPrimary", "primary");
            checkContrast(ui, "onPrimaryContainer", "primaryContainer");
            try {
                File directory = new File(getTargetContext().getFilesDir(), "design-review");
                directory.mkdirs();
                try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
                    image.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                image.recycle();
            } catch (Exception error) { throw new RuntimeException(error); }
        });
    }

    private void checkContrast(Object ui, String foreground, String background) {
        double a = luminance((Integer) field(ui, foreground));
        double b = luminance((Integer) field(ui, background));
        double ratio = (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
        check(ratio >= 4.5, foreground + "/" + background + " text contrast >= 4.5:1");
    }

    private static double luminance(int color) {
        double result = 0;
        double[] weights = {0.2126, 0.7152, 0.0722};
        for (int i = 0; i < 3; i++) {
            double channel = ((color >> (16 - i * 8)) & 255) / 255.0;
            channel = channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
            result += channel * weights[i];
        }
        return result;
    }

    private void checkTextBounds(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView && !(view instanceof EditText)) {
            TextView text = (TextView) view;
            if (text.getLayout() != null) {
                check(text.getLayout().getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom(),
                        "no vertical clipping: " + text.getText());
                for (int line = 0; line < text.getLayout().getLineCount(); line++)
                    check(text.getLayout().getEllipsisCount(line) == 0, "no truncated labels");
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) checkTextBounds(group.getChildAt(i));
        }
    }

    private View view(String name) { return (View) field(activity, name); }
    private TextView text(String name) { return (TextView) view(name); }
    private EditText edit() { return (EditText) view("watermarkInput"); }
    private Switch toggle() { return (Switch) view("keepBlankSpaceSwitch"); }
    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        report.append("  ok ").append(message).append('\n');
    }
    private static Object field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private static void set(Object target, String name, Object value) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
}
