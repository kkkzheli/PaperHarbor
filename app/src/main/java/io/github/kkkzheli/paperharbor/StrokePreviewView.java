/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation, not affiliated with Goodnotes.
 */
package io.github.kkkzheli.paperharbor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Paper-toned preview of the selected stroke or eraser.
 *
 * <p>One page unit is rendered as one dp, clamped so the full stroke remains visible. Hard pen and
 * marker use a constant round-capped curve; the soft brush paints the same curve with a
 * calligraphic width envelope; the eraser shows the swept disc at the chosen size. Marker strokes
 * use the same 38% alpha factor as {@link InkStroke}.
 */
public class StrokePreviewView extends View {
    private static final float MIN_WIDTH_UNITS = 1f;
    private static final float MAX_WIDTH_UNITS = 140f;

    private final float density;
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path envelope = new Path();

    private int argb = 0xff22272e;
    private float widthUnits = 3f;
    private String style = "pen";
    private boolean dirty = true;
    private float strokeWidth;

    public StrokePreviewView(Context context) {
        this(context, null);
    }

    public StrokePreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    /** Sets the sample color, page-unit width and the style: pen, brush, marker or erase. */
    public void setStroke(int argb, float widthPageUnits, String style) {
        if (!Float.isFinite(widthPageUnits)) return;
        float width = clamp(widthPageUnits, MIN_WIDTH_UNITS, MAX_WIDTH_UNITS);
        if (width != widthUnits) dirty = true;
        this.argb = argb;
        this.widthUnits = width;
        this.style = style == null ? "pen" : style;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = Math.round(240f * density) + getPaddingLeft() + getPaddingRight();
        int desiredHeight = Math.round(80f * density) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        dirty = true;
        invalidate();
    }

    private void buildPath() {
        path.rewind();
        envelope.rewind();
        float contentWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        float contentHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        if (contentWidth <= 0f || contentHeight <= 0f) {
            dirty = false;
            return;
        }

        float scale = "erase".equals(style) ? .34f : 1f;
        strokeWidth = Math.max(1f, Math.min(widthUnits * density * scale,
                Math.min(contentHeight, contentWidth * .7f)));
        float capRadius = strokeWidth * .5f;
        float edge = Math.min(contentWidth * .45f, Math.max(capRadius, 12f * density));
        float startX = getPaddingLeft() + edge;
        float endX = getWidth() - getPaddingRight() - edge;
        if (endX < startX) {
            float middle = getPaddingLeft() + contentWidth * .5f;
            startX = middle - .5f;
            endX = middle + .5f;
        }

        float centerY = getPaddingTop() + contentHeight * .5f;
        float span = endX - startX;
        float maxAmplitude = Math.max(0f, contentHeight * .5f - capRadius - 3f * density);
        float amplitude = Math.min(10f * density, Math.min(maxAmplitude, span * .35f));

        path.moveTo(startX, centerY);
        if (span > .25f) {
            path.cubicTo(startX + span * .18f, centerY - amplitude,
                    startX + span * .34f, centerY - amplitude,
                    startX + span * .50f, centerY);
            path.cubicTo(startX + span * .66f, centerY + amplitude,
                    startX + span * .82f, centerY + amplitude,
                    endX, centerY);
        } else {
            path.lineTo(endX, centerY);
        }

        if ("brush".equals(style) && span > .25f) {
            // Same curve, drawn as a filled band whose half-width pulses like a loaded brush.
            int steps = 72;
            float[] xs = new float[steps + 1];
            float[] ys = new float[steps + 1];
            for (int i = 0; i <= steps; i++) {
                float t = i / (float)steps;
                float x = startX + span * t;
                float phase = (float)Math.sin(Math.PI * 2 * (t * 2));
                float y = centerY - amplitude * phase;
                xs[i] = x;
                ys[i] = y;
            }
            float half = strokeWidth * .5f;
            for (int i = 0; i <= steps; i++) {
                float t = i / (float)steps;
                float taper = (float)(Math.sin(Math.PI * Math.min(1, Math.max(0, t * 1.02))) * .55 + .45);
                float wid = half * taper * (1.05f + .25f * (float)Math.sin(Math.PI * 2 * t * 2));
                if (i == 0) envelope.moveTo(xs[i], ys[i] - wid);
                else envelope.lineTo(xs[i], ys[i] - wid);
            }
            for (int i = steps; i >= 0; i--) {
                float t = i / (float)steps;
                float taper = (float)(Math.sin(Math.PI * Math.min(1, Math.max(0, t * 1.02))) * .55 + .45);
                float wid = half * taper * (1.05f + .25f * (float)Math.sin(Math.PI * 2 * t * 2));
                envelope.lineTo(xs[i], ys[i] + wid);
            }
            envelope.close();
        }
        dirty = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (dirty) buildPath();
        if ("erase".equals(style)) {
            fillPaint.setColor(Ui.INK);
            fillPaint.setAlpha(0x1f);
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, strokeWidth * .5f, fillPaint);
            strokePaint.setStrokeWidth(1.4f * density);
            strokePaint.setColor(Ui.INK);
            strokePaint.setAlpha(0x99);
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, strokeWidth * .5f, strokePaint);
            return;
        }
        if (path.isEmpty()) return;
        // Transparent background: the sample is painted in the theme foreground so it stays
        // legible on both the light and dark sheets.
        int previewColor = Ui.INK;
        fillPaint.setColor(previewColor);
        strokePaint.setColor(previewColor);
        if ("brush".equals(style)) { canvas.drawPath(envelope, fillPaint); return; }
        strokePaint.setAlpha("marker".equals(style) ? Math.round(255 * .38f) : 255);
        strokePaint.setStrokeWidth(strokeWidth);
        canvas.drawPath(path, strokePaint);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : value > max ? max : value;
    }
}
