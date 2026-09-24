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
import android.widget.Checkable;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Map;

/** Device UI regression checks; never exports the real Notes database. */
public final class DesignSmoke extends Instrumentation {
    private static final int MODE_HIDDEN = 0, MODE_BLANK = 1, MODE_CUSTOM = 2;
    private static final int STATUS_ACTIVE = 1, STATUS_INACTIVE = 3;

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
            checkShareAdapt();
            preferences = getTargetContext().getSharedPreferences("settings", 0);
            original = preferences.getAll();
            preferences.edit().remove("watermark_text").remove("keep_blank_space")
                    .remove("last_custom_text").remove("selected_mode").commit();
            launch();
            runOnMainSync(() -> {
                check(mode() == MODE_BLANK, "unset settings start on the blank-spacing mode");
                check(((Checkable) button(MODE_BLANK)).isChecked(), "the chosen mode is the checked segment");
                check(group().getClass().getName()
                        .equals("com.google.android.material.button.MaterialButtonToggleGroup"),
                        "official MaterialButtonToggleGroup");
                check(view("inputLayout").getVisibility() == View.GONE,
                        "the text field only appears in the custom mode");
                check(view("previewFooter").getVisibility() == View.VISIBLE
                        && view("previewWatermark").getVisibility() == View.INVISIBLE,
                        "blank spacing keeps the footer's height without its text");

                button(MODE_HIDDEN).performClick();
                check(view("previewFooter").getVisibility() == View.GONE, "hidden collapses the footer");
                flush();
                check(!preferences.getBoolean("keep_blank_space", true), "hidden persists by itself");
                check(preferences.getString("watermark_text", "x").isEmpty(), "hidden clears the text");

                button(MODE_CUSTOM).performClick();
                check(view("inputLayout").getVisibility() == View.VISIBLE, "custom reveals the text field");
                check(error() == null, "blank custom text is supported, not marked invalid");
                check(text("modeDetail").getText().toString().contains("留白"),
                        "empty custom explains the actual blank result");
                edit().setText("  M3 Expressive  ");
                check(error() == null, "a written custom line has no validation error");
                check(text("previewWatermark").getText().toString().equals("M3 Expressive"),
                        "the preview trims what it shows");
                check(view("previewDivider").getVisibility() == View.VISIBLE,
                        "a custom watermark brings back the rule above it");
                flush();
                check(preferences.getString("watermark_text", "").equals("M3 Expressive"),
                        "typing persists on its own");

                button(MODE_BLANK).performClick();
                flush();
                check(preferences.getString("watermark_text", "x").isEmpty(),
                        "leaving the custom mode clears the stored watermark");
                check(preferences.getString("last_custom_text", "").equals("M3 Expressive"),
                        "the written line is remembered for next time");
                button(MODE_CUSTOM).performClick();
                check(mode() == MODE_CUSTOM, "picking custom again selects it");
                check(edit().getText().toString().trim().equals("M3 Expressive"),
                        "coming back offers the same line again");
                edit().setText("Draft survives recreation");
                flush();
            });

            // The phone can stop this activity the moment it launches (a lock screen keeps
            // focus), and a stopped activity's bundle is captured before the checks run, so
            // the instance state is asserted on a bundle taken here rather than through the
            // system's own timing. What recreation still proves deterministically is the
            // retained export job, which never travels through a bundle.
            Bundle saved = new Bundle();
            runOnMainSync(() -> {
                callActivityOnSaveInstanceState(activity, saved);
                check(saved.getInt("mode", -1) == MODE_CUSTOM, "instance state records the mode");
                check("Draft survives recreation".equals(saved.getString("draft")),
                        "instance state records the unfinished line");
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
                check(!view("exportButton").isEnabled(),
                        "an in-flight export stays disabled after recreation");
                check(view("exportProgress").getVisibility() == View.VISIBLE,
                        "export progress survives recreation");
                Object job = field(activity, "exportJob");
                set(job, "running", false);
                set(job, "failed", true);
                set(job, "message", "测试错误：请重试");
                invoke(activity, "renderExport");
                check(text("exportButton").getText().toString().equals("重试导出"), "a failure offers a retry");
                check(view("exportButton").isEnabled(), "the retry is reachable");
                set(job, "failed", false);
                set(job, "message", "");
                invoke(activity, "renderExport");
                check(view("exportStatus").getVisibility() == View.GONE, "an empty export state is hidden");
            });
            close();

            // A cold start is what a user actually comes back to: the mode and the line
            // are read back from the settings the screen saved on its own.
            launch();
            runOnMainSync(() -> {
                button(MODE_CUSTOM).performClick();
                edit().setText("回到原处");
                flush();
            });
            close();
            launch();
            runOnMainSync(() -> {
                check(mode() == MODE_CUSTOM, "a cold start reopens on the saved mode");
                check(edit().getText().toString().equals("回到原处"),
                        "a cold start brings the saved line back");
                check(view("inputLayout").getVisibility() == View.VISIBLE,
                        "a cold start reveals the field the mode needs");
                button(MODE_BLANK).performClick();
                flush();
            });
            close();

            launch();
            runOnMainSync(() -> {
                button(MODE_CUSTOM).performClick();
                edit().setText("");
                flush();
            });
            close();
            launch();
            runOnMainSync(() -> {
                check(mode() == MODE_CUSTOM, "empty custom selection survives a cold start");
                check(edit().length() == 0, "empty custom draft stays empty on cold start");
                button(MODE_BLANK).performClick();
                flush();
            });
            close();

            launch();
            capture("light", STATUS_ACTIVE);
            runOnMainSync(() -> {
                button(MODE_CUSTOM).performClick();
                edit().setText("摘自我的便签");
                flush();
            });
            capture("light-custom", STATUS_ACTIVE);
            runOnMainSync(() -> {
                button(MODE_HIDDEN).performClick();
                flush();
            });
            capture("light-hidden", STATUS_ACTIVE);
            runOnMainSync(() -> {
                button(MODE_BLANK).performClick();
                flush();
            });
            close();
            launch();
            capture("light-inactive", STATUS_INACTIVE);
            close();
            dark = true;
            launch();
            capture("dark", STATUS_ACTIVE);
            close();
            dark = false;

            fontScale = 2f;
            density = Math.round(getTargetContext().getResources().getDisplayMetrics().widthPixels / 320f * 160);
            launch();
            runOnMainSync(() -> {
                relayout(activity.getWindow().getDecorView());
                check(group().getOrientation() == LinearLayout.VERTICAL,
                        "the segments stack when three of them no longer fit");
                checkTextBounds(activity.getWindow().getDecorView());
                button(MODE_CUSTOM).performClick();
                edit().setText("ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFG");
                check(edit().length() == 80, "80-character input limit");
                flush();
                relayout(activity.getWindow().getDecorView());
                checkTextBounds(activity.getWindow().getDecorView());
            });
            capture("large-text-320dp", STATUS_ACTIVE);
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

    /** Pins the module state so the review screenshots do not depend on what the phone answers. */
    private void capture(String name, int status) {
        // Pinning first also blocks further probes; the settle lets an in-flight one land,
        // and the second pin then has the screen to itself. The phone's lock screen keeps
        // stopping and restarting this activity, which is what kicks those probes off.
        pin(status);
        settle();
        pin(status);
        settle();
        runOnMainSync(() -> relayout(activity.getWindow().getDecorView()));
        runOnMainSync(() -> {
            ViewGroup scroll = (ViewGroup) view("scroll");
            View content = view("content");
            check(content.getWidth() > 0 && content.getHeight() > 0, "measured " + name);
            if (status == STATUS_ACTIVE) {
                check(view("statusDetail").getVisibility() == View.GONE,
                        "a working module says nothing more");
                check(view("statusRow").getBackground() == null,
                        "a working module needs no container of its own");
                check(view("statusRow").getPaddingLeft() == 0,
                        "and stays level with the rest of the page");
            } else {
                check(view("statusRow").getBackground() != null,
                        "a module that is not working gets a surface of its own");
                check(view("statusDetail").getVisibility() == View.VISIBLE,
                        "and says what to do about it");
            }
            Object ui = field(activity, "ui");
            View viewport = activity.getWindow().getDecorView();
            write(name, viewport, (Integer) field(ui, "surface"));
            write(name + "-page", scroll.getChildAt(0), (Integer) field(ui, "surface"));
            checkContrast(ui, "ink", "surface");
            checkContrast(ui, "muted", "surface");
            checkContrast(ui, "muted", "container");
            checkContrast(ui, "ink", "sheet");
            checkContrast(ui, "muted", "sheet");
            checkContrast(ui, "onPrimary", "primary");
            checkContrast(ui, "onPrimaryContainer", "primaryContainer");
            checkContrast(ui, "onSecondary", "secondary");
            checkContrast(ui, "onSecondaryContainer", "secondaryContainer");
            checkContrast(ui, "onTertiary", "tertiary");
            checkContrast(ui, "onTertiaryContainer", "tertiaryContainer");
            checkContrast(ui, "onErrorContainer", "errorContainer");
            check(view("statusRow").getHeight() >= dp(48), "status refresh target >= 48dp");
            check(view("exportButton").getHeight() >= dp(48), "export target >= 48dp");
            for (int i = 0; i < 3; i++)
                check(button(i).getHeight() >= dp(48), "mode target >= 48dp");
        });
    }

    /**
     * The phone keeps this activity stopped (its lock screen holds focus), and a stopped
     * window never runs a traversal, so a state change would otherwise be screenshotted
     * against the previous layout. Measuring and laying out by hand keeps the review
     * images honest.
     */
    private static void relayout(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.EXACTLY));
        root.layout(root.getLeft(), root.getTop(), root.getRight(), root.getBottom());
    }

    private void pin(int status) {
        runOnMainSync(() -> {
            set(activity, "checking", true);
            set(activity, "status", status);
            invoke(activity, "renderStatus");
        });
    }

    /** Gives the state transition time to land, so a screenshot is not a half-played animation. */
    private void settle() {
        waitForIdleSync();
        try { Thread.sleep(700); } catch (InterruptedException ignored) { }
        waitForIdleSync();
    }

    private void write(String name, View source, int background) {
        if (source.getWidth() <= 0 || source.getHeight() <= 0) return;
        float scale = 600f / source.getWidth();
        Bitmap image = Bitmap.createBitmap(600, Math.round(source.getHeight() * scale), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.scale(scale, scale);
        canvas.drawColor(background);
        source.draw(canvas);
        try {
            File directory = new File(getTargetContext().getFilesDir(), "design-review");
            directory.mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
                image.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        } catch (Exception error) {
            throw new RuntimeException(error);
        } finally {
            image.recycle();
        }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
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
    private LinearLayout group() { return (LinearLayout) view("modeGroup"); }
    private int mode() { return (Integer) field(activity, "mode"); }
    private View button(int index) { return (View) Array.get(field(activity, "modeButtons"), index); }
    private CharSequence error() {
        Object layout = field(activity, "inputLayout");
        try { return (CharSequence) layout.getClass().getMethod("getError").invoke(layout); }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }
    private void flush() { invoke(activity, "flush"); }

    /** Guard against accepting look-alike classes or modifying views outside the footer. */
    private void checkShareAdapt() throws Exception {
        Class<?> adapt = Class.forName("com.jy.notewatermark.ShareAdapt", true,
                getTargetContext().getClassLoader());
        java.lang.reflect.Method inspect = adapt.getDeclaredMethod("inspect", Class.class);
        inspect.setAccessible(true);
        Object target = inspect.invoke(null, ShareFixture.class);
        check(target != null, "share method signatures are recognized");
        java.lang.reflect.Field logo = target.getClass().getDeclaredField("logo");
        logo.setAccessible(true);
        check(logo.get(target) != null, "logo hook is independently available");
        check(inspect.invoke(null, UnrelatedFixture.class) == null,
                "unrelated methods are not hooked by a fuzzy name match");
        java.lang.reflect.Method resolve = adapt.getDeclaredMethod("resolve", Object.class);
        resolve.setAccessible(true);
        ShareFixture fixture = new ShareFixture();
        fixture.mLogoLinearLayout = new LinearLayout(getTargetContext());
        fixture.mWaterMark = new TextView(getTargetContext());
        Object views = resolve.invoke(null, fixture);
        java.lang.reflect.Field row = views.getClass().getDeclaredField("row");
        row.setAccessible(true);
        check(row.get(views) == fixture.mLogoLinearLayout,
                "reflection still resolves the verified share footer");
    }

    public static final class ShareFixture {
        public View mLogoLinearLayout;
        public View mWaterMark;
        public void setLogo() { }
        public void createImageFile(int width, int height, int quality) { }
    }

    public static final class UnrelatedFixture {
        public void saveImage(String path) { }
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        report.append("  ok ").append(message).append('\n');
    }
    private static Object field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private static void invoke(Object target, String name) {
        try { java.lang.reflect.Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(target); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private static void set(Object target, String name, Object value) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
}
