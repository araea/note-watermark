package com.jy.notewatermark;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Html;
import android.text.InputFilter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.text.method.LinkMovementMethod;
import android.text.style.BulletSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TextAppearanceSpan;
import android.util.TypedValue;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.listitem.ListItemCardView;
import com.google.android.material.listitem.ListItemLayout;
import com.google.android.material.loadingindicator.LoadingIndicator;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.shape.MaterialShapes;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One screen for the one setting this module owns, plus the backup errand.
 *
 * The share preview is the subject: it shows the footer of the picture the notes
 * apps are about to produce, and the radio list right under it names the three
 * outcomes the hook can actually produce, each with what it does. Choices apply as
 * they are made, so there is no draft state, no save bar and no "are you sure" on
 * the way out. The setting is shared by both apps (「笔记」 and 「便签」); each gets its
 * own status line and its own export button, for the ones that are installed.
 *
 * Updates stay out of the way until there is one: then a card above everything says what
 * it brings, and one button carries it from download to the system installer.
 */
public final class ConfigActivity extends Activity {
    /** Set by the update notification's button: start updating as soon as the screen is up. */
    static final String EXTRA_UPDATE = "update";
    private static final String MARKET_PAGE = "https://modules.lsposed.org/module/com.jy.notewatermark/";
    /** Screens started and not yet stopped; the daily job keeps quiet while one is on show. */
    private static int started;

    static boolean visible() { return started > 0; }

    /** The three states {@link Main} can put the share footer in. */
    private static final int MODE_HIDDEN = 0, MODE_BLANK = 1, MODE_CUSTOM = 2;

    private static final int STATUS_CHECKING = 0, STATUS_ACTIVE = NotesBridge.ACTIVE,
            STATUS_STALE = NotesBridge.STALE, STATUS_INACTIVE = NotesBridge.INACTIVE,
            STATUS_MISSING = NotesBridge.MISSING, STATUS_IDLE = NotesBridge.IDLE,
            STATUS_SCOPE = NotesBridge.NEEDS_SCOPE;

    /** One queue for every hand-off to the apps, so a later setting never lands before an earlier one. */
    private static final ExecutorService BRIDGE = Executors.newSingleThreadExecutor();

    /** Long enough that typing is not saved letter by letter, short enough to feel immediate. */
    private static final long SAVE_DELAY_MS = 450L;

    private MaterialStyle ui;
    private SharedPreferences prefs;

    private NestedScrollView scroll;
    private LinearLayout content, shareGroup, statusGroup, previewSheet, previewFooter;
    private ListItemCardView previewCard;
    private ChoiceItem[] modeItems;
    private ImageView exportPrivacyIcon;
    private TextView previewLabel, sampleTitle, sampleBody, previewWatermark,
            exportStatus, exportPrivacy;
    private View previewDivider, inputItem;
    private TextInputLayout inputLayout;
    private TextInputEditText watermarkInput;
    private MaterialButton[] exportButtons;
    private LinearProgressIndicator exportProgress;
    /** One per app, in {@link NotesApp#ALL} order; only the installed ones are shown. */
    private StatusRow[] rows;

    private Updater updater;
    private Updater.State update;
    private View updateItem;
    private LinearLayout updateGroup;
    private ListItemCardView updateCard, versionCard;
    private SwitchItem autoCard;
    private MaterialSwitch autoSwitch;
    private ImageView updateIcon, versionIcon;
    private LoadingIndicator versionLoading;
    private TextView updateTitle, updateDetail, updateMeta, updateStatus, versionTitle, versionDetail, autoDetail;
    private LinearProgressIndicator updateProgress;
    private MaterialButton updateAction, updateNotes;
    private BottomSheetDialog sheet;
    /** The confirmation screen already started, so a repeated snapshot does not open it twice. */
    private Intent confirmShown;
    private final Updater.Listener updateListener = this::updated;

    private boolean resumed;
    private int mode = MODE_BLANK;
    private ExportJob exportJob;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable persist = this::persist;
    /** The framework's binder can arrive after the screen is up: hand it the settings and look again. */
    private final Remote.Listener remoteListener = () -> main.post(() -> {
        if (isFinishing() || isDestroyed()) return;
        publish();
        checkModule();
    });

    @Override protected void onCreate(Bundle state) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        ui = new MaterialStyle(this);
        ui.applyWindow();
        prefs = getSharedPreferences(ConfigContract.PREFS, 0);
        updater = Updater.get(this);
        update = updater.state();
        Remote.listen(remoteListener);
        Remote.init();
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
            exportJob.app = NotesApp.forPackage(state.getString("exportApp", ""));
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
        for (StatusRow row : rows) {
            row.card.setOnClickListener(v -> {
                if (row.status == STATUS_SCOPE) requestScope(row); else check(row);
            });
        }
        for (int i = 0; i < exportButtons.length; i++) {
            final NotesApp app = NotesApp.ALL[i];
            exportButtons[i].setOnClickListener(v -> startExport(app));
        }
        versionCard.setOnClickListener(v -> updater.check(true));
        autoCard.setOnClickListener(v -> {
            updater.setAuto(!updater.auto());
            updater.check(false);
            renderUpdate();
        });
        updateAction.setOnClickListener(v -> act());
        updateNotes.setOnClickListener(v -> showNotes());

        render();
        renderExport();
        renderStatus();
        renderUpdate();
        segments(shareGroup);
        segments(statusGroup);
        segments(updateGroup);
        checkModule();
        if (state != null) scroll.post(() -> scroll.scrollTo(0, state.getInt("scroll")));

        updater.listen(updateListener);
        UpdateJob.schedule(this, updater.auto());
        // The screen says it all from here; the notification has done its job.
        UpdateJob.clear(this);
        updater.check(false);
        if (state == null) follow(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        follow(intent);
    }

    /** The notification's 「立即更新」: the user has already decided. */
    private void follow(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_UPDATE, false)) return;
        intent.removeExtra(EXTRA_UPDATE);
        UpdateJob.clear(this);
        updater.update();
    }

    private void bind() {
        scroll = findViewById(R.id.scroll);
        content = findViewById(R.id.content);
        statusGroup = findViewById(R.id.statusGroup);
        rows = new StatusRow[] {
            new StatusRow(NotesApp.PAPER, findViewById(R.id.statusPaper)),
            new StatusRow(NotesApp.NOTES, findViewById(R.id.statusNotes)),
        };
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
        exportButtons = new MaterialButton[] {
            findViewById(R.id.exportPaper), findViewById(R.id.exportNotes),
        };
        exportProgress = findViewById(R.id.exportProgress);
        exportStatus = findViewById(R.id.exportStatus);
        exportPrivacy = findViewById(R.id.exportPrivacy);
        exportPrivacyIcon = findViewById(R.id.exportPrivacyIcon);
        updateItem = findViewById(R.id.updateItem);
        updateCard = findViewById(R.id.updateCard);
        updateIcon = findViewById(R.id.updateIcon);
        updateTitle = findViewById(R.id.updateTitle);
        updateDetail = findViewById(R.id.updateDetail);
        updateMeta = findViewById(R.id.updateMeta);
        updateProgress = findViewById(R.id.updateProgress);
        updateStatus = findViewById(R.id.updateStatus);
        updateAction = findViewById(R.id.updateAction);
        updateNotes = findViewById(R.id.updateNotes);
        updateGroup = findViewById(R.id.updateGroup);
        versionCard = findViewById(R.id.versionCard);
        versionIcon = findViewById(R.id.versionIcon);
        versionLoading = findViewById(R.id.versionLoading);
        versionTitle = findViewById(R.id.versionTitle);
        versionDetail = findViewById(R.id.versionDetail);
        autoCard = findViewById(R.id.autoCard);
        autoSwitch = findViewById(R.id.autoSwitch);
        autoDetail = findViewById(R.id.autoDetail);
    }

    /** Everything a token cannot express in XML: tinted shapes, limits, accessibility roles. */
    private void paint() {
        previewSheet.setBackground(ui.shape(ui.paper, R.style.Shape_Sujian_Paper));
        previewDivider.setBackgroundColor(ui.outline);
        exportPrivacyIcon.setImageTintList(ColorStateList.valueOf(ui.muted));
        watermarkInput.setFilters(new InputFilter[] { new InputFilter.LengthFilter(80) });
        heading(findViewById(R.id.shareSection));
        heading(findViewById(R.id.exportSection));
        heading(findViewById(R.id.updateSection));
        // The card is a banner, not a list: its own surface, the badge and button carry the accent.
        updateCard.setCardBackgroundColor(ui.primaryContainer);
        updateIcon.setBackground(ui.badge(MaterialShapes.SOFT_BURST, ui.primary));
        updateIcon.setImageTintList(ColorStateList.valueOf(ui.onPrimary));
        for (TextView text : new TextView[] { updateTitle, updateDetail, updateMeta }) {
            text.setTextColor(ui.onPrimaryContainer);
        }
        updateNotes.setTextColor(ui.onPrimaryContainer);
        updateProgress.setIndicatorColor(ui.primary);
        updateProgress.setTrackColor(ColorUtils.setAlphaComponent(ui.onPrimaryContainer, 0x24));
        versionTitle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ((View) versionTitle.getParent()).setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        versionCard.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ((View) autoDetail.getParent()).setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        ((ListItemLayout) findViewById(R.id.exportItem)).updateAppearance(0, 1);

        // The preview is one summary node; its sample text is not read out line by line.
        previewCard.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        previewCard.setFocusable(true);
        ((View) previewLabel.getParent()).setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        for (StatusRow row : rows) {
            row.text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            row.card.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        }
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
        previewWatermark.setText(written ? draft() : getString(sampleBrand()));

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

    /** The brand word the real picture would carry, for whichever app is installed (the newer one first). */
    private int sampleBrand() {
        return rows[0].installed || !rows[1].installed
                ? R.string.preview_sample_watermark_paper : R.string.preview_sample_watermark_notes;
    }

    private void renderStatus() {
        boolean any = false;
        for (StatusRow row : rows) any |= row.installed;
        for (StatusRow row : rows) {
            // With neither app installed the first row stays, to say so.
            row.item.setVisibility(row.installed || (!any && row == rows[0]) ? View.VISIBLE : View.GONE);
            row.render();
        }
        segments(statusGroup);
    }

    private void renderExport() {
        for (int i = 0; i < exportButtons.length; i++) {
            NotesApp app = NotesApp.ALL[i];
            MaterialButton button = exportButtons[i];
            boolean mine = exportJob.app == app;
            boolean failedHere = exportJob.failed && mine && !exportJob.running;
            button.setVisibility(rows[i].installed ? View.VISIBLE : View.GONE);
            button.setEnabled(!exportJob.running);
            button.setIconResource(failedHere ? R.drawable.ic_refresh : R.drawable.ic_export);
            button.setText(exportJob.running && mine ? R.string.export_running
                    : failedHere ? (app == NotesApp.PAPER ? R.string.export_retry_paper : R.string.export_retry_notes)
                    : app == NotesApp.PAPER ? R.string.export_action_paper : R.string.export_action_notes);
        }
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
        publish();
        push();
    }

    /** The framework keeps the settings where every hooked process can read them, awake or not. */
    private void publish() {
        final String text = prefs.getString(ConfigContract.KEY_WATERMARK_TEXT, "");
        final boolean keepBlank = prefs.getBoolean(ConfigContract.KEY_KEEP_BLANK_SPACE, true);
        BRIDGE.execute(() -> Remote.publishSettings(text, keepBlank));
    }

    /** Hands the saved settings to each app's process; a changed answer updates the status quietly. */
    private void push() {
        final Context context = getApplicationContext();
        for (final StatusRow row : rows) {
            if (!row.installed) continue;
            BRIDGE.execute(() -> {
                final int result = NotesBridge.resolve(context, row.app);
                main.post(() -> {
                    if (row.checking || isFinishing() || isDestroyed() || row.status == result) return;
                    row.status = result;
                    animate();
                    renderStatus();
                });
            });
        }
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

    @Override protected void onStart() {
        super.onStart();
        started++;
    }

    @Override protected void onStop() {
        started--;
        super.onStop();
    }

    @Override protected void onRestart() {
        super.onRestart();
        for (StatusRow row : rows) {
            if (row.installed && row.status != STATUS_ACTIVE && row.status != STATUS_IDLE) {
                checkModule();
                return;
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        // Back from the install permission page, or from the notification settings.
        updater.resume();
        renderUpdate();
        showConfirm();
        seen();
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
        out.putString("exportApp", exportJob.app == null ? "" : exportJob.app.pkg);
        super.onSaveInstanceState(out);
    }

    @Override public Object onRetainNonConfigurationInstance() { return exportJob; }

    @Override protected void onDestroy() {
        main.removeCallbacks(persist);
        Remote.unlisten(remoteListener);
        updater.unlisten(updateListener);
        if (sheet != null) sheet.dismiss();
        if (exportJob.activity.get() == this) exportJob.activity.clear();
        super.onDestroy();
    }

    // ------------------------------------------------------------ module status

    /**
     * One notes app's row: asks the injected code, inside that app's process, to identify
     * itself, and hands it the settings on the way. A missing answer means the module is
     * installed but not actually hooking anything.
     */
    private final class StatusRow {
        final NotesApp app;
        final View item;
        final ListItemCardView card;
        final LinearLayout text;
        final LoadingIndicator loading;
        final ImageView icon, action;
        final TextView title, detail;
        final boolean installed;
        int status = STATUS_CHECKING;
        boolean checking;

        StatusRow(NotesApp app, View item) {
            this.app = app;
            this.item = item;
            card = item.findViewById(R.id.statusCard);
            text = item.findViewById(R.id.statusText);
            loading = item.findViewById(R.id.statusLoading);
            icon = item.findViewById(R.id.statusIcon);
            action = item.findViewById(R.id.statusAction);
            title = item.findViewById(R.id.statusTitle);
            detail = item.findViewById(R.id.statusDetail);
            installed = NotesBridge.installed(ConfigActivity.this, app);
            if (!installed) status = STATUS_MISSING;
        }

        String name() {
            return getString(app == NotesApp.PAPER ? R.string.app_paper : R.string.app_notes);
        }

        /**
         * A module that works needs no container: a glyph and a line of text, level with
         * everything else on the page. Only a module that is not working gets a filled
         * surface and a second line saying what to do about it.
         */
        void render() {
            boolean checkingNow = status == STATUS_CHECKING;
            boolean good = status == STATUS_ACTIVE || status == STATUS_IDLE;
            boolean quiet = checkingNow || good;
            // Worth a look but not broken: an old build waiting for a restart, a scope to add.
            boolean stale = status == STATUS_STALE || status == STATUS_SCOPE;
            int surface = quiet ? ui.card : stale ? ui.tertiaryContainer : ui.errorContainer;
            int onSurface = quiet ? ui.ink : stale ? ui.onTertiaryContainer : ui.onErrorContainer;
            int onSurfaceVariant = quiet ? ui.muted : onSurface;
            card.setCardBackgroundColor(surface);
            title.setTextColor(onSurface);
            detail.setTextColor(onSurfaceVariant);
            action.setImageTintList(ColorStateList.valueOf(onSurfaceVariant));

            loading.setVisibility(checkingNow ? View.VISIBLE : View.GONE);
            icon.setVisibility(checkingNow ? View.GONE : View.VISIBLE);
            action.setVisibility(checkingNow ? View.INVISIBLE : View.VISIBLE);
            if (!checkingNow) {
                boolean active = good;
                icon.setBackground(ui.badge(active ? MaterialShapes.COOKIE_9 : MaterialShapes.SUNNY,
                        active ? ui.primaryContainer : stale ? ui.tertiary : ui.error));
                icon.setImageResource(active ? R.drawable.ic_status_active
                        : status == STATUS_STALE ? R.drawable.ic_refresh : R.drawable.ic_status_alert);
                icon.setImageTintList(ColorStateList.valueOf(
                        active ? ui.onPrimaryContainer : stale ? ui.onTertiary : ui.onError));
            }

            String name = name();
            title.setText(checkingNow ? getString(R.string.status_checking, name)
                    : status == STATUS_ACTIVE ? getString(R.string.status_active, name)
                    : status == STATUS_IDLE ? getString(R.string.status_idle, name)
                    : status == STATUS_SCOPE ? getString(R.string.status_scope, name)
                    : stale ? getString(R.string.status_stale, name)
                    : status == STATUS_MISSING ? getString(R.string.status_missing)
                    : getString(R.string.status_inactive, name));
            detail.setText(checkingNow ? getString(R.string.status_checking_detail, name)
                    : status == STATUS_ACTIVE ? getString(R.string.status_active_detail, name, versionName())
                    : status == STATUS_IDLE ? getString(R.string.status_idle_detail, name)
                    : status == STATUS_SCOPE ? getString(R.string.status_scope_detail)
                    : stale ? getString(R.string.status_stale_detail, name)
                    : status == STATUS_MISSING ? getString(R.string.status_missing_detail)
                    : getString(R.string.status_inactive_detail, name));
            card.setEnabled(!checkingNow && installed);
            card.setContentDescription(title.getText() + "。" + detail.getText()
                    + (checkingNow || !installed || status == STATUS_SCOPE ? ""
                            : "。" + getString(R.string.status_recheck)));
        }
    }

    private void checkModule() {
        for (StatusRow row : rows) {
            if (row.installed) check(row);
        }
    }

    private void check(final StatusRow row) {
        if (row.checking || !row.installed) return;
        row.checking = true;
        row.status = STATUS_CHECKING;
        renderStatus();
        final Context context = getApplicationContext();
        BRIDGE.execute(() -> {
            final int result = NotesBridge.resolve(context, row.app);
            main.post(() -> {
                row.checking = false;
                if (isFinishing() || isDestroyed()) return;
                row.status = result;
                animate();
                renderStatus();
            });
        });
    }

    /** The framework asks the user itself; whatever the answer, look again afterwards. */
    private void requestScope(final StatusRow row) {
        BRIDGE.execute(() -> Remote.requestScope(java.util.Collections.singletonList(row.app.pkg),
                () -> main.post(() -> {
                    if (!isFinishing() && !isDestroyed()) check(row);
                })));
    }

    // ------------------------------------------------------------------ update

    private void updated(Updater.State state) {
        if (isFinishing() || isDestroyed()) return;
        Updater.State before = update;
        update = state;
        if (state.phase != Updater.INSTALLING) confirmShown = null;
        // Progress ticks only move the bar; the layout moves when the situation changes.
        if (before.phase != state.phase || before.newer != state.newer || before.checking != state.checking
                || !before.failure.equals(state.failure) || !before.note.equals(state.note)) {
            animate();
        }
        renderUpdate();
        showConfirm();
        showJust();
        seen();
        if (state.newer && !before.newer) askNotifications();
    }

    /** A version the user has seen here needs no notification later. */
    private void seen() {
        if (resumed && update.newer) updater.markNotified(update.release.tag);
    }

    /** Said once, by whichever copy of the screen is up after the update. */
    private void showJust() {
        String just = updater.takeJust();
        if (just.isEmpty()) return;
        UpdateJob.clear(this);
        scroll.post(() -> Snackbar.make(findViewById(R.id.root), getString(R.string.update_done, just),
                Snackbar.LENGTH_LONG).show());
    }

    /** The one button does whatever the update needs next. */
    private void act() {
        switch (update.phase) {
            case Updater.DOWNLOADING:
                updater.cancel();
                break;
            case Updater.PERMISSION:
                Intent allow = updater.permissionIntent();
                if (allow == null) { updater.resume(); break; }
                try { startActivity(allow); }
                catch (RuntimeException missing) { updater.update(); }
                break;
            default:
                confirmShown = null;
                updater.update();
        }
    }

    /** The system's 「install this update?」, shown from here so it opens over this screen. */
    private void showConfirm() {
        Intent confirm = update.confirm;
        if (!resumed || confirm == null || confirm == confirmShown) return;
        confirmShown = confirm;
        updater.confirmShown();
        try { startActivity(confirm); }
        catch (RuntimeException gone) { updater.status(android.content.pm.PackageInstaller.STATUS_FAILURE, null, null); }
    }

    /** Asked once, at the moment there is something worth being told about. */
    private void askNotifications() {
        if (Build.VERSION.SDK_INT < 33 || !resumed || !updater.askNotifications()) return;
        updater.markAsked();
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        renderUpdate();
    }

    private void renderUpdate() {
        Updater.State state = update;
        boolean card = state.release != null && (state.newer || state.phase != Updater.IDLE);
        updateItem.setVisibility(card ? View.VISIBLE : View.GONE);
        if (card) renderCard(state);
        renderVersion(state);
        boolean on = updater.auto();
        autoSwitch.setChecked(on);
        autoCard.setOn(on);
        autoDetail.setText(!on ? R.string.auto_off : notifying() ? R.string.auto_on : R.string.auto_on_silent);
        autoCard.setContentDescription(getString(R.string.auto_title) + "。" + autoDetail.getText());
    }

    /** Whether a new version will actually reach the user as a notification. */
    private boolean notifying() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && manager.areNotificationsEnabled()) return true;
        // Not asked yet: it will be, as soon as there is something to tell.
        return Build.VERSION.SDK_INT >= 33 && updater.askNotifications();
    }

    private void renderCard(Updater.State state) {
        Release release = state.release;
        updateTitle.setText(getString(R.string.update_available, release.version));
        String summary = release.summary();
        updateDetail.setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);
        updateDetail.setText(summary);
        updateMeta.setText(meta(release));

        boolean downloading = state.phase == Updater.DOWNLOADING;
        updateProgress.setVisibility(downloading ? View.VISIBLE : View.GONE);
        if (downloading) {
            updateProgress.setProgressCompat(state.total > 0 ? (int) (state.done * 100 / state.total) : 0, true);
        }
        boolean failed = state.phase == Updater.IDLE && !state.failure.isEmpty();
        String status = downloading ? getString(R.string.update_downloading, size(state.done), size(state.total))
                : state.phase == Updater.PERMISSION ? getString(R.string.update_permission)
                : state.phase == Updater.INSTALLING
                        ? getString(state.confirm == null && confirmShown == null
                                ? R.string.update_installing : R.string.update_confirm)
                : failed ? state.failure : state.note;
        updateStatus.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
        updateStatus.setText(status);
        updateStatus.setTextColor(failed ? ui.onErrorContainer : ui.onPrimaryContainer);
        updateStatus.setBackground(failed ? ui.shape(ui.errorContainer, R.style.Shape_Sujian_Notice) : null);
        int padding = failed ? ui.dimen(R.dimen.space_md) : 0;
        updateStatus.setPadding(padding, padding, padding, padding);
        // Read out when the situation changes, not on every tick of the download.
        updateStatus.setAccessibilityLiveRegion(downloading ? View.ACCESSIBILITY_LIVE_REGION_NONE
                : View.ACCESSIBILITY_LIVE_REGION_POLITE);
        updateProgress.setContentDescription(status);

        updateAction.setText(downloading ? R.string.update_cancel
                : state.phase == Updater.PERMISSION ? R.string.update_allow
                : state.phase == Updater.INSTALLING ? R.string.update_continue
                : failed ? R.string.update_retry : R.string.update_now);
        updateNotes.setVisibility(release.notes.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void renderVersion(Updater.State state) {
        versionTitle.setText(getString(R.string.version_title, versionName()));
        versionLoading.setVisibility(state.checking ? View.VISIBLE : View.GONE);
        versionIcon.setVisibility(state.checking ? View.INVISIBLE : View.VISIBLE);
        boolean latest = !state.newer && state.checkError.isEmpty() && state.checkedAt > 0;
        if (state.newer) {
            versionIcon.setBackground(ui.badge(MaterialShapes.SOFT_BURST, ui.primary));
            versionIcon.setImageResource(R.drawable.ic_download);
            versionIcon.setImageTintList(ColorStateList.valueOf(ui.onPrimary));
        } else {
            versionIcon.setBackground(ui.badge(MaterialShapes.COOKIE_9,
                    latest ? ui.primaryContainer : ui.secondaryContainer));
            versionIcon.setImageResource(latest ? R.drawable.ic_status_active : R.drawable.ic_refresh);
            versionIcon.setImageTintList(ColorStateList.valueOf(
                    latest ? ui.onPrimaryContainer : ui.onSecondaryContainer));
        }
        versionDetail.setText(state.checking ? getString(R.string.version_checking)
                : state.newer ? getString(R.string.version_newer, state.release.version)
                : !state.checkError.isEmpty() ? getString(R.string.version_failed, state.checkError)
                : state.checkedAt > 0 ? getString(R.string.version_latest, checked(state.checkedAt))
                : getString(R.string.version_unchecked));
        versionCard.setEnabled(!state.checking);
        versionCard.setContentDescription(versionTitle.getText() + "。" + versionDetail.getText()
                + (state.checking ? "" : "。" + getString(R.string.status_recheck)));
    }

    private String size(long bytes) { return Formatter.formatShortFileSize(this, bytes); }

    /** 「5.3 MB · 10 月 20 日发布」 */
    private String meta(Release release) {
        return release.published <= 0 ? size(release.size)
                : getString(R.string.update_meta, size(release.size), day(release.published));
    }

    private String day(long time) {
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(time);
        boolean thisYear = then.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR);
        return new SimpleDateFormat(getString(thisYear ? R.string.date_this_year : R.string.date_other_year),
                Locale.CHINA).format(new Date(time));
    }

    /** 「刚刚检查」「12 分钟前检查」「今天 09:30 检查」「10 月 18 日检查」 */
    private String checked(long time) {
        long ago = System.currentTimeMillis() - time;
        if (ago < 60_000L) return getString(R.string.checked_now);
        if (ago < 3_600_000L) return getString(R.string.checked_minutes, (int) (ago / 60_000L));
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(time);
        Calendar now = Calendar.getInstance();
        if (then.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) {
            return getString(R.string.checked_today,
                    new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(time)));
        }
        return getString(R.string.checked_day, day(time));
    }

    /** The release notes in a sheet: read them, then update from right there. */
    private void showNotes() {
        Release release = update.release;
        if (release == null || (sheet != null && sheet.isShowing())) return;
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.sheet_release, null);
        ((TextView) view.findViewById(R.id.sheetTitle)).setText(getString(R.string.sheet_title, release.version));
        ((TextView) view.findViewById(R.id.sheetMeta)).setText(meta(release));
        TextView notes = view.findViewById(R.id.sheetNotes);
        CharSequence body = notes(release.body());
        notes.setText(body.length() == 0 ? getString(R.string.sheet_empty) : body);
        notes.setMovementMethod(LinkMovementMethod.getInstance());
        view.findViewById(R.id.sheetPage).setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(MARKET_PAGE))); }
            catch (RuntimeException noBrowser) { /* Nothing to open it with. */ }
        });
        View go = view.findViewById(R.id.sheetUpdate);
        go.setVisibility(update.newer && update.phase == Updater.IDLE ? View.VISIBLE : View.GONE);
        go.setOnClickListener(v -> {
            dialog.dismiss();
            act();
        });
        dialog.setContentView(view);
        // Long notes scroll inside the sheet, so the actions under them stay in reach.
        NestedScrollView notesScroll = view.findViewById(R.id.sheetScroll);
        int limit = Math.round(getResources().getDisplayMetrics().heightPixels * 0.55f);
        notesScroll.measure(View.MeasureSpec.makeMeasureSpec(getResources().getDisplayMetrics().widthPixels,
                View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        if (notesScroll.getMeasuredHeight() > limit) notesScroll.getLayoutParams().height = limit;
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        dialog.show();
        sheet = dialog;
    }

    /** The marketplace's HTML, restyled: headings take the title role, bullets the theme's colour. */
    private CharSequence notes(String html) {
        SpannableStringBuilder text = new SpannableStringBuilder(
                Html.fromHtml(html, Html.FROM_HTML_SEPARATOR_LINE_BREAK_LIST_ITEM));
        TypedValue title = new TypedValue();
        getTheme().resolveAttribute(com.google.android.material.R.attr.textAppearanceTitleSmall, title, true);
        for (RelativeSizeSpan heading : text.getSpans(0, text.length(), RelativeSizeSpan.class)) {
            int start = text.getSpanStart(heading), end = text.getSpanEnd(heading);
            text.removeSpan(heading);
            for (StyleSpan bold : text.getSpans(start, end, StyleSpan.class)) {
                if (text.getSpanStart(bold) == start && text.getSpanEnd(bold) == end) text.removeSpan(bold);
            }
            if (title.resourceId != 0) {
                text.setSpan(new TextAppearanceSpan(this, title.resourceId), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            for (BulletSpan bullet : text.getSpans(0, text.length(), BulletSpan.class)) {
                int start = text.getSpanStart(bullet), end = text.getSpanEnd(bullet);
                text.removeSpan(bullet);
                text.setSpan(new BulletSpan(ui.dimen(R.dimen.space_sm), ui.primary, ui.dimen(R.dimen.bullet_radius)),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        return text.subSequence(0, end);
    }

    // ------------------------------------------------------------------ export

    private void startExport(NotesApp app) {
        if (exportJob.running) return;
        exportJob.running = true;
        exportJob.failed = false;
        exportJob.app = app;
        exportJob.message = getString(R.string.export_working,
                getString(app == NotesApp.PAPER ? R.string.app_paper : R.string.app_notes));
        animate();
        renderExport();
        final ExportJob job = exportJob;
        new Thread(() -> {
            String result = job.exportThrough(app);
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
        NotesApp app;
        String message = "";
        ExportJob(Context context) { this.context = context; }

        String exportThrough(NotesApp target) {
            String name = context.getString(
                    target == NotesApp.PAPER ? R.string.app_paper : R.string.app_notes);
            try (Cursor cursor = context.getContentResolver().query(target.exportUri(), null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int column = cursor.getColumnIndex(ConfigContract.COLUMN_EXPORT_MESSAGE);
                    if (column >= 0 && !cursor.isNull(column)) {
                        int ok = cursor.getColumnIndex(ConfigContract.COLUMN_EXPORT_OK);
                        failed = ok >= 0 && cursor.getInt(ok) == 0;
                        return cursor.getString(column);
                    }
                }
            } catch (Exception ignored) { /* The hook may need a cold start. */ }
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(target.pkg);
            if (intent == null) {
                failed = true;
                return context.getString(R.string.export_missing, name);
            }
            long now = System.currentTimeMillis();
            context.getSharedPreferences(ConfigContract.PREFS, 0).edit()
                    .putLong(ConfigContract.KEY_EXPORT_REQUEST, now)
                    .putString(ConfigContract.KEY_EXPORT_TARGET, target.pkg).apply();
            // The framework carries it too: the app cannot start this module's provider to ask.
            Remote.publishExportRequest(target, now);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // From the screen when it is still there, so the app opens next to it (on the
            // same display) rather than wherever the system defaults to.
            ConfigActivity screen = activity.get();
            Context starter = screen != null && !screen.isFinishing() ? screen : context;
            try { starter.startActivity(intent); }
            catch (Exception ignored) {
                failed = true;
                return context.getString(R.string.export_cannot_open, name);
            }
            return context.getString(R.string.export_requested, name);
        }
    }

    private void heading(TextView view) {
        if (android.os.Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
    }
}
