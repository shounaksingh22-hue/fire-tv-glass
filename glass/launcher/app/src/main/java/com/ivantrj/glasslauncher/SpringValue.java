package com.ivantrj.glasslauncher;

import android.view.Choreographer;

/** A damped spring driven by Choreographer. Ratio below 1 overshoots slightly. */
final class SpringValue implements Choreographer.FrameCallback {

    interface Listener { void onSpringUpdate(float value); }

    private final float stiffness;
    private final float damping;
    private final Listener listener;
    private float value, velocity, target;
    private long lastFrameNanos;
    private boolean running;

    SpringValue(float stiffness, float dampingRatio, Listener listener) {
        this.stiffness = stiffness;
        this.damping = 2f * dampingRatio * (float) Math.sqrt(stiffness);
        this.listener = listener;
    }

    float value() { return value; }

    void snapTo(float v) {
        value = target = v;
        velocity = 0;
        if (running) {
            Choreographer.getInstance().removeFrameCallback(this);
            running = false;
        }
        listener.onSpringUpdate(value);
    }

    void animateTo(float v) {
        target = v;
        if (!running) {
            running = true;
            lastFrameNanos = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long now) {
        float dt = lastFrameNanos == 0 ? 1f / 60f : Math.min((now - lastFrameNanos) / 1e9f, 1f / 30f);
        lastFrameNanos = now;
        // Four semi-implicit Euler substeps keep the spring stable at 30-60 fps
        float h = dt / 4f;
        for (int i = 0; i < 4; i++) {
            float accel = -stiffness * (value - target) - damping * velocity;
            velocity += accel * h;
            value += velocity * h;
        }
        boolean settled = Math.abs(value - target) < 0.5f && Math.abs(velocity) < 8f;
        if (settled) {
            value = target;
            velocity = 0;
        }
        listener.onSpringUpdate(value);
        if (settled) {
            running = false;
        } else {
            Choreographer.getInstance().postFrameCallback(this);
        }
    }
}
