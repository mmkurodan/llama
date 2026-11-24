package com.micklab.llama;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * First-run coach mark: dims the whole screen except {@code target}, outlines the target and shows
 * a "tap here" bubble next to it. Tapping the highlighted area runs {@code onTarget}; tapping
 * anywhere else just closes the overlay. Either way the overlay removes itself.
 */
public final class SpotlightOverlay extends View {

    private final View target;
    private final String message;
    private final Runnable onTarget;
    private final Runnable onClosed;

    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF hole = new RectF();
    private final RectF bubble = new RectF();
    private final int[] tmpLoc = new int[2];
    private final int[] selfLoc = new int[2];
    private final float density;
    private boolean finished;

    private SpotlightOverlay(Context ctx, View target, String message, Runnable onTarget, Runnable onClosed) {
        super(ctx);
        this.target = target;
        this.message = message;
        this.onTarget = onTarget;
        this.onClosed = onClosed;
        density = ctx.getResources().getDisplayMetrics().density;
        dimPaint.setColor(0xB3000000);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(3 * density);
        ringPaint.setColor(0xFFFF8236);
        bubblePaint.setColor(Color.WHITE);
        textPaint.setColor(0xFF202124);
        textPaint.setTextSize(16 * density);
        textPaint.setFakeBoldText(true);
        setClickable(true);
        setContentDescription(message);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /** Attach over the whole activity window. Returns the overlay (already shown). */
    public static SpotlightOverlay show(Activity activity, View target, String message,
                                        Runnable onTarget, Runnable onClosed) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        SpotlightOverlay overlay = new SpotlightOverlay(activity, target, message, onTarget, onClosed);
        decor.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.bringToFront();
        return overlay;
    }

    private void computeHole() {
        getLocationInWindow(selfLoc);
        target.getLocationInWindow(tmpLoc);
        float pad = 6 * density;
        float left = tmpLoc[0] - selfLoc[0] - pad;
        float top = tmpLoc[1] - selfLoc[1] - pad;
        hole.set(left, top, left + target.getWidth() + 2 * pad, top + target.getHeight() + 2 * pad);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        computeHole();
        float r = 14 * density;

        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        path.addRoundRect(hole, r, r, Path.Direction.CW);
        canvas.drawPath(path, dimPaint);
        canvas.drawRoundRect(hole, r, r, ringPaint);

        // Bubble below the target when there is room, otherwise above it.
        float margin = 16 * density;
        float padIn = 14 * density;
        float arrow = 10 * density;
        int textWidth = (int) Math.max(1, getWidth() - 2 * margin - 2 * padIn);
        StaticLayout layout = StaticLayout.Builder
                .obtain(message, 0, message.length(), textPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build();
        float bh = layout.getHeight() + 2 * padIn;
        boolean below = hole.bottom + arrow + bh + margin < getHeight();
        float top = below ? hole.bottom + arrow : hole.top - arrow - bh;
        bubble.set(margin, top, getWidth() - margin, top + bh);
        canvas.drawRoundRect(bubble, r, r, bubblePaint);

        float cx = hole.centerX();
        path.reset();
        if (below) {
            path.moveTo(cx - arrow, bubble.top + 1);
            path.lineTo(cx, bubble.top - arrow);
            path.lineTo(cx + arrow, bubble.top + 1);
        } else {
            path.moveTo(cx - arrow, bubble.bottom - 1);
            path.lineTo(cx, bubble.bottom + arrow);
            path.lineTo(cx + arrow, bubble.bottom - 1);
        }
        path.close();
        canvas.drawPath(path, bubblePaint);

        canvas.save();
        canvas.translate(bubble.left + padIn, bubble.top + padIn);
        layout.draw(canvas);
        canvas.restore();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        invalidate();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            computeHole();
            boolean inTarget = hole.contains(event.getX(), event.getY());
            dismiss();
            if (inTarget && onTarget != null) {
                onTarget.run();
            }
        }
        return true;   // swallow every touch while shown
    }

    @Override
    public boolean performClick() {
        // Accessibility "click": treat as tapping the highlighted target.
        super.performClick();
        dismiss();
        if (onTarget != null) {
            onTarget.run();
        }
        return true;
    }

    public void dismiss() {
        if (finished) {
            return;
        }
        finished = true;
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) {
            parent.removeView(this);
        }
        if (onClosed != null) {
            onClosed.run();
        }
    }

    public boolean isShowing() {
        return !finished;
    }
}
