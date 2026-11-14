package com.gathin.tvhome;

import android.view.View;
import android.view.ViewGroup;

import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.function.BooleanSupplier;

/** The same small, retargetable focus spring used by the control panel buttons. */
final class SettingsFocusMotion {
    private SettingsFocusMotion() {}

    static void bind(View root, BooleanSupplier enabled) {
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) bind(group.getChildAt(i), enabled);
        }
        if (!root.isFocusable() || !root.isClickable()) return;
        View.OnFocusChangeListener previous = root.getOnFocusChangeListener();
        SpringAnimation[] spring = new SpringAnimation[1];
        root.setOnFocusChangeListener((view, focused) -> {
            if (previous != null) previous.onFocusChange(view, focused);
            if (!enabled.getAsBoolean() || !view.isAttachedToWindow()) {
                if (spring[0] != null) spring[0].cancel();
                view.setScaleX(1f);
                view.setScaleY(1f);
                return;
            }
            if (spring[0] == null) {
                spring[0] = new SpringAnimation(view, SpringAnimation.SCALE_X, 1f);
                spring[0].setMinimumVisibleChange(.001f);
                spring[0].addUpdateListener((animation, value, velocity) -> view.setScaleY(value));
            }
            float target = focused ? 1.015f : 1f;
            spring[0].setSpring(new SpringForce(target)
                    .setStiffness(focused ? 1500f : 1900f)
                    .setDampingRatio(focused ? .72f : 1f));
            spring[0].animateToFinalPosition(target);
        });
    }
}
