package com.jy.notewatermark;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textfield.TextInputEditText;
import static com.jy.notewatermark.MaterialStyle.Type.*;
import java.lang.ref.WeakReference;

/** One task-focused screen: draft preview, editor, backup, and a persistent save action. */
public final class ConfigActivity extends Activity {
    private MaterialStyle ui;
    private SharedPreferences prefs;
    private EditText watermarkInput;
    private TextInputLayout inputLayout;
    private MaterialSwitch keepBlankSpaceSwitch;
    private TextView draftStatus, previewWatermark, previewCaption, exportStatus, modeLabel;
    private LinearLayout previewFooter, preview;
    private MaterialButton saveButton, clearButton, exportButton;
    private LinearProgressIndicator exportProgress;
    private ExportJob exportJob;
    private ScrollView scroll;

    @Override protected void onCreate(Bundle state) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        ui = new MaterialStyle(this);
        ui.applyWindow();
        prefs = getSharedPreferences(ConfigContract.PREFS, 0);
        exportJob = (ExportJob) getLastNonConfigurationInstance();
        if (exportJob == null) exportJob = new ExportJob(getApplicationContext());
        if (state != null && !exportJob.running) {
            exportJob.message = state.getBoolean("exportRunning")
                    ? "上次导出的状态已中断，请先检查「下载」文件夹，必要时重新导出。"
                    : state.getString("exportMessage", exportJob.message);
            exportJob.failed = state.getBoolean("exportFailed");
        }

        LinearLayout root = column();
        root.setBackgroundColor(ui.surface);
        root.setFocusableInTouchMode(true);
        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("素笺");
        toolbar.setSubtitle("ColorOS 便签 · " + versionName());
        toolbar.setLogo(R.drawable.ic_toolbar_logo);
        toolbar.setLogoAdjustViewBounds(false);
        toolbar.setLogoScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        root.addView(toolbar, params(0));

        LinearLayout content = column();
        content.setPadding(ui.xl, ui.lg, ui.xl, ui.section);
        TextView title = ui.text("分享，只留下内容。", DISPLAY, ui.ink);
        heading(title);
        content.addView(title, params(0));
        content.addView(ui.text("隐藏水印，或留下一句自己的话。", BODY, ui.muted), params(ui.sm));

        preview = panel(ui.primaryContainer, R.style.Shape_Sujian_Preview);
        LinearLayout previewTop = new LinearLayout(this);
        previewTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView previewLabel = ui.text("分享预览", LABEL, ui.onPrimaryContainer);
        previewTop.addView(previewLabel, new LinearLayout.LayoutParams(0, -2, 1));
        modeLabel = ui.text("无水印", CAPTION, ui.primary);
        modeLabel.setPadding(ui.md, ui.sm, ui.md, ui.sm);
        modeLabel.setBackground(ui.shape(ui.surface, R.style.Shape_Sujian_Pill));
        previewTop.addView(modeLabel);
        preview.addView(previewTop, params(0));
        TextView sampleTitle = ui.text("留一点空白，\n给今天的灵感。", HEADLINE, ui.onPrimaryContainer);
        preview.addView(sampleTitle, params(ui.lg));
        TextView sampleBody = ui.text("一些想法，一点日常。\n每一次记录，都保留本来的样子。", BODY, ui.onPrimaryContainer);
        preview.addView(sampleBody, params(ui.sm));
        sampleTitle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sampleBody.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        previewFooter = column();
        previewFooter.setPadding(0, ui.xl, 0, 0);
        previewFooter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        previewWatermark = ui.text("", CAPTION, ui.onPrimaryContainer);
        previewWatermark.setGravity(Gravity.CENTER);
        previewFooter.addView(previewWatermark, params(0));
        preview.addView(previewFooter, params(0));
        content.addView(preview, params(ui.xl));
        previewCaption = ui.text("", CAPTION, ui.muted);
        content.addView(previewCaption, params(ui.sm));

        TextView settingsTitle = ui.text("水印设置", HEADLINE, ui.ink);
        heading(settingsTitle);
        content.addView(settingsTitle, params(ui.section));
        inputLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        inputLayout.setHint("水印文字");
        inputLayout.setHelperText("留空时，分享长图不显示水印");
        inputLayout.setCounterEnabled(true);
        inputLayout.setCounterMaxLength(80);
        inputLayout.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
        watermarkInput = new TextInputEditText(inputLayout.getContext());
        watermarkInput.setId(0x1001);
        watermarkInput.setSaveEnabled(false);
        watermarkInput.setSingleLine(true);
        watermarkInput.setFilters(new InputFilter[] { new InputFilter.LengthFilter(80) });
        watermarkInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        watermarkInput.setText(state == null ? prefs.getString(ConfigContract.KEY_WATERMARK_TEXT, "") : state.getString("draft", ""));
        watermarkInput.setSelection(watermarkInput.length());
        watermarkInput.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) { save(); return true; }
            return false;
        });
        inputLayout.addView(watermarkInput, new LinearLayout.LayoutParams(-1, -2));
        content.addView(inputLayout, params(ui.lg));

        LinearLayout spacingRow = new LinearLayout(this);
        spacingRow.setGravity(Gravity.CENTER_VERTICAL);
        spacingRow.setPadding(ui.lg, ui.md, ui.md, ui.md);
        spacingRow.setBackground(ui.shape(ui.container, R.style.Shape_Sujian_Medium));
        LinearLayout spacingText = column();
        spacingText.addView(ui.text("保留底部留白", TITLE, ui.ink), params(0));
        spacingText.addView(ui.text("水印为空时，留出约两行间距", CAPTION, ui.muted), params(ui.xs));
        spacingText.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        spacingRow.addView(spacingText, new LinearLayout.LayoutParams(0, -2, 1));
        keepBlankSpaceSwitch = new MaterialSwitch(this);
        keepBlankSpaceSwitch.setContentDescription("保留底部留白，水印为空时留出约两行间距");
        keepBlankSpaceSwitch.setMinWidth(ui.dimen(R.dimen.touch_target));
        keepBlankSpaceSwitch.setMinHeight(ui.dimen(R.dimen.touch_target));
        keepBlankSpaceSwitch.setSaveEnabled(false);
        keepBlankSpaceSwitch.setChecked(state == null ? prefs.getBoolean(ConfigContract.KEY_KEEP_BLANK_SPACE, true) : state.getBoolean("keep", true));
        spacingRow.addView(keepBlankSpaceSwitch);
        content.addView(spacingRow, params(ui.lg));
        clearButton = new MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle);
        clearButton.setText("清空水印并保存");
        clearButton.setMinHeight(ui.dimen(R.dimen.touch_target));
        clearButton.setOnClickListener(v -> { watermarkInput.setText(""); save(); });
        content.addView(clearButton, params(ui.sm));

        View divider = new View(this);
        divider.setBackgroundColor(ui.outline);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, ui.dp(1));
        dividerParams.topMargin = ui.xl;
        content.addView(divider, dividerParams);
        TextView exportTitle = ui.text("给记录，留一份备份", HEADLINE, ui.ink);
        heading(exportTitle);
        content.addView(exportTitle, params(ui.xl));
        content.addView(ui.text("按分类整理为 ZIP，保存到系统「下载」文件夹。包含文本、HTML 与附件。", BODY, ui.muted), params(ui.sm));
        TextView privacy = ui.text("加密便签会自动跳过", CAPTION, ui.onTertiaryContainer);
        privacy.setPadding(ui.md, ui.sm, ui.md, ui.sm);
        privacy.setBackground(ui.shape(ui.tertiaryContainer, R.style.Shape_Sujian_Small));
        LinearLayout.LayoutParams privacyParams = params(ui.md);
        privacyParams.width = -2;
        content.addView(privacy, privacyParams);
        exportButton = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonTonalStyle);
        exportButton.setText("导出全部便签");
        exportButton.setMinHeight(ui.dimen(R.dimen.touch_target));
        exportButton.setOnClickListener(v -> startExport());
        content.addView(exportButton, params(ui.lg));
        exportProgress = (LinearProgressIndicator) getLayoutInflater().inflate(R.layout.export_progress, content, false);
        content.addView(exportProgress, params(ui.md));
        exportStatus = ui.text("", BODY, ui.muted);
        exportStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(exportStatus, params(ui.sm));
        content.addView(ui.text("在模块管理器中启用素笺后，重新启动 ColorOS 便签。作用域已固定，无需手动添加。", CAPTION, ui.muted), params(ui.xl));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setId(0x1002);
        FrameLayout centered = new FrameLayout(this);
        centered.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        scroll.addView(centered, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        FrameLayout dock = new FrameLayout(this);
        dock.setBackgroundColor(ui.low);
        LinearLayout actions = column();
        actions.setPadding(ui.xl, ui.md, ui.xl, ui.md);
        draftStatus = ui.text("", CAPTION, ui.muted);
        draftStatus.setGravity(Gravity.CENTER);
        actions.addView(draftStatus, params(0));
        saveButton = (MaterialButton) getLayoutInflater().inflate(R.layout.save_button, actions, false);
        saveButton.setOnClickListener(v -> save());
        actions.addView(saveButton, params(ui.xs));
        dock.addView(actions, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL));
        root.addView(dock, params(0));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = Math.min(r - l, ui.dimen(R.dimen.content_max_width));
            for (View bounded : new View[] { content, actions }) {
                if (bounded.getLayoutParams().width != width) {
                    bounded.getLayoutParams().width = width;
                    bounded.requestLayout();
                }
            }
        });
        setContentView(root);
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
        int visibility = blank && !keepBlankSpaceSwitch.isChecked() ? View.GONE : View.VISIBLE;
        if (previewFooter.getVisibility() != visibility) ui.animateLayout(preview);
        previewFooter.setVisibility(visibility);
        modeLabel.setText(blank ? "无水印" : "自定义");
        previewWatermark.setText(blank ? " " : value);
        previewCaption.setText(blank ? (keepBlankSpaceSwitch.isChecked() ? "不显示水印 · 保留底部留白" : "不显示水印 · 收起底部区域") : "自定义文字 · 以便签实际分享效果为准");
        previewCaption.setContentDescription("示意预览：" + (blank ? previewCaption.getText() : "水印为" + value + "，以便签实际分享效果为准"));
        boolean changed = dirty();
        draftStatus.setText(changed ? "有未保存的修改 · 保存后应用到分享长图" : "设置已保存 · 重新打开便签分享页后生效");
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
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("保存这次修改？")
                .setMessage("水印设置尚未保存。保存后，重新打开便签分享页即可生效。")
                .setPositiveButton("保存并退出", (d, which) -> { save(); finish(); })
                .setNegativeButton("放弃修改", (d, which) -> finish())
                .setNeutralButton("继续编辑", null).create();
        dialog.show();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("draft", watermarkInput.getText().toString());
        out.putBoolean("keep", keepBlankSpaceSwitch.isChecked());
        out.putInt("scroll", scroll.getScrollY());
        out.putString("exportMessage", exportJob.message);
        out.putBoolean("exportRunning", exportJob.running);
        out.putBoolean("exportFailed", exportJob.failed);
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
        exportJob.failed = false;
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
        exportStatus.setTextColor(exportJob.failed ? ui.onErrorContainer : ui.muted);
        exportStatus.setBackground(exportJob.failed ? ui.shape(ui.errorContainer, R.style.Shape_Sujian_Small) : null);
        int padding = exportJob.failed ? ui.md : 0;
        exportStatus.setPadding(padding, padding, padding, padding);
        if (exportJob.failed) exportButton.setText("重试导出");
        exportStatus.setVisibility(exportJob.message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Retained worker only holds application context; rotation neither leaks nor duplicates export. */
    private static final class ExportJob {
        final Context context;
        WeakReference<ConfigActivity> activity = new WeakReference<>(null);
        boolean running, failed;
        String message = "";
        ExportJob(Context context) { this.context = context; }

        String exportThroughNotes() {
            try (Cursor cursor = context.getContentResolver().query(ConfigContract.EXPORT_URI, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int column = cursor.getColumnIndex(ConfigContract.COLUMN_EXPORT_MESSAGE);
                    if (column >= 0 && !cursor.isNull(column)) {
                        int ok = cursor.getColumnIndex(ConfigContract.COLUMN_EXPORT_OK);
                        failed = ok >= 0 && cursor.getInt(ok) == 0;
                        return cursor.getString(column);
                    }
                }
            } catch (Exception ignored) { /* The hook may need a cold start. */ }
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(ConfigContract.NOTE_PKG);
            if (intent == null) { failed = true; return "未找到 ColorOS 便签，请先安装便签应用。"; }
            context.getSharedPreferences(ConfigContract.PREFS, 0).edit()
                    .putLong(ConfigContract.KEY_EXPORT_REQUEST, System.currentTimeMillis()).apply();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { context.startActivity(intent); }
            catch (Exception ignored) { failed = true; return "无法打开便签。请手动打开便签，并确认模块已启用。"; }
            return "已请求导出。请留意便签内的完成提示；若无响应，请确认模块已启用并重新启动便签。";
        }
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout panel(int color, int shape) {
        LinearLayout v = column();
        v.setPadding(ui.xl, ui.lg, ui.xl, ui.xl);
        v.setBackground(ui.shape(color, shape));
        return v;
    }

    private LinearLayout.LayoutParams params(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = top;
        return p;
    }

    private void heading(TextView view) {
        if (android.os.Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
    }
}
