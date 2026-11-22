// EXTENSION DEVELOPMENT [EXT-TOUCH-RIGHT-CLICK] [ADDED]
package com.limelight.extensions.rightclick;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Non-interactive overlay: the right-click target ring and the armed hint. */
final class RightClickIndicator extends View {
    private static final int NONE = 0, HOLD = 1, AIM = 2;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final int[] origin = new int[2];
    private final float density;
    private int mode = NONE;
    private boolean armed;
    private float x, y;

    RightClickIndicator(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * density);
        ring.setColor(0xFFF5FAF7);
        fill.setColor(0x5525744D);
        pill.setColor(0xE6202A30);
        text.setColor(0xFFF5FAF7);
        text.setTextSize(13 * density);
        text.setTextAlign(Paint.Align.CENTER);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void showAt(boolean hold, float screenX, float screenY) {
        getLocationOnScreen(origin);
        mode = hold ? HOLD : AIM;
        x = screenX - origin[0];
        y = screenY - origin[1];
        invalidate();
    }

    void hideTarget() {
        mode = NONE;
        invalidate();
    }

    void setArmed(boolean value) {
        armed = value;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (armed && mode == NONE) {
            String hint = "右键：点一下目标位置";
            float w = text.measureText(hint) + 28 * density, h = 32 * density, top = 18 * density;
            bounds.set((getWidth() - w) / 2, top, (getWidth() + w) / 2, top + h);
            canvas.drawRoundRect(bounds, h / 2, h / 2, pill);
            canvas.drawText(hint, getWidth() / 2f, top + h / 2 - (text.ascent() + text.descent()) / 2, text);
        }
        if (mode == NONE) return;
        float r = (mode == HOLD ? 26 : 20) * density;
        canvas.drawCircle(x, y, r, fill);
        canvas.drawCircle(x, y, r, ring);
        if (mode == AIM) {
            float c = 6 * density;
            canvas.drawLine(x - c, y, x + c, y, ring);
            canvas.drawLine(x, y - c, x, y + c, ring);
        }
    }
}
