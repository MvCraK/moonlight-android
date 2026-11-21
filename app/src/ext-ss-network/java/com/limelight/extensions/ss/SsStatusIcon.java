// EXTENSION DEVELOPMENT [EXT-SS-NETWORK] [ADDED]
package com.limelight.extensions.ss;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import com.limelight.R;

/**
 * Line-style mesh glyph (three linked nodes) matching the home toolbar icons.
 * Colour carries the state: grey = off, green = connected, amber = node issue.
 * Nodes are hollow until the connection is confirmed.
 */
final class SsStatusIcon extends Drawable {
    private final int size;
    private final int off;
    private final int online;
    private final int warning;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private SsRoutes.Status status = SsRoutes.Status.OFF;
    private int alpha = 255;

    SsStatusIcon(Context context) {
        size = Math.round(24 * context.getResources().getDisplayMetrics().density);
        off = context.getResources().getColor(R.color.home_secondary_text);
        online = context.getResources().getColor(R.color.home_connected);
        warning = context.getResources().getColor(R.color.home_warning);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    void setStatus(SsRoutes.Status status) {
        if (this.status != status) {
            this.status = status;
            invalidateSelf();
        }
    }

    static int color(Context context, SsRoutes.Status status) {
        switch (status) {
            case ONLINE:
            case CHECKING:
                return context.getResources().getColor(R.color.home_connected);
            case DEGRADED:
                return context.getResources().getColor(R.color.home_warning);
            default:
                return context.getResources().getColor(R.color.home_secondary_text);
        }
    }

    private int color() {
        switch (status) {
            case ONLINE:
            case CHECKING:
                return online;
            case DEGRADED:
                return warning;
            default:
                return off;
        }
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float unit = Math.min(b.width(), b.height()) / 24f;
        canvas.save();
        canvas.translate(b.exactCenterX() - 12 * unit, b.exactCenterY() - 12 * unit);
        int color = color();
        stroke.setColor(color);
        stroke.setAlpha(alpha);
        fill.setColor(color);
        fill.setAlpha(alpha);
        stroke.setStrokeWidth(1.8f * unit);

        float[][] nodes = {{12f, 4.6f}, {4.8f, 17.4f}, {19.2f, 17.4f}};
        float r = 2.7f;
        for (int i = 0; i < nodes.length; i++) {
            float[] a = nodes[i];
            float[] c = nodes[(i + 1) % nodes.length];
            float dx = c[0] - a[0];
            float dy = c[1] - a[1];
            float len = (float) Math.hypot(dx, dy);
            float ox = dx / len * r;
            float oy = dy / len * r;
            canvas.drawLine((a[0] + ox) * unit, (a[1] + oy) * unit, (c[0] - ox) * unit, (c[1] - oy) * unit, stroke);
        }
        boolean solid = status == SsRoutes.Status.ONLINE || status == SsRoutes.Status.DEGRADED;
        for (float[] n : nodes) {
            if (solid) canvas.drawCircle(n[0] * unit, n[1] * unit, r * unit, fill);
            else canvas.drawCircle(n[0] * unit, n[1] * unit, (r - 0.9f) * unit, stroke);
        }
        canvas.restore();
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        stroke.setColorFilter(colorFilter);
        fill.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
