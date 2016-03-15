package com.github.axet.hourlyreminder.animations;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.view.animation.Transformation;

public class StepAnimation extends Animation {

    View view;

    public interface LateCreator {
        StepAnimation create();
    }

    public static void apply(LateCreator c, View v, boolean expand, boolean animate) {
        Animation old = v.getAnimation();
        if (old != null && old instanceof MarginAnimation) {
            MarginAnimation m = (MarginAnimation) old;

            long cur = AnimationUtils.currentAnimationTimeMillis();
            long past = cur - m.getStartTime() - m.getStartOffset();
            long left = m.getDuration() - past;
            long offset = cur - m.getStartTime() - left;

            if (animate) {
                if (m.hasEnded()) {
                    StepAnimation mm = c.create();
                    mm.startAnimation(v);
                } else {
                    if (m.expand != expand) {
                        m.expand = expand;
                        m.setStartOffset(offset);
                    } else {
                        // keep rolling do nothing
                    }
                }
            } else {
                if (!m.hasEnded()) {
                    v.clearAnimation();
                    m.restore();
                }
                StepAnimation mm = c.create();
                mm.restore();
                mm.end();
            }
        } else {
            StepAnimation mm = c.create();
            if (animate) {
                mm.startAnimation(v);
            } else {
                mm.restore();
                mm.end();
            }
        }
    }

    public StepAnimation(View view) {
        this.view = view;
    }

    public void startAnimation(View v) {
        init();
        // do first step to hide view (we animation does it).
        //
        // but some androids API does not start animation on 0dp views.
        calc(0.01f, new Transformation());
        v.startAnimation(this);
    }

    public void init() {
        // animation does not start on older API if inital state of view is hidden.
        // show view here.
        view.setVisibility(View.VISIBLE);
    }

    public void calc(float i, Transformation t) {
    }

    public void restore() {
    }

    public void end() {
    }

    @Override
    protected void applyTransformation(float interpolatedTime, Transformation t) {
        super.applyTransformation(interpolatedTime, t);

        calc(interpolatedTime, t);

        if (interpolatedTime >= 1) {
            restore();
            end();
        }
    }
}