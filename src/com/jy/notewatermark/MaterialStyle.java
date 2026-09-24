package com.jy.notewatermark;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.ShapeDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.graphics.shapes.RoundedPolygon;
import androidx.transition.ChangeBounds;
import androidx.transition.Fade;
import androidx.transition.TransitionManager;
import androidx.transition.TransitionSet;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.motion.MotionUtils;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.MaterialShapes;
import com.google.android.material.shape.ShapeAppearanceModel;

/**
 * The design tokens the code needs at runtime. Typography, shape and spacing are
 * declared in res/values and applied in the layout; what is left here is the colour
 * roles, the M3E shapes a state badge is cut from, and the one spring-driven
 * transition every state change on this screen runs through.
 */
final class MaterialStyle {
    final Activity activity;
    final boolean dark;
    final int page, card, paper, ink, muted, outline, primary, onPrimary,
            primaryContainer, onPrimaryContainer, secondaryContainer, onSecondaryContainer,
            tertiary, onTertiary, tertiaryContainer, onTertiaryContainer,
            error, onError, errorContainer, onErrorContainer;

    MaterialStyle(Activity activity) {
        this.activity = activity;
        dark = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        page = color(com.google.android.material.R.attr.colorSurfaceContainer);
        card = color(com.google.android.material.R.attr.colorSurfaceBright);
        paper = color(com.google.android.material.R.attr.colorSurfaceContainerHigh);
        ink = color(com.google.android.material.R.attr.colorOnSurface);
        muted = color(com.google.android.material.R.attr.colorOnSurfaceVariant);
        outline = color(com.google.android.material.R.attr.colorOutlineVariant);
        primary = color(androidx.appcompat.R.attr.colorPrimary);
        onPrimary = color(com.google.android.material.R.attr.colorOnPrimary);
        primaryContainer = color(com.google.android.material.R.attr.colorPrimaryContainer);
        onPrimaryContainer = color(com.google.android.material.R.attr.colorOnPrimaryContainer);
        secondaryContainer = color(com.google.android.material.R.attr.colorSecondaryContainer);
        onSecondaryContainer = color(com.google.android.material.R.attr.colorOnSecondaryContainer);
        tertiary = color(com.google.android.material.R.attr.colorTertiary);
        onTertiary = color(com.google.android.material.R.attr.colorOnTertiary);
        tertiaryContainer = color(com.google.android.material.R.attr.colorTertiaryContainer);
        onTertiaryContainer = color(com.google.android.material.R.attr.colorOnTertiaryContainer);
        error = color(androidx.appcompat.R.attr.colorError);
        onError = color(com.google.android.material.R.attr.colorOnError);
        errorContainer = color(com.google.android.material.R.attr.colorErrorContainer);
        onErrorContainer = color(com.google.android.material.R.attr.colorOnErrorContainer);
    }

    private int color(int attr) { return MaterialColors.getColor(activity, attr, "Sujian"); }
    int dimen(int id) { return activity.getResources().getDimensionPixelSize(id); }

    MaterialShapeDrawable shape(int fill, int style) {
        MaterialShapeDrawable drawable =
                new MaterialShapeDrawable(ShapeAppearanceModel.builder(activity, style, 0).build());
        drawable.setFillColor(ColorStateList.valueOf(fill));
        return drawable;
    }

    /** A badge cut from the M3E shape library, so states differ in outline, not only in colour. */
    ShapeDrawable badge(RoundedPolygon polygon, int fill) {
        ShapeDrawable drawable = MaterialShapes.createShapeDrawable(polygon);
        drawable.getPaint().setColor(fill);
        return drawable;
    }

    /**
     * One transition for every state change, so the screen always moves the same way:
     * bounds follow the default spatial spring, fades the default effects spring.
     * A running transition suppresses layout on its scene root, so any earlier one is
     * ended first: an interrupted transition would otherwise freeze the group's bounds.
     */
    void animate(ViewGroup group) {
        if (!group.isLaidOut() || !ValueAnimator.areAnimatorsEnabled()) return;
        TransitionManager.endTransitions(group);
        Spring spatial = spring(com.google.android.material.R.attr.motionSpringDefaultSpatial,
                com.google.android.material.R.style.Motion_Material3_Spring_Expressive_Default_Spatial);
        Spring effects = spring(com.google.android.material.R.attr.motionSpringDefaultEffects,
                com.google.android.material.R.style.Motion_Material3_Spring_Expressive_Default_Effects);
        TransitionSet change = new TransitionSet()
                .setOrdering(TransitionSet.ORDERING_TOGETHER)
                .addTransition(new Fade().setDuration(effects.duration).setInterpolator(effects))
                .addTransition(new ChangeBounds().setDuration(spatial.duration).setInterpolator(spatial));
        TransitionManager.beginDelayedTransition(group, change);
    }

    private Spring spring(int attr, int fallback) {
        SpringForce force = MotionUtils.resolveThemeSpringForce(activity, attr, fallback);
        return new Spring(force.getStiffness(), force.getDampingRatio());
    }

    /**
     * A unit-mass spring from rest to 1, played as a time curve. Transitions only take
     * an interpolator, so the theme's spring tokens become one, with the duration set
     * to the moment the motion settles within 0.1% of its target.
     */
    static final class Spring implements TimeInterpolator {
        final long duration;
        private final double omega, zeta, settle;

        Spring(float stiffness, float dampingRatio) {
            omega = Math.sqrt(stiffness);
            zeta = Math.max(0.05, Math.min(1, dampingRatio));
            settle = Math.log(1000) / (zeta * omega) * (zeta >= 1 ? 1.4 : 1);
            duration = Math.round(settle * 1000);
        }

        @Override public float getInterpolation(float input) {
            if (input >= 1) return 1;
            double t = input * settle;
            double decay = Math.exp(-zeta * omega * t);
            if (zeta >= 1) return (float) (1 - decay * (1 + omega * t));
            double damped = omega * Math.sqrt(1 - zeta * zeta);
            return (float) (1 - decay * (Math.cos(damped * t) + zeta * omega / damped * Math.sin(damped * t)));
        }
    }

    void applyWindow() {
        activity.getWindow().setStatusBarColor(page);
        activity.getWindow().setNavigationBarColor(page);
        activity.getWindow().getDecorView().setSystemUiVisibility(dark ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        activity.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 29) activity.getWindow().setNavigationBarContrastEnforced(false);
    }
}
