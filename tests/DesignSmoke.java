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
    private static final int STATUS_ACTIVE = 1, STATUS_INACTIVE = 3, STATUS_IDLE = 5, STATUS_SCOPE = 6;

    private boolean dark;
    private float fontScale = 1f;
    private int density;
    private Activity activity;
    private int displayId = -1;
    private SharedPreferences preferences;
    private Map<String, ?> original;
    private final StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        // `am instrument -e display N` runs the screens on another display, e.g. a virtual one.
        String display = arguments == null ? null : arguments.getString("display");
        if (display != null) displayId = Integer.parseInt(display);
        start();
    }

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
            checkExportLinks();
            checkPaperShare();
            preferences = getTargetContext().getSharedPreferences("settings", 0);
            original = preferences.getAll();
            preferences.edit().remove("watermark_text").remove("keep_blank_space")
                    .remove("last_custom_text").remove("selected_mode").commit();
            launch();
            runOnMainSync(() -> {
                check(mode() == MODE_BLANK, "unset settings start on the blank-spacing mode");
                check(((Checkable) button(MODE_BLANK)).isChecked(), "the chosen mode is the checked item");
                check(!((Checkable) button(MODE_HIDDEN)).isChecked()
                        && !((Checkable) button(MODE_CUSTOM)).isChecked(), "only one item is checked");
                check(button(MODE_BLANK).getClass().getSuperclass().getName()
                        .equals("com.google.android.material.listitem.ListItemCardView"),
                        "choices are official M3E segmented list items");
                android.view.accessibility.AccessibilityNodeInfo node =
                        button(MODE_BLANK).createAccessibilityNodeInfo();
                check("android.widget.RadioButton".contentEquals(node.getClassName())
                        && node.isCheckable() && node.isChecked(),
                        "a choice is announced as a checked radio button");
                check(node.getCollectionItemInfo() != null && node.getCollectionItemInfo().getRowIndex() == 1,
                        "a choice announces its place in the list");
                check(view("inputItem").getVisibility() == View.GONE,
                        "the text field only appears in the custom mode");
                check(hasState(button(MODE_CUSTOM), "state_last"),
                        "without the field the custom choice closes the group");
                check(hasState(view("previewCard"), "state_first"), "the preview opens the group");
                check(view("previewFooter").getVisibility() == View.VISIBLE
                        && view("previewWatermark").getVisibility() == View.INVISIBLE,
                        "blank spacing keeps the footer's height without its text");

                button(MODE_HIDDEN).performClick();
                check(view("previewFooter").getVisibility() == View.GONE, "hidden collapses the footer");
                flush();
                check(!preferences.getBoolean("keep_blank_space", true), "hidden persists by itself");
                check(preferences.getString("watermark_text", "x").isEmpty(), "hidden clears the text");

                button(MODE_CUSTOM).performClick();
                check(view("inputItem").getVisibility() == View.VISIBLE, "custom reveals the text field");
                check(hasState(button(MODE_CUSTOM), "state_middle"),
                        "with the field shown the custom choice moves to the middle");
                check(hasState(view("inputItem"), "state_last"), "and the field closes the group");
                check(error() == null, "blank custom text is supported, not marked invalid");
                check(String.valueOf(helper()).contains("留白"),
                        "empty custom explains the actual blank result");
                edit().setText("  M3 Expressive  ");
                check(error() == null, "a written custom line has no validation error");
                check(helper() == null, "a written line needs no helper");
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
                for (View button : exportButtons())
                    check(!button.isEnabled(), "an in-flight export stays disabled after recreation");
                check(view("exportProgress").getVisibility() == View.VISIBLE,
                        "export progress survives recreation");
                Object job = field(activity, "exportJob");
                set(job, "running", false);
                set(job, "failed", true);
                set(job, "app", app("NOTES"));
                set(job, "message", "测试错误：请重试");
                invoke(activity, "renderExport");
                check(((TextView) exportButton(1)).getText().toString().equals("重试导出便签"),
                        "a failure offers a retry on the app that failed");
                check(((TextView) exportButton(0)).getText().toString().equals("导出全部笔记"),
                        "the other app's button is left as it was");
                check(exportButton(1).isEnabled(), "the retry is reachable");
                set(job, "failed", false);
                set(job, "app", null);
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
                check(view("inputItem").getVisibility() == View.VISIBLE,
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
            launch();
            capture("light-idle", STATUS_IDLE);
            capture("light-scope", STATUS_SCOPE);
            runOnMainSync(() -> check(((TextView) field(((Object[]) field(activity, "rows"))[0], "title"))
                    .getText().toString().contains("作用域"), "the scope row says what is missing"));
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
            restore();
            result.putString("stream", "\n" + report + "PASS: expressive settings UI\n");
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
        activity = displayId < 0 ? startActivitySync(intent)
                : startActivitySync(intent, android.app.ActivityOptions.makeBasic()
                        .setLaunchDisplayId(displayId).toBundle());
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
        // The screens under test handed their test settings to Notes; hand back the real ones.
        try {
            Class<?> bridge = Class.forName("com.jy.notewatermark.NotesBridge", true,
                    getTargetContext().getClassLoader());
            Class<?> apps = Class.forName("com.jy.notewatermark.NotesApp", true,
                    getTargetContext().getClassLoader());
            java.lang.reflect.Method sync = bridge.getDeclaredMethod("sync", android.content.Context.class, apps);
            sync.setAccessible(true);
            java.lang.reflect.Field all = apps.getDeclaredField("ALL");
            all.setAccessible(true);
            for (Object app : (Object[]) all.get(null))
                report.append("  restored settings synced to ").append(((String) field(app, "pkg")))
                        .append(": ").append(sync.invoke(null, getTargetContext(), app)).append('\n');
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
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
            Object ui = field(activity, "ui");
            for (Object row : rows()) {
                if (!(Boolean) field(row, "installed")) continue;
                int card = ((android.content.res.ColorStateList) invokeResult(field(row, "card"),
                        "getCardBackgroundColor")).getDefaultColor();
                if (status == STATUS_ACTIVE || status == STATUS_IDLE) {
                    check(card == (Integer) field(ui, "card"), "a working module sits on the plain list surface");
                } else if (status == STATUS_SCOPE) {
                    check(card == (Integer) field(ui, "tertiaryContainer"),
                            "a missing scope is flagged without the weight of an error");
                } else {
                    check(card == (Integer) field(ui, "errorContainer"),
                            "a module that is not working turns its item into an error container");
                }
                check(((View) field(row, "detail")).getVisibility() == View.VISIBLE, "the status says what it means");
                check(((View) field(row, "loading")).getVisibility() == View.GONE, "a settled status stops loading");
                check(((View) field(row, "card")).getHeight() >= dp(48), "status refresh target >= 48dp");
            }
            View viewport = activity.getWindow().getDecorView();
            write(name, viewport, (Integer) field(ui, "page"));
            write(name + "-page", scroll.getChildAt(0), (Integer) field(ui, "page"));
            checkContrast(ui, "ink", "card");
            checkContrast(ui, "muted", "card");
            checkContrast(ui, "muted", "page");
            checkContrast(ui, "primary", "page");
            checkContrast(ui, "ink", "paper");
            checkContrast(ui, "muted", "paper");
            checkContrast(ui, "onPrimary", "primary");
            checkContrast(ui, "onPrimaryContainer", "primaryContainer");
            checkContrast(ui, "onSecondaryContainer", "secondaryContainer");
            checkContrast(ui, "onTertiary", "tertiary");
            checkContrast(ui, "onTertiaryContainer", "tertiaryContainer");
            checkContrast(ui, "onError", "error");
            checkContrast(ui, "onErrorContainer", "errorContainer");
            for (View button : exportButtons())
                check(button.getHeight() >= dp(48), "export target >= 48dp");
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
            for (Object row : rows()) {
                set(row, "checking", true);
                set(row, "status", (Boolean) field(row, "installed") ? status : 4);
            }
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
    private Object[] rows() { return (Object[]) field(activity, "rows"); }
    private View exportButton(int index) { return (View) Array.get(field(activity, "exportButtons"), index); }
    private View[] exportButtons() {
        Object[] buttons = (Object[]) field(activity, "exportButtons");
        java.util.List<View> shown = new java.util.ArrayList<>();
        for (Object button : buttons) if (((View) button).getVisibility() == View.VISIBLE) shown.add((View) button);
        check(!shown.isEmpty(), "an export button is shown for an installed app");
        return shown.toArray(new View[0]);
    }
    private Object app(String name) {
        try {
            java.lang.reflect.Field field = Class.forName("com.jy.notewatermark.NotesApp", true,
                    getTargetContext().getClassLoader()).getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (Exception error) { throw new RuntimeException(error); }
    }
    private TextView text(String name) { return (TextView) view(name); }
    private EditText edit() { return (EditText) view("watermarkInput"); }
    private int mode() { return (Integer) field(activity, "mode"); }
    private View button(int index) { return (View) Array.get(field(activity, "modeItems"), index); }
    private CharSequence helper() {
        Object layout = field(activity, "inputLayout");
        try { return (CharSequence) layout.getClass().getMethod("getHelperText").invoke(layout); }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }
    /** ListItemLayout marks segment positions with the framework's state_first / middle / last. */
    private boolean hasState(View view, String name) {
        int attr = android.content.res.Resources.getSystem().getIdentifier(name, "attr", "android");
        check(attr != 0, "resolved " + name);
        for (int state : view.getDrawableState()) if (state == attr) return true;
        return false;
    }
    private CharSequence error() {
        Object layout = field(activity, "inputLayout");
        try { return (CharSequence) layout.getClass().getMethod("getError").invoke(layout); }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }
    private void flush() { invoke(activity, "flush"); }

    /** Exported pages must show their pictures from the archive, not from the Notes app. */
    private void checkExportLinks() throws Exception {
        Class<?> exporter = Class.forName("com.jy.notewatermark.NoteExporter", true,
                getTargetContext().getClassLoader());
        java.lang.reflect.Method id = exporter.getDeclaredMethod("attachmentId", String.class);
        id.setAccessible(true);
        check("2a57-cb8e".equals(id.invoke(null, "2a57-cb8e_thumb.png")), "thumbnail maps to its attachment id");
        check("f3eb".equals(id.invoke(null, "f3eb.paint")), "a drawing maps to its attachment id");
        java.lang.reflect.Method link = exporter.getDeclaredMethod("linkAttachments",
                String.class, String.class, Map.class);
        link.setAccessible(true);
        Map<String, String> files = new java.util.HashMap<>();
        files.put("2a57-cb8e", "2a57-cb8e_thumb.png");
        String html = "<img src=\"2a57-cb8e\" width=\"1\"/><img src=\"gone\"><a src=\"2a57-cb8e\">";
        String linked = (String) link.invoke(null, html, "001_今天 灵感_附件/", files);
        check(linked.contains("src=\"001_%E4%BB%8A%E5%A4%A9%20%E7%81%B5%E6%84%9F_%E9%99%84%E4%BB%B6/2a57-cb8e_thumb.png\""),
                "a picture points at its copy beside the page");
        check(linked.contains("<img src=\"gone\">"), "an unknown picture is left as it was");
        check(linked.endsWith("<a src=\"2a57-cb8e\">"), "only img sources are rewritten");
    }

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

    /** The newer app's share surfaces: which style leaves the legacy footer alone, and what the page is told. */
    private void checkPaperShare() throws Exception {
        ClassLoader loader = getTargetContext().getClassLoader();
        Class<?> adapt = Class.forName("com.jy.notewatermark.ShareAdapt", true, loader);
        java.lang.reflect.Method own = adapt.getDeclaredMethod("paintsOwnFooter", Object.class);
        own.setAccessible(true);
        SimpleFixture simple = new SimpleFixture();
        check((Boolean) own.invoke(null, simple), "the simple style is recognized as painting its own footer");
        simple.mCurrentShareStyle = Style.CLASSIC;
        check(!(Boolean) own.invoke(null, simple), "the other styles keep using the legacy footer");
        check(!(Boolean) own.invoke(null, new ShareFixture()), "the older app has no such style");
        check(!(Boolean) own.invoke(null, (Object) null), "nothing is not a style");

        Class<?> config = Class.forName("com.jy.notewatermark.FooterConfig", true, loader);
        java.lang.reflect.Constructor<?> make = config.getDeclaredConstructor(String.class, boolean.class);
        make.setAccessible(true);
        Class<?> paper = Class.forName("com.jy.notewatermark.PaperShare", true, loader);
        java.lang.reflect.Method script = paper.getDeclaredMethod("script", config);
        script.setAccessible(true);
        String hidden = (String) script.invoke(null, make.newInstance("", false));
        String blank = (String) script.invoke(null, make.newInstance("", true));
        String custom = (String) script.invoke(null, make.newInstance("a\"</script>'b", true));
        check(hidden.contains("mode:\"hidden\"") && blank.contains("mode:\"blank\"")
                && custom.contains("mode:\"custom\""), "the page is told which of the three outcomes to show");
        check(custom.contains("a\\\"<\\/script>'b"), "a custom line is escaped into the script, not spliced");
        check(hidden.contains("--share-watermark-height:0px"), "hiding also gives back the room the footer reserved");
        check(blank.contains("visibility:hidden") && !blank.contains("--share-watermark-height:0px"),
                "blank keeps the room");
    }

    public enum Style { SIMPLE, CLASSIC }

    public static final class SimpleFixture {
        Style mCurrentShareStyle = Style.SIMPLE;
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
    private static Object invokeResult(Object target, String name) {
        try { return target.getClass().getMethod(name).invoke(target); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private static void set(Object target, String name, Object value) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
}
