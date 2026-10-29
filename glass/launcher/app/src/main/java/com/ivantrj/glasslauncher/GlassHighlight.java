package com.ivantrj.glasslauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * The single translucent "glass" focus highlight. It springs between targets
 * instead of every tile drawing its own border. No blur: a vertical sheen
 * gradient plus a hairline rim reads as glass on a black background.
 */
public class GlassHighlight extends View {

    private final SpringValue x, y, w, h, radius;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shaderMatrix = new Matrix();
    private final LinearGradient sheen;
    private final RectF rect = new RectF();
    private boolean placed;

    public GlassHighlight(Context context, AttributeSet attrs) {
        super(context, attrs);
        SpringValue.Listener redraw = v -> invalidate();
        x = new SpringValue(420f, 0.72f, redraw);
        y = new SpringValue(420f, 0.72f, redraw);
        w = new SpringValue(420f, 0.78f, redraw);
        h = new SpringValue(420f, 0.78f, redraw);
        radius = new SpringValue(420f, 1f, redraw);
        // Unit gradient, scaled to the highlight's height each frame
        sheen = new LinearGradient(0, 0, 0, 1,
                new int[] {0x47FFFFFF, 0x24FFFFFF, 0x1AFFFFFF},
                new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        fill.setShader(sheen);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(getResources().getDisplayMetrics().density * 1.2f);
        rim.setColor(0x52FFFFFF);
        setWillNotDraw(false);
    }

    /** Move to a rect in this view's coordinates. The first call snaps. */
    public void moveTo(float left, float top, float width, float height, float cornerRadius) {
        if (!placed) {
            placed = true;
            x.snapTo(left); y.snapTo(top); w.snapTo(width); h.snapTo(height); radius.snapTo(cornerRadius);
            return;
        }
        x.animateTo(left); y.animateTo(top); w.animateTo(width); h.animateTo(height); radius.animateTo(cornerRadius);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!placed) return;
        rect.set(x.value(), y.value(), x.value() + w.value(), y.value() + h.value());
        shaderMatrix.setScale(1f, rect.height());
        shaderMatrix.postTranslate(0, rect.top);
        sheen.setLocalMatrix(shaderMatrix);
        float r = radius.value();
        canvas.drawRoundRect(rect, r, r, fill);
        canvas.drawRoundRect(rect, r, r, rim);
    }
}
