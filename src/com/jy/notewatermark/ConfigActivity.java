package com.jy.notewatermark;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;

/** Native Material 3 Expressive settings: preview drafts, explicitly save, export separately. */
public final class ConfigActivity extends Activity {
    private MaterialStyle ui;
    private SharedPreferences prefs;
    private EditText watermarkInput;
    private Switch keepBlankSpaceSwitch;
    private TextView draftStatus, previewWatermark, previewCaption, counter, exportStatus;
    private LinearLayout previewFooter;
    private Button saveButton, clearButton, exportButton;
    private ExpressiveProgress exportProgress;
    private ExportJob exportJob;
    private ScrollView scroll;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new MaterialStyle(this);
        ui.applyWindow();
        setTitle(getApplicationInfo().loadLabel(getPackageManager()));
        prefs = getSharedPreferences(ConfigContract.PREFS, 0);
        exportJob = (ExportJob) getLastNonConfigurationInstance();
        if (exportJob == null) exportJob = new ExportJob(getApplicationContext());
        if (state != null && !exportJob.running) exportJob.message = state.getBoolean("exportRunning")
                ? "上次导出的状态已中断，请先检查「下载」文件夹，必要时重新导出。"
                : state.getString("exportMessage", exportJob.message);

        LinearLayout content = column();
        content.setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(32));
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(icon, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        TextView eyebrow = ui.text("COLOROS 便签", 12, ui.muted, true);
        eyebrow.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams eyebrowParams = params(0);
        eyebrowParams.width = 0;
        eyebrowParams.weight = 1;
        eyebrowParams.leftMargin = ui.dp(12);
        brand.addView(eyebrow, eyebrowParams);
        TextView version = ui.text(versionName(), 12, ui.muted, true);
        version.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        version.setBackground(ui.shape(ui.container, 20));
        brand.addView(version);
        content.addView(brand, params(0));
        TextView title = ui.text("让分享，\n只留下内容。", 36, ui.ink, true);
        heading(title);
        content.addView(title, params(24));
        content.addView(ui.text("隐藏水印，或留下一句自己的话。", 14, ui.muted, false), params(6));

        LinearLayout preview = card(ui.primaryContainer, 32);
        TextView previewLabel = ui.text("分享效果 · 示意预览", 12, ui.onPrimaryContainer, true);
        preview.addView(previewLabel, params(0));
        LinearLayout paper = card(ui.surface, 16);
        TextView sampleTitle = ui.text("把日常，写成值得珍藏的片段", 18, ui.ink, true);
        paper.addView(sampleTitle, params(0));
        paper.addView(ui.text("一些想法，一点灵感。\n让每一次记录，都保留本来的样子。", 14, ui.muted, false), params(8));
        previewFooter = column();
        previewFooter.setPadding(0, ui.dp(20), 0, 0);
        previewWatermark = ui.text("", 12, ui.muted, false);
        previewWatermark.setGravity(Gravity.CENTER);
        previewFooter.addView(previewWatermark, params(0));
        paper.addView(previewFooter, params(0));
        // The summary below describes the preview once; decorative sample text is skipped by TalkBack.
        paper.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        preview.addView(paper, params(16));
        previewCaption = ui.text("", 12, ui.onPrimaryContainer, false);
        preview.addView(previewCaption, params(12));
        content.addView(preview, params(24));

        LinearLayout settings = card(ui.container, 24);
        TextView settingTitle = ui.text("水印设置", 20, ui.ink, true);
        heading(settingTitle);
        settings.addView(settingTitle, params(0));
        TextView label = ui.text("水印文字", 14, ui.muted, true);
        settings.addView(label, params(20));
        watermarkInput = new EditText(this);
        watermarkInput.setId(0x1001);
        label.setLabelFor(watermarkInput.getId());
        watermarkInput.setSaveEnabled(false); // Save draft explicitly across recreation.
        watermarkInput.setSingleLine(true);
        watermarkInput.setTextSize(16);
        watermarkInput.setTextColor(ui.ink);
        watermarkInput.setHintTextColor(ui.muted);
        watermarkInput.setHint("留空，不显示水印");
        watermarkInput.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        watermarkInput.setMinimumHeight(ui.dp(56));
        watermarkInput.setBackground(ui.shape(ui.surface, 12, ui.outline, 1));
        watermarkInput.setFilters(new InputFilter[] { new InputFilter.LengthFilter(80) });
        watermarkInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        watermarkInput.setText(state == null ? prefs.getString(ConfigContract.KEY_WATERMARK_TEXT, "") : state.getString("draft", ""));
        watermarkInput.setSelection(watermarkInput.length());
        watermarkInput.setOnFocusChangeListener((v, focused) -> watermarkInput.setBackground(
                ui.shape(ui.surface, 12, focused ? ui.primary : ui.outline, focused ? 2 : 1)));
        watermarkInput.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) { save(); return true; }
            return false;
        });
        settings.addView(watermarkInput, params(8));
        counter = ui.text("", 12, ui.muted, false);
        counter.setGravity(Gravity.END);
        settings.addView(counter, params(4));

        keepBlankSpaceSwitch = new Switch(this);
        keepBlankSpaceSwitch.setText("保留底部留白");
        keepBlankSpaceSwitch.setTextSize(16);
        keepBlankSpaceSwitch.setTextColor(ui.ink);
        keepBlankSpaceSwitch.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        keepBlankSpaceSwitch.setMinHeight(ui.dp(64));
        keepBlankSpaceSwitch.setPadding(ui.dp(16), ui.dp(8), ui.dp(12), ui.dp(8));
        keepBlankSpaceSwitch.setBackground(ui.shape(ui.surface, 20));
        keepBlankSpaceSwitch.setSwitchPadding(ui.dp(16));
        GradientDrawable track = ui.shape(ui.high, 16, ui.outline, 2);
        track.setSize(ui.dp(52), ui.dp(32));
        keepBlankSpaceSwitch.setTrackDrawable(track);
        keepBlankSpaceSwitch.setTrackTintList(ui.states(ui.primary, ui.outline));
        GradientDrawable thumb = ui.shape(ui.onPrimary, 12);
        thumb.setSize(ui.dp(24), ui.dp(24));
        keepBlankSpaceSwitch.setThumbDrawable(new InsetDrawable(thumb, ui.dp(4)));
        keepBlankSpaceSwitch.setThumbTintList(ui.states(ui.onPrimary, ui.surface));
        keepBlankSpaceSwitch.setSplitTrack(false);
        keepBlankSpaceSwitch.setSaveEnabled(false);
        keepBlankSpaceSwitch.setChecked(state == null ? prefs.getBoolean(ConfigContract.KEY_KEEP_BLANK_SPACE, true) : state.getBoolean("keep", true));
        settings.addView(keepBlankSpaceSwitch, params(12));
        settings.addView(ui.text("仅在水印为空时生效。开启后保留约两行间距；关闭后收起水印区域。正文不受影响。", 14, ui.muted, false), params(8));
        draftStatus = ui.text("", 12, ui.muted, true);
        settings.addView(draftStatus, params(20));
        saveButton = ui.button("保存设置", true);
        saveButton.setOnClickListener(v -> save());
        settings.addView(saveButton, params(12));
        clearButton = ui.button("清空水印并保存", false);
        clearButton.setOnClickListener(v -> { watermarkInput.setText(""); save(); });
        settings.addView(clearButton, params(8));
        settings.addView(ui.text("保存后，重新打开便签分享页即可生效。", 12, ui.muted, false), params(12));
        content.addView(settings, params(16));

        LinearLayout export = card(ui.container, 24);
        TextView exportTitle = ui.text("给记录，留一份备份", 20, ui.ink, true);
        heading(exportTitle);
        export.addView(exportTitle, params(0));
        export.addView(ui.text("全部便签按分类整理为 ZIP，保存到系统「下载」文件夹。包含文本、HTML 与附件。", 14, ui.muted, false), params(8));
        TextView privacy = ui.text("加密便签会自动跳过", 12, ui.muted, true);
        export.addView(privacy, params(12));
        exportButton = ui.button("导出全部便签", false);
        exportButton.setOnClickListener(v -> startExport());
        export.addView(exportButton, params(20));
        exportProgress = new ExpressiveProgress(ui);
        export.addView(exportProgress, params(12));
        exportStatus = ui.text("", 12, ui.muted, false);
        exportStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        export.addView(exportStatus, params(8));
        content.addView(export, params(16));
        TextView help = ui.text("使用前，请在模块管理器中启用本模块，\n勾选 ColorOS 便签并重新启动便签。", 12, ui.muted, false);
        help.setGravity(Gravity.CENTER);
        content.addView(help, params(24));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(ui.surface);
        scroll.setClipToPadding(false);
        scroll.setId(0x1002);
        FrameLayout centered = new FrameLayout(this);
        centered.setFocusableInTouchMode(true);
        centered.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        scroll.addView(centered, new ScrollView.LayoutParams(-1, -2));
        // A readable column on tablets and landscape; phone layouts fill the available width.
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = Math.min(r - l, ui.dp(600));
            if (content.getLayoutParams().width != width) {
                content.getLayoutParams().width = width;
                content.requestLayout();
            }
        });
        setContentView(scroll);
        watermarkInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { updateDraft(); }
            public void afterTextChanged(Editable text) {}
        });
        keepBlankSpaceSwitch.setOnCheckedChangeListener((v, checked) -> updateDraft());
        updateDraft();
        exportJob.activity = new WeakReference<>(this);
        updateExport();
        if (state != null) scroll.post(() -> scroll.scrollTo(0, state.getInt("scroll")));
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) { return ""; }
    }

    private boolean dirty() {
        return !watermarkInput.getText().toString().trim().equals(prefs.getString(ConfigContract.KEY_WATERMARK_TEXT, ""))
                || keepBlankSpaceSwitch.isChecked() != prefs.getBoolean(ConfigContract.KEY_KEEP_BLANK_SPACE, true);
    }

    private void updateDraft() {
        String value = watermarkInput.getText().toString().trim();
        boolean blank = value.isEmpty();
        previewFooter.setVisibility(blank && !keepBlankSpaceSwitch.isChecked() ? View.GONE : View.VISIBLE);
        previewWatermark.setText(blank ? " " : value);
        previewCaption.setText(blank ? (keepBlankSpaceSwitch.isChecked() ? "不显示水印 · 保留底部留白" : "不显示水印 · 收起底部区域") : "自定义文字 · 以便签实际分享效果为准");
        previewCaption.setContentDescription("示意预览：" + (blank ? previewCaption.getText() : "水印为" + value + "，以便签实际分享效果为准"));
        counter.setText(watermarkInput.length() + " / 80");
        counter.setContentDescription("已输入 " + watermarkInput.length() + "，最多 80 个字符");
        boolean changed = dirty();
        draftStatus.setText(changed ? "有未保存的修改" : "设置已保存");
        draftStatus.setTextColor(changed ? ui.primary : ui.muted);
        saveButton.setEnabled(changed);
        saveButton.setText(changed ? "保存设置" : "已保存");
        clearButton.setEnabled(watermarkInput.length() > 0);
    }

    private void save() {
        String watermark = watermarkInput.getText().toString().trim();
        prefs.edit().putString(ConfigContract.KEY_WATERMARK_TEXT, watermark)
                .putBoolean(ConfigContract.KEY_KEEP_BLANK_SPACE, keepBlankSpaceSwitch.isChecked()).apply();
        watermarkInput.setText(watermark);
        watermarkInput.setSelection(watermark.length());
        watermarkInput.clearFocus();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(watermarkInput.getWindowToken(), 0);
        updateDraft();
        draftStatus.announceForAccessibility("设置已保存，重新打开便签分享页后生效");
        Toast.makeText(this, "已保存，重新打开便签分享页后生效", Toast.LENGTH_SHORT).show();
    }

    @Override public void onBackPressed() {
        if (!dirty()) { super.onBackPressed(); return; }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("保存这次修改？")
                .setMessage("水印设置尚未保存。保存后，重新打开便签分享页即可生效。")
                .setPositiveButton("保存并退出", (d, which) -> { save(); finish(); })
                .setNegativeButton("放弃修改", (d, which) -> finish())
                .setNeutralButton("继续编辑", null).create();
        dialog.setOnShowListener(d -> {
            dialog.getWindow().setBackgroundDrawable(ui.shape(ui.container, 28));
            for (int button : new int[] { -1, -2, -3 }) dialog.getButton(button).setTextColor(ui.primary);
        });
        dialog.show();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("draft", watermarkInput.getText().toString());
        out.putBoolean("keep", keepBlankSpaceSwitch.isChecked());
        out.putInt("scroll", scroll.getScrollY());
        out.putString("exportMessage", exportJob.message);
        out.putBoolean("exportRunning", exportJob.running);
        super.onSaveInstanceState(out);
    }

    @Override public Object onRetainNonConfigurationInstance() { return exportJob; }

    @Override protected void onDestroy() {
        if (exportJob.activity.get() == this) exportJob.activity.clear();
        super.onDestroy();
    }

    private void startExport() {
        if (exportJob.running) return;
        exportJob.running = true;
        exportJob.message = "正在整理便签与附件，请稍候…";
        updateExport();
        final ExportJob job = exportJob;
        new Thread(() -> {
            String result = job.exportThroughNotes();
            new Handler(Looper.getMainLooper()).post(() -> {
                job.message = result;
                job.running = false;
                ConfigActivity activity = job.activity.get();
                if (activity != null && !activity.isFinishing()) activity.updateExport();
            });
        }, "note-watermark-export").start();
    }

    private void updateExport() {
        exportButton.setEnabled(!exportJob.running);
        exportButton.setText(exportJob.running ? "正在导出…" : "导出全部便签");
        exportProgress.setVisibility(exportJob.running ? View.VISIBLE : View.GONE);
        exportStatus.setText(exportJob.message);
        exportStatus.setVisibility(exportJob.message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Retained worker only holds application context; rotation neither leaks nor duplicates export. */
    private static final class ExportJob {
        final Context context;
        WeakReference<ConfigActivity> activity = new WeakReference<>(null);
        boolean running;
        String message = "";
        ExportJob(Context context) { this.context = context; }

        String exportThroughNotes() {
            try (Cursor cursor = context.getContentResolver().query(ConfigContract.EXPORT_URI, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int column = cursor.getColumnIndex(ConfigContract.COLUMN_EXPORT_MESSAGE);
                    if (column >= 0 && !cursor.isNull(column)) return cursor.getString(column);
                }
            } catch (Exception ignored) { /* The hook may need a cold start. */ }
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(ConfigContract.NOTE_PKG);
            if (intent == null) return "未找到 ColorOS 便签，请先安装便签应用。";
            context.getSharedPreferences(ConfigContract.PREFS, 0).edit()
                    .putLong(ConfigContract.KEY_EXPORT_REQUEST, System.currentTimeMillis()).apply();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { context.startActivity(intent); }
            catch (Exception ignored) { return "无法打开便签。请手动打开便签，并确认模块已启用。"; }
            return "已请求导出。请留意便签内的完成提示；若无响应，请确认模块已启用并重新启动便签。";
        }
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout card(int color, int radius) {
        LinearLayout v = column();
        v.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20));
        v.setBackground(ui.shape(color, radius));
        return v;
    }

    private LinearLayout.LayoutParams params(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = ui.dp(top);
        return p;
    }

    private void heading(TextView view) {
        if (android.os.Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
    }
}
