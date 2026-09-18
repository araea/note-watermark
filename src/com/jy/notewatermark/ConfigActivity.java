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
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.lang.ref.WeakReference;

/**
 * One screen for the one setting this module owns, plus the backup errand.
 *
 * The share preview is the subject: it shows the footer of the picture the Notes
 * app is about to produce, and the segmented control right under it names the three
 * outcomes the hook can actually produce. Choices apply as they are made, so there
 * is no draft state, no save bar and no "are you sure" on the way out.
 */
public final class ConfigActivity extends Activity {
    /** The three states {@link Main} can put the share footer in. */
    private static final int MODE_HIDDEN = 0, MODE_BLANK = 1, MODE_CUSTOM = 2;

    private static final int STATUS_CHECKING = 0, STATUS_ACTIVE = 1,
            STATUS_STALE = 2, STATUS_INACTIVE = 3, STATUS_MISSING = 4;

    /** Long enough that typing is not saved letter by letter, short enough to feel immediate. */
    private static final long SAVE_DELAY_MS = 450L;

    private MaterialStyle ui;
    private SharedPreferences prefs;

    private NestedScrollView scroll;
    private LinearLayout content, statusRow, statusText, previewSheet, previewFooter;
    private CircularProgressIndicator statusSpinner;
    private ImageView statusIcon, exportPrivacyIcon;
    private TextView statusTitle, statusDetail, sampleTitle, sampleBody, previewWatermark,
            modeDetail, exportStatus, exportPrivacy, versionLabel;
    private View previewDivider;
    private MaterialButtonToggleGroup modeGroup;
    private MaterialButton[] modeButtons;
    private TextInputLayout inputLayout;
    private TextInputEditText watermarkInput;
    private MaterialButton exportButton;
    private LinearProgressIndicator exportProgress;

    private boolean resumed;
    private int mode = MODE_BLANK;
    private int status = STATUS_CHECKING;
    private boolean checking;
    private ExportJob exportJob;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable persist = this::persist;

    @Override protected void onCreate(Bundle state) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        ui = new MaterialStyle(this);
        ui.applyWindow();
        prefs = getSharedPreferences(ConfigContract.PREFS, 0);
        setContentView(R.layout.activity_config);
        bind();
        paint();

        exportJob = (ExportJob) getLastNonConfigurationInstance();
        if (exportJob == null) exportJob = new ExportJob(getApplicationContext());
        if (state != null && !exportJob.running) {
            exportJob.message = state.getBoolean("exportRunning")
                    ? getString(R.string.export_interrupted)
                    : state.getString("exportMessage", exportJob.message);
            exportJob.failed = state.getBoolean("exportFailed");
        }
        exportJob.activity = new WeakReference<>(this);

        String saved = prefs.getString(ConfigContract.KEY_WATERMARK_TEXT, "");
        boolean keepBlank = prefs.getBoolean(ConfigContract.KEY_KEEP_BLANK_SPACE, true);
        mode = state != null ? state.getInt("mode")
                : !saved.isEmpty() ? MODE_CUSTOM : keepBlank ? MODE_BLANK : MODE_HIDDEN;
        watermarkInput.setText(state != null ? state.getString("draft", "")
                : saved.isEmpty() ? prefs.getString(ConfigContract.KEY_LAST_CUSTOM_TEXT, "") : saved);
        watermarkInput.setSelection(watermarkInput.length());

        modeGroup.check(modeButtons[mode].getId());
        modeGroup.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            for (int i = 0; i < modeButtons.length; i++) {
                if (modeButtons[i].getId() == id && mode != i) {
                    mode = i;
                    animate();
                    render();
                    save();
                }
            }
        });
        watermarkInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                render();
                save();
            }
            public void afterTextChanged(Editable text) {}
        });
        watermarkInput.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            flush();
            hideKeyboard();
            return true;
        });
        statusRow.setOnClickListener(v -> checkModule());
        exportButton.setOnClickListener(v -> startExport());

        render();
        renderExport();
        renderStatus();
        checkModule();
        if (state != null) scroll.post(() -> scroll.scrollTo(0, state.getInt("scroll")));
    }

    private void bind() {
        scroll = findViewById(R.id.scroll);
        content = findViewById(R.id.content);
        statusRow = findViewById(R.id.statusRow);
        statusText = findViewById(R.id.statusText);
        statusSpinner = findViewById(R.id.statusSpinner);
        statusIcon = findViewById(R.id.statusIcon);
        statusTitle = findViewById(R.id.statusTitle);
        statusDetail = findViewById(R.id.statusDetail);
        previewSheet = findViewById(R.id.previewSheet);
        previewFooter = findViewById(R.id.previewFooter);
        previewDivider = findViewById(R.id.previewDivider);
        previewWatermark = findViewById(R.id.previewWatermark);
        sampleTitle = findViewById(R.id.sampleTitle);
        sampleBody = findViewById(R.id.sampleBody);
        modeGroup = findViewById(R.id.modeGroup);
        modeButtons = new MaterialButton[] {
            findViewById(R.id.modeHidden), findViewById(R.id.modeBlank), findViewById(R.id.modeCustom),
        };
        modeDetail = findViewById(R.id.modeDetail);
        inputLayout = findViewById(R.id.inputLayout);
        watermarkInput = findViewById(R.id.watermarkInput);
        exportButton = findViewById(R.id.exportButton);
        exportProgress = findViewById(R.id.exportProgress);
        exportStatus = findViewById(R.id.exportStatus);
        exportPrivacy = findViewById(R.id.exportPrivacy);
        exportPrivacyIcon = findViewById(R.id.exportPrivacyIcon);
        versionLabel = findViewById(R.id.versionLabel);
    }

    /** Everything a token cannot express in XML: tinted shapes, limits, measured layout. */
    private void paint() {
        previewSheet.setBackground(ui.outlinedShape(ui.sheet, ui.outline, R.style.Shape_Sujian_Sheet));
        previewDivider.setBackgroundColor(ui.outline);
        exportPrivacy.setTextColor(ui.tertiary);
        exportPrivacyIcon.setImageTintList(android.content.res.ColorStateList.valueOf(ui.tertiary));
        watermarkInput.setFilters(new InputFilter[] { new InputFilter.LengthFilter(80) });
        versionLabel.setText(getString(R.string.version_footer, versionName()));
        heading(findViewById(R.id.shareSection));
        heading(findViewById(R.id.exportSection));
        for (View child : new View[] { sampleTitle, sampleBody, previewFooter }) {
            child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        previewSheet.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        exportStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusText.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = Math.min(((ViewGroup) content.getParent()).getWidth(),
                    ui.dimen(R.dimen.content_max_width));
            if (width > 0 && content.getLayoutParams().width != width) {
                content.getLayoutParams().width = width;
                content.requestLayout();
            }
            stackModesIfCramped(r - l - content.getPaddingLeft() - content.getPaddingRight());
        });
    }

    /**
     * Three segments side by side stop being readable long before the text is
     * ellipsised, so at large font sizes or on narrow screens they stack instead.
     */
    private void stackModesIfCramped(int available) {
        if (available <= 0) return;
        float widest = 0;
        for (MaterialButton button : modeButtons) {
            widest = Math.max(widest, button.getPaint().measureText(button.getText().toString()));
        }
        boolean stack = (widest + ui.dimen(R.dimen.segment_padding)) * modeButtons.length > available;
        int orientation = stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL;
        if (modeGroup.getOrientation() == orientation) return;
        modeGroup.setOrientation(orientation);
        for (MaterialButton button : modeButtons) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) button.getLayoutParams();
            params.width = stack ? LinearLayout.LayoutParams.MATCH_PARENT : 0;
            params.weight = stack ? 0 : 1;
            button.setLayoutParams(params);
        }
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) { return ""; }
    }

    private String draft() { return watermarkInput.getText().toString().trim(); }

    // ---------------------------------------------------------------- rendering

    /** The preview mirrors exactly what {@link Main} does to the real footer row. */
    private void render() {
        boolean custom = mode == MODE_CUSTOM;
        boolean written = custom && !draft().isEmpty();
        previewFooter.setVisibility(mode == MODE_HIDDEN ? View.GONE : View.VISIBLE);
        previewDivider.setVisibility(written ? View.VISIBLE : View.INVISIBLE);
        previewWatermark.setVisibility(written ? View.VISIBLE : View.INVISIBLE);
        previewWatermark.setText(written ? draft() : getString(R.string.preview_sample_watermark));

        modeDetail.setText(mode == MODE_HIDDEN ? getString(R.string.mode_hidden_detail)
                : mode == MODE_BLANK ? getString(R.string.mode_blank_detail)
                : written ? getString(R.string.mode_custom_detail)
                : getString(R.string.mode_custom_empty));
        inputLayout.setVisibility(custom ? View.VISIBLE : View.GONE);
        inputLayout.setError(custom && !written ? getString(R.string.watermark_error) : null);
        previewSheet.setContentDescription(getString(R.string.share_section) + "：" + modeDetail.getText()
                + (written ? draft() : ""));
    }

    /**
     * A module that works needs no container: a glyph and a line of text, level with
     * everything else on the page. Only a module that is not working gets a filled
     * surface and a second line saying what to do about it.
     */
    private void renderStatus() {
        boolean quiet = status == STATUS_ACTIVE || status == STATUS_CHECKING;
        int foreground = status == STATUS_CHECKING ? ui.muted
                : status == STATUS_ACTIVE ? ui.tertiary
                : status == STATUS_STALE ? ui.onSecondaryContainer : ui.onErrorContainer;
        statusRow.setBackground(quiet ? null : ui.shape(
                status == STATUS_STALE ? ui.secondaryContainer : ui.errorContainer,
                R.style.Shape_Sujian_Medium));
        int inset = quiet ? 0 : ui.lg;
        statusRow.setPadding(inset, quiet ? 0 : ui.md, inset, quiet ? 0 : ui.md);

        statusSpinner.setVisibility(status == STATUS_CHECKING ? View.VISIBLE : View.GONE);
        statusIcon.setVisibility(status == STATUS_CHECKING ? View.GONE : View.VISIBLE);
        statusIcon.setImageResource(status == STATUS_ACTIVE
                ? R.drawable.ic_status_active : R.drawable.ic_status_alert);
        statusIcon.setImageTintList(android.content.res.ColorStateList.valueOf(foreground));

        statusTitle.setText(status == STATUS_CHECKING ? R.string.status_checking
                : status == STATUS_ACTIVE ? R.string.status_active
                : status == STATUS_STALE ? R.string.status_stale
                : status == STATUS_MISSING ? R.string.status_missing : R.string.status_inactive);
        statusTitle.setTextColor(foreground);
        statusDetail.setTextColor(foreground);
        statusDetail.setVisibility(quiet ? View.GONE : View.VISIBLE);
        if (!quiet) {
            statusDetail.setText(status == STATUS_STALE ? R.string.status_stale_detail
                    : status == STATUS_MISSING ? R.string.status_missing_detail
                    : R.string.status_inactive_detail);
        }
        statusRow.setContentDescription(statusTitle.getText()
                + (quiet ? "" : "。" + statusDetail.getText())
                + "。" + getString(R.string.status_recheck));
    }

    private void renderExport() {
        exportButton.setEnabled(!exportJob.running);
        exportButton.setText(exportJob.running ? R.string.export_running
                : exportJob.failed ? R.string.export_retry : R.string.export_action);
        exportProgress.setVisibility(exportJob.running ? View.VISIBLE : View.GONE);
        exportStatus.setVisibility(exportJob.message.isEmpty() ? View.GONE : View.VISIBLE);
        exportStatus.setText(exportJob.message);
        exportStatus.setTextColor(exportJob.failed ? ui.onErrorContainer : ui.muted);
        exportStatus.setBackground(exportJob.failed
                ? ui.shape(ui.errorContainer, R.style.Shape_Sujian_Small) : null);
        int padding = exportJob.failed ? ui.md : 0;
        exportStatus.setPadding(padding, padding, padding, padding);
    }

    // ------------------------------------------------------------------ saving

    /** Choices apply on their own; the delay only keeps typing out of every keystroke. */
    private void save() {
        main.removeCallbacks(persist);
        main.postDelayed(persist, SAVE_DELAY_MS);
    }

    private void flush() {
        main.removeCallbacks(persist);
        persist();
    }

    private void persist() {
        String watermark = mode == MODE_CUSTOM ? draft() : "";
        boolean keepBlank = mode != MODE_HIDDEN;
        if (watermark.equals(prefs.getString(ConfigContract.KEY_WATERMARK_TEXT, ""))
                && keepBlank == prefs.getBoolean(ConfigContract.KEY_KEEP_BLANK_SPACE, true)) {
            return;
        }
        SharedPreferences.Editor edit = prefs.edit()
                .putString(ConfigContract.KEY_WATERMARK_TEXT, watermark)
                .putBoolean(ConfigContract.KEY_KEEP_BLANK_SPACE, keepBlank);
        if (!watermark.isEmpty()) edit.putString(ConfigContract.KEY_LAST_CUSTOM_TEXT, watermark);
        edit.apply();
        previewSheet.announceForAccessibility(getString(R.string.apply_announcement));
    }

    /** Nothing off-screen needs to animate, and a transition cut short by a stop would
     *  leave the scene root's layout suppressed. */
    private void animate() {
        if (resumed) ui.animate(content);
    }

    private void hideKeyboard() {
        watermarkInput.clearFocus();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(watermarkInput.getWindowToken(), 0);
    }

    @Override protected void onRestart() {
        super.onRestart();
        if (status != STATUS_ACTIVE) checkModule();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
    }

    @Override protected void onPause() {
        resumed = false;
        flush();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("mode", mode);
        out.putString("draft", watermarkInput.getText().toString());
        out.putInt("scroll", scroll.getScrollY());
        out.putString("exportMessage", exportJob.message);
        out.putBoolean("exportRunning", exportJob.running);
        out.putBoolean("exportFailed", exportJob.failed);
        super.onSaveInstanceState(out);
    }

    @Override public Object onRetainNonConfigurationInstance() { return exportJob; }

    @Override protected void onDestroy() {
        main.removeCallbacks(persist);
        if (exportJob.activity.get() == this) exportJob.activity.clear();
        super.onDestroy();
    }

    // ------------------------------------------------------------ module status

    /**
     * Asks the injected code, inside the Notes process, to identify itself. A missing
     * answer means the module is installed but not actually hooking anything.
     */
    private void checkModule() {
        if (checking) return;
        checking = true;
        status = STATUS_CHECKING;
        renderStatus();
        final String expected = versionName();
        final Context context = getApplicationContext();
        new Thread(() -> {
            final int result = probe(context, expected);
            main.post(() -> {
                checking = false;
                if (isFinishing() || isDestroyed()) return;
                status = result;
                animate();
                renderStatus();
            });
        }, "note-watermark-status").start();
    }

    private static int probe(Context context, String expected) {
        try {
            context.getPackageManager().getPackageInfo(ConfigContract.NOTE_PKG, 0);
        } catch (Exception missing) {
            return STATUS_MISSING;
        }
        try (Cursor cursor = context.getContentResolver()
                .query(ConfigContract.STATUS_URI, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return STATUS_INACTIVE;
            int column = cursor.getColumnIndex(ConfigContract.COLUMN_MODULE_VERSION);
            if (column < 0 || cursor.isNull(column)) return STATUS_INACTIVE;
            return expected.equals(cursor.getString(column)) ? STATUS_ACTIVE : STATUS_STALE;
        } catch (Exception unhooked) {
            return STATUS_INACTIVE;
        }
    }

    // ------------------------------------------------------------------ export

    private void startExport() {
        if (exportJob.running) return;
        exportJob.running = true;
        exportJob.failed = false;
        exportJob.message = getString(R.string.export_working);
        animate();
        renderExport();
        final ExportJob job = exportJob;
        new Thread(() -> {
            String result = job.exportThroughNotes();
            main.post(() -> {
                job.message = result;
                job.running = false;
                ConfigActivity activity = job.activity.get();
                if (activity != null && !activity.isFinishing()) {
                    activity.animate();
                    activity.renderExport();
                }
            });
        }, "note-watermark-export").start();
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
            if (intent == null) {
                failed = true;
                return context.getString(R.string.export_missing_notes);
            }
            context.getSharedPreferences(ConfigContract.PREFS, 0).edit()
                    .putLong(ConfigContract.KEY_EXPORT_REQUEST, System.currentTimeMillis()).apply();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { context.startActivity(intent); }
            catch (Exception ignored) {
                failed = true;
                return context.getString(R.string.export_cannot_open);
            }
            return context.getString(R.string.export_requested);
        }
    }

    private void heading(TextView view) {
        if (android.os.Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
    }
}
