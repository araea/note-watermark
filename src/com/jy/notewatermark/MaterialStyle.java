package com.jy.notewatermark;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.transition.ChangeBounds;
import androidx.transition.TransitionManager;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.motion.MotionUtils;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.textview.MaterialTextView;

/** Single source of semantic roles. Components inherit the stable M3 Expressive theme. */
final class MaterialStyle {
    enum Type {
        DISPLAY(com.google.android.material.R.attr.textAppearanceDisplaySmallEmphasized),
        HEADLINE(com.google.android.material.R.attr.textAppearanceHeadlineSmallEmphasized),
        TITLE(com.google.android.material.R.attr.textAppearanceTitleMediumEmphasized),
        BODY(com.google.android.material.R.attr.textAppearanceBodyMedium),
        LABEL(com.google.android.material.R.attr.textAppearanceLabelLarge),
        CAPTION(com.google.android.material.R.attr.textAppearanceBodySmall);
        final int attr;
        Type(int attr) { this.attr = attr; }
    }
    final Activity activity;
    final boolean dark;
    final int surface, low, container, high, ink, muted, outline, primary, onPrimary,
            primaryContainer, onPrimaryContainer, secondary, onSecondary, secondaryContainer,
            onSecondaryContainer, tertiary, onTertiary, tertiaryContainer, onTertiaryContainer,
            error, onErrorContainer, errorContainer;
    final int xs, sm, md, lg, xl, section;

    MaterialStyle(Activity activity) {
        this.activity = activity;
        dark = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        surface = color(com.google.android.material.R.attr.colorSurface);
        low = color(com.google.android.material.R.attr.colorSurfaceContainerLow);
        container = color(com.google.android.material.R.attr.colorSurfaceContainer);
        high = color(com.google.android.material.R.attr.colorSurfaceContainerHigh);
        ink = color(com.google.android.material.R.attr.colorOnSurface);
        muted = color(com.google.android.material.R.attr.colorOnSurfaceVariant);
        outline = color(com.google.android.material.R.attr.colorOutlineVariant);
        primary = color(androidx.appcompat.R.attr.colorPrimary);
        onPrimary = color(com.google.android.material.R.attr.colorOnPrimary);
        primaryContainer = color(com.google.android.material.R.attr.colorPrimaryContainer);
        onPrimaryContainer = color(com.google.android.material.R.attr.colorOnPrimaryContainer);
        secondary = color(com.google.android.material.R.attr.colorSecondary);
        onSecondary = color(com.google.android.material.R.attr.colorOnSecondary);
        secondaryContainer = color(com.google.android.material.R.attr.colorSecondaryContainer);
        onSecondaryContainer = color(com.google.android.material.R.attr.colorOnSecondaryContainer);
        tertiary = color(com.google.android.material.R.attr.colorTertiary);
        onTertiary = color(com.google.android.material.R.attr.colorOnTertiary);
        tertiaryContainer = color(com.google.android.material.R.attr.colorTertiaryContainer);
        onTertiaryContainer = color(com.google.android.material.R.attr.colorOnTertiaryContainer);
        error = color(androidx.appcompat.R.attr.colorError);
        errorContainer = color(com.google.android.material.R.attr.colorErrorContainer);
        onErrorContainer = color(com.google.android.material.R.attr.colorOnErrorContainer);
        xs = dimen(R.dimen.space_xs); sm = dimen(R.dimen.space_sm);
        md = dimen(R.dimen.space_md); lg = dimen(R.dimen.space_lg);
        xl = dimen(R.dimen.space_xl); section = dimen(R.dimen.space_section);
    }
    private int color(int attr) { return MaterialColors.getColor(activity, attr, "Sujian"); }
    int dimen(int id) { return activity.getResources().getDimensionPixelSize(id); }
    int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    MaterialShapeDrawable shape(int color, int style) {
        MaterialShapeDrawable d = new MaterialShapeDrawable(ShapeAppearanceModel.builder(activity, style, 0).build());
        d.setFillColor(ColorStateList.valueOf(color));
        return d;
    }

    TextView text(String value, Type type, int color) {
        TextView view = new MaterialTextView(activity);
        TypedValue appearance = new TypedValue();
        activity.getTheme().resolveAttribute(type.attr, appearance, true);
        view.setTextAppearance(appearance.resourceId);
        view.setText(value);
        view.setTextColor(color);
        return view;
    }

    void animateLayout(ViewGroup group) {
        if (!group.isLaidOut() || !ValueAnimator.areAnimatorsEnabled()) return;
        ChangeBounds change = new ChangeBounds();
        change.setDuration(MotionUtils.resolveThemeDuration(activity,
                com.google.android.material.R.attr.motionDurationMedium2, 300));
        change.setInterpolator(MotionUtils.resolveThemeInterpolator(activity,
                com.google.android.material.R.attr.motionEasingEmphasizedInterpolator,
                new android.view.animation.AccelerateDecelerateInterpolator()));
        TransitionManager.beginDelayedTransition(group, change);
    }

    void applyWindow() {
        activity.getWindow().setStatusBarColor(surface);
        activity.getWindow().setNavigationBarColor(low);
        activity.getWindow().getDecorView().setSystemUiVisibility(dark ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        activity.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 29) activity.getWindow().setNavigationBarContrastEnforced(false);
    }
}
