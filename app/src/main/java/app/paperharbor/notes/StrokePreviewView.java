package app.paperharbor.notes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Paper-toned preview of the selected pen or marker stroke.
 *
 * <p>One page unit is rendered as one dp, clamped so the full stroke remains visible. Pen strokes
 * are opaque; marker strokes use the same 38% alpha factor as {@link InkStroke}. The sample is a
 * single round-capped cubic wave, so it does not compound alpha where the curve crosses itself.
 */
public class StrokePreviewView extends View {
    private static final int PAPER_COLOR = 0xfffffdf8;
    private static final float MIN_WIDTH_UNITS = 1f;
    private static final float MAX_WIDTH_UNITS = 40f;

    private final float density;
    private final Paint paperPaint = new Paint();
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private int argb = 0xff22272e;
    private float widthUnits = 3f;
    private boolean marker;
    private boolean pathDirty = true;
    private float strokeWidth;

    public StrokePreviewView(Context context) {
        this(context, null);
    }

    public StrokePreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        paperPaint.setColor(PAPER_COLOR);
        paperPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Sets the sample color, page-unit width (clamped to 1..40), and marker alpha behavior. */
    public void setStroke(int argb, float widthPageUnits, boolean marker) {
        if (!Float.isFinite(widthPageUnits)) return;
        float width = clamp(widthPageUnits, MIN_WIDTH_UNITS, MAX_WIDTH_UNITS);
        if (width != widthUnits) pathDirty = true;
        this.argb = argb;
        this.widthUnits = width;
        this.marker = marker;
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
        pathDirty = true;
        invalidate();
    }

    private void buildPath() {
        path.rewind();
        float contentWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        float contentHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        if (contentWidth <= 0f || contentHeight <= 0f) {
            pathDirty = false;
            return;
        }

        strokeWidth = Math.max(1f, Math.min(widthUnits * density,
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
        pathDirty = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(PAPER_COLOR);
        if (pathDirty) buildPath();
        if (path.isEmpty()) return;

        strokePaint.setColor(argb);
        strokePaint.setAlpha(marker ? Math.round(Color.alpha(argb) * .38f) : Color.alpha(argb));
        strokePaint.setStrokeWidth(strokeWidth);
        canvas.drawPath(path, strokePaint);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : value > max ? max : value;
    }
}
