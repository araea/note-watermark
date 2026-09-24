package com.jy.notewatermark;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import androidx.transition.ChangeBounds;
import androidx.transition.Fade;
import androidx.transition.TransitionManager;
import androidx.transition.TransitionSet;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.motion.MotionUtils;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.ShapeAppearanceModel;

/**
 * The design tokens the code needs at runtime. Typography, shape and spacing are
 * declared in res/values and applied in the layout; what is left here is the colour
 * roles, the two spacing steps a tinted surface needs, and the one transition every
 * state change on this screen runs through.
 */
final class MaterialStyle {
    final Activity activity;
    final boolean dark;
    final int surface, sheet, container, ink, muted, outline, primary, onPrimary,
            primaryContainer, onPrimaryContainer, secondary, onSecondary, secondaryContainer,
            onSecondaryContainer, tertiary, onTertiary, tertiaryContainer, onTertiaryContainer,
            error, onErrorContainer, errorContainer;
    final int md, lg;

    MaterialStyle(Activity activity) {
        this.activity = activity;
        dark = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        surface = color(com.google.android.material.R.attr.colorSurface);
        sheet = color(com.google.android.material.R.attr.colorSurfaceContainerLow);
        container = color(com.google.android.material.R.attr.colorSurfaceContainer);
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
        md = dimen(R.dimen.space_md);
        lg = dimen(R.dimen.space_lg);
    }

    private int color(int attr) { return MaterialColors.getColor(activity, attr, "Sujian"); }
    int dimen(int id) { return activity.getResources().getDimensionPixelSize(id); }

    MaterialShapeDrawable shape(int fill, int style) {
        MaterialShapeDrawable drawable =
                new MaterialShapeDrawable(ShapeAppearanceModel.builder(activity, style, 0).build());
        drawable.setFillColor(ColorStateList.valueOf(fill));
        return drawable;
    }

    /**
     * One transition for every state change, so the screen always moves the same way.
     * A running transition suppresses layout on its scene root, so any earlier one is
     * ended first: an interrupted transition would otherwise freeze the group's bounds.
     */
    void animate(ViewGroup group) {
        if (!group.isLaidOut() || !ValueAnimator.areAnimatorsEnabled()) return;
        TransitionManager.endTransitions(group);
        TransitionSet change = new TransitionSet()
                .setOrdering(TransitionSet.ORDERING_TOGETHER)
                .addTransition(new Fade())
                .addTransition(new ChangeBounds());
        change.setDuration(MotionUtils.resolveThemeDuration(activity,
                com.google.android.material.R.attr.motionDurationMedium2, 300));
        change.setInterpolator(MotionUtils.resolveThemeInterpolator(activity,
                com.google.android.material.R.attr.motionEasingEmphasizedInterpolator,
                new android.view.animation.AccelerateDecelerateInterpolator()));
        TransitionManager.beginDelayedTransition(group, change);
    }

    void applyWindow() {
        activity.getWindow().setStatusBarColor(surface);
        activity.getWindow().setNavigationBarColor(surface);
        activity.getWindow().getDecorView().setSystemUiVisibility(dark ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        activity.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 29) activity.getWindow().setNavigationBarContrastEnforced(false);
    }
}
