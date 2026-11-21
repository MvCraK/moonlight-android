// EXTENSION DEVELOPMENT [EXT-SS-NETWORK] [ADDED]
package com.limelight.extensions.ss;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

/** Caps the panel body so the actions stay visible on short (landscape) screens. */
public class SsMaxHeightScrollView extends ScrollView {
    public SsMaxHeightScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int screen = getResources().getDisplayMetrics().heightPixels;
        int reserved = Math.round(250 * getResources().getDisplayMetrics().density);
        int max = Math.max(screen / 4, Math.min(screen - reserved, Math.round(screen * 0.6f)));
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
    }
}
