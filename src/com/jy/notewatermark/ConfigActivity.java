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
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.listitem.ListItemCardView;
import com.google.android.material.listitem.ListItemLayout;
import com.google.android.material.loadingindicator.LoadingIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.shape.MaterialShapes;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One screen for the one setting this module owns, plus the backup errand.
 *
 * The share preview is the subject: it shows the footer of the picture the Notes
 * app is about to produce, and the radio list right under it names the three
 * outcomes the hook can actually produce, each with what it does. Choices apply as
 * they are made, so there is no draft state, no save bar and no "are you sure" on
 * the way out.
 */
public final class ConfigActivity extends Activity {
    /** The three states {@link Main} can put the share footer in. */
    private static final int MODE_HIDDEN = 0, MODE_BLANK = 1, MODE_CUSTOM = 2;

    private static final int STATUS_CHECKING = 0, STATUS_ACTIVE = NotesBridge.ACTIVE,
            STATUS_STALE = NotesBridge.STALE, STATUS_INACTIVE = NotesBridge.INACTIVE,
            STATUS_MISSING = NotesBridge.MISSING;

    /** One queue for every hand-off to Notes, so a later setting never lands before an earlier one. */
    private static final ExecutorService BRIDGE = Executors.newSingleThreadExecutor();

    /** Long enough that typing is not saved letter by letter, short enough to feel immediate. */
    private static final long SAVE_DELAY_MS = 450L;

    private MaterialStyle ui;
    private SharedPreferences prefs;

    private NestedScrollView scroll;
    private LinearLayout content, shareGroup, statusText, previewSheet, previewFooter;
    private ListItemCardView statusCard, previewCard;
    private ChoiceItem[] modeItems;
    private LoadingIndicator statusLoading;
    private ImageView statusIcon, statusAction, exportPrivacyIcon;
    private TextView statusTitle, statusDetail, previewLabel, sampleTitle, sampleBody, previewWatermark,
            exportStatus, exportPrivacy, versionLabel;
    private View previewDivider, inputItem;
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
        int legacyMode = !saved.isEmpty() ? MODE_CUSTOM : keepBlank ? MODE_BLANK : MODE_HIDDEN;
        int selectedMode = prefs.getInt(ConfigContract.KEY_SELECTED_MODE, legacyMode);
        mode = state != null ? state.getInt("mode", legacyMode)
                : selectedMode >= MODE_HIDDEN && selectedMode <= MODE_CUSTOM ? selectedMode : legacyMode;
        String lastCustom = prefs.getString(ConfigContract.KEY_LAST_CUSTOM_TEXT, "");
        watermarkInput.setText(state != null ? state.getString("draft", "")
                : !saved.isEmpty() ? saved : mode == MODE_CUSTOM ? "" : lastCustom);
        watermarkInput.setSelection(watermarkInput.length());

        for (int i = 0; i < modeItems.length; i++) {
            final int choice = i;
            modeItems[i].setOnClickListener(v -> choose(choice));
        }
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
        statusCard.setOnClickListener(v -> checkModule());
        exportButton.setOnClickListener(v -> startExport());

        render();
        renderExport();
        renderStatus();
        segments(shareGroup);
        checkModule();
        if (state != null) scroll.post(() -> scroll.scrollTo(0, state.getInt("scroll")));
    }

    private void bind() {
        scroll = findViewById(R.id.scroll);
        content = findViewById(R.id.content);
        statusCard = findViewById(R.id.statusCard);
        statusText = findViewById(R.id.statusText);
        statusLoading = findViewById(R.id.statusLoading);
        statusIcon = findViewById(R.id.statusIcon);
        statusAction = findViewById(R.id.statusAction);
        statusTitle = findViewById(R.id.statusTitle);
        statusDetail = findViewById(R.id.statusDetail);
        shareGroup = findViewById(R.id.shareGroup);
        previewCard = findViewById(R.id.previewCard);
        previewSheet = findViewById(R.id.previewSheet);
        previewFooter = findViewById(R.id.previewFooter);
        previewDivider = findViewById(R.id.previewDivider);
        previewWatermark = findViewById(R.id.previewWatermark);
        previewLabel = findViewById(R.id.previewLabel);
        sampleTitle = findViewById(R.id.sampleTitle);
        sampleBody = findViewById(R.id.sampleBody);
        modeItems = new ChoiceItem[] {
            findViewById(R.id.modeHidden), findViewById(R.id.modeBlank), findViewById(R.id.modeCustom),
        };
        inputItem = findViewById(R.id.inputItem);
        inputLayout = findViewById(R.id.inputLayout);
        watermarkInput = findViewById(R.id.watermarkInput);
        exportButton = findViewById(R.id.exportButton);
        exportProgress = findViewById(R.id.exportProgress);
        exportStatus = findViewById(R.id.exportStatus);
        exportPrivacy = findViewById(R.id.exportPrivacy);
        exportPrivacyIcon = findViewById(R.id.exportPrivacyIcon);
        versionLabel = findViewById(R.id.versionLabel);
    }

    /** Everything a token cannot express in XML: tinted shapes, limits, accessibility roles. */
    private void paint() {
        previewSheet.setBackground(ui.shape(ui.paper, R.style.Shape_Sujian_Paper));
        previewDivider.setBackgroundColor(ui.outline);
        exportPrivacyIcon.setImageTintList(ColorStateList.valueOf(ui.muted));
        watermarkInput.setFilters(new InputFilter[] { new InputFilter.LengthFilter(80) });
        versionLabel.setText(getString(R.string.version_footer, versionName()));
        heading(findViewById(R.id.shareSection));
        heading(findViewById(R.id.exportSection));
        ((ListItemLayout) findViewById(R.id.statusItem)).updateAppearance(0, 1);
        ((ListItemLayout) findViewById(R.id.exportItem)).updateAppearance(0, 1);

        // The preview is one summary node; its sample text is not read out line by line.
        previewCard.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        previewCard.setFocusable(true);
        ((View) previewLabel.getParent()).setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        statusText.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        statusCard.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        exportStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        findViewById(R.id.exportPrivacyRow).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        // The three cards are one single-choice list: each announces its position, the way
        // a RadioGroup would (the radio role itself comes from ChoiceItem).
        shareGroup.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(modeItems.length, 1, false,
                        AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE));
            }
        });
        for (int i = 0; i < modeItems.length; i++) {
            final int row = i;
            ChoiceItem item = modeItems[i];
            item.setCheckable(true);
            item.setClickable(true);
            item.setFocusable(true);
            item.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(
                            row, 1, 0, 1, false, mode == row));
                }
            });
        }
        content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = Math.min(((ViewGroup) content.getParent()).getWidth(),
                    ui.dimen(R.dimen.content_max_width));
            if (width > 0 && content.getLayoutParams().width != width) {
                content.getLayoutParams().width = width;
                content.requestLayout();
            }
        });
    }

    /** Gives each visible item of a segmented list its first / middle / last shape. */
    private static void segments(LinearLayout group) {
        int count = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i).getVisibility() != View.GONE) count++;
        }
        int position = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            ((ListItemLayout) child).updateAppearance(position++, count);
        }
    }

    private void choose(int choice) {
        if (mode == choice) return;
        mode = choice;
        animate();
        render();
        save();
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

        for (int i = 0; i < modeItems.length; i++) modeItems[i].setChecked(mode == i);
        if ((inputItem.getVisibility() == View.VISIBLE) != custom) {
            inputItem.setVisibility(custom ? View.VISIBLE : View.GONE);
            segments(shareGroup);
        }
        // An empty custom line is a supported blank footer, not a validation error.
        inputLayout.setHelperText(written ? null : getString(R.string.mode_custom_empty));
        String outcome = mode == MODE_HIDDEN ? getString(R.string.mode_hidden_detail)
                : mode == MODE_BLANK || !written ? getString(R.string.mode_blank_detail)
                : getString(R.string.mode_custom_detail) + "：" + draft();
        previewCard.setContentDescription(getString(R.string.preview_label) + "，"
                + getString(R.string.share_section) + "：" + outcome);
    }

    /**
     * A module that works needs no container: a glyph and a line of text, level with
     * everything else on the page. Only a module that is not working gets a filled
     * surface and a second line saying what to do about it.
     */
    private void renderStatus() {
        boolean checkingNow = status == STATUS_CHECKING;
        boolean quiet = checkingNow || status == STATUS_ACTIVE;
        boolean stale = status == STATUS_STALE;
        int surface = quiet ? ui.card : stale ? ui.tertiaryContainer : ui.errorContainer;
        int onSurface = quiet ? ui.ink : stale ? ui.onTertiaryContainer : ui.onErrorContainer;
        int onSurfaceVariant = quiet ? ui.muted : onSurface;
        statusCard.setCardBackgroundColor(surface);
        statusTitle.setTextColor(onSurface);
        statusDetail.setTextColor(onSurfaceVariant);
        statusAction.setImageTintList(ColorStateList.valueOf(onSurfaceVariant));

        statusLoading.setVisibility(checkingNow ? View.VISIBLE : View.GONE);
        statusIcon.setVisibility(checkingNow ? View.GONE : View.VISIBLE);
        statusAction.setVisibility(checkingNow ? View.INVISIBLE : View.VISIBLE);
        if (!checkingNow) {
            boolean active = status == STATUS_ACTIVE;
            statusIcon.setBackground(ui.badge(active ? MaterialShapes.COOKIE_9 : MaterialShapes.SUNNY,
                    active ? ui.primaryContainer : stale ? ui.tertiary : ui.error));
            statusIcon.setImageResource(active ? R.drawable.ic_status_active
                    : stale ? R.drawable.ic_refresh : R.drawable.ic_status_alert);
            statusIcon.setImageTintList(ColorStateList.valueOf(
                    active ? ui.onPrimaryContainer : stale ? ui.onTertiary : ui.onError));
        }

        statusTitle.setText(checkingNow ? R.string.status_checking
                : status == STATUS_ACTIVE ? R.string.status_active
                : stale ? R.string.status_stale
                : status == STATUS_MISSING ? R.string.status_missing : R.string.status_inactive);
        statusDetail.setText(checkingNow ? getString(R.string.status_checking_detail)
                : status == STATUS_ACTIVE ? getString(R.string.status_active_detail, versionName())
                : getString(stale ? R.string.status_stale_detail
                        : status == STATUS_MISSING ? R.string.status_missing_detail
                        : R.string.status_inactive_detail));
        statusCard.setEnabled(!checkingNow);
        statusCard.setContentDescription(statusTitle.getText() + "。" + statusDetail.getText()
                + (checkingNow ? "" : "。" + getString(R.string.status_recheck)));
    }

    private void renderExport() {
        exportButton.setEnabled(!exportJob.running);
        exportButton.setIconResource(exportJob.failed ? R.drawable.ic_refresh : R.drawable.ic_export);
        exportButton.setText(exportJob.running ? R.string.export_running
                : exportJob.failed ? R.string.export_retry : R.string.export_action);
        exportProgress.setVisibility(exportJob.running ? View.VISIBLE : View.GONE);
        exportStatus.setVisibility(exportJob.message.isEmpty() ? View.GONE : View.VISIBLE);
        exportStatus.setText(exportJob.message);
        exportStatus.setTextColor(exportJob.failed ? ui.onErrorContainer : ui.muted);
        exportStatus.setBackground(exportJob.failed
                ? ui.shape(ui.errorContainer, R.style.Shape_Sujian_Notice) : null);
        int padding = exportJob.failed ? ui.dimen(R.dimen.space_md) : 0;
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
                && keepBlank == prefs.getBoolean(ConfigContract.KEY_KEEP_BLANK_SPACE, true)
                && mode == prefs.getInt(ConfigContract.KEY_SELECTED_MODE, -1)) {
            return;
        }
        SharedPreferences.Editor edit = prefs.edit()
                .putString(ConfigContract.KEY_WATERMARK_TEXT, watermark)
                .putBoolean(ConfigContract.KEY_KEEP_BLANK_SPACE, keepBlank)
                .putInt(ConfigContract.KEY_SELECTED_MODE, mode);
        if (!watermark.isEmpty()) edit.putString(ConfigContract.KEY_LAST_CUSTOM_TEXT, watermark);
        edit.apply();
        push();
    }

    /** Hands the saved settings to the Notes process; a changed answer updates the status quietly. */
    private void push() {
        final Context context = getApplicationContext();
        BRIDGE.execute(() -> {
            final int result = NotesBridge.sync(context);
            main.post(() -> {
                if (checking || isFinishing() || isDestroyed() || status == result) return;
                status = result;
                animate();
                renderStatus();
            });
        });
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
     * Asks the injected code, inside the Notes process, to identify itself, and hands it
     * the settings on the way. A missing answer means the module is installed but not
     * actually hooking anything.
     */
    private void checkModule() {
        if (checking) return;
        checking = true;
        status = STATUS_CHECKING;
        renderStatus();
        final Context context = getApplicationContext();
        BRIDGE.execute(() -> {
            final int result = NotesBridge.sync(context);
            main.post(() -> {
                checking = false;
                if (isFinishing() || isDestroyed()) return;
                status = result;
                animate();
                renderStatus();
            });
        });
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
