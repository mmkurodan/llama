package com.micklab.llama;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * A {@link ScrollView} that grows with its content up to {@code maxHeightPx},
 * then caps its height and scrolls internally. Used to bound the output / log
 * text areas to a fraction of the screen (set via {@link #setMaxHeightPx(int)}).
 * The child TextView stays selectable; this ScrollView provides the scrolling.
 */
public class MaxHeightScrollView extends ScrollView {

    private int maxHeightPx = 0;   // 0 = no cap

    public MaxHeightScrollView(Context context) {
        super(context);
    }

    public MaxHeightScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MaxHeightScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setMaxHeightPx(int px) {
        maxHeightPx = px;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (maxHeightPx > 0) {
            // AT_MOST: wrap content but never exceed maxHeightPx (scroll beyond).
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
