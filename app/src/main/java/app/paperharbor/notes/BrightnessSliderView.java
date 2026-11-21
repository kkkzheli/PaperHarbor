package app.paperharbor.notes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Horizontal HSV value slider for the hue and saturation supplied by the host.
 *
 * <p>The track runs from black to the fully bright hue/saturation color. The handle is clamped
 * inside the content width even when the view is narrow. Programmatic setters do not notify the
 * listener; user taps and drags do.
 */
public class BrightnessSliderView extends View {
    /** Receives user-driven brightness changes. */
    public interface Listener {
        void onBrightnessChanged(float value);
    }

    private final float density;
    private final float[] hsv = new float[3];

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint trackBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();

    private Shader trackShader;
    private float shaderLeft = Float.NaN;
    private float shaderRight = Float.NaN;

    private float hue = 198f;
    private float saturation = .72f;
    private float brightness = 1f;
    private int fullColor;

    private float thumbRadius;
    private float thumbMinX;
    private float thumbMaxX;
    private float thumbCenterY;

    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private Listener listener;

    public BrightnessSliderView(Context context) {
        this(context, null);
    }

    public BrightnessSliderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setClickable(true);

        trackBorderPaint.setStyle(Paint.Style.STROKE);
        trackBorderPaint.setStrokeWidth(Math.max(1f, density));
        trackBorderPaint.setColor(0x1a000000);

        thumbRingPaint.setStyle(Paint.Style.STROKE);
        thumbRingPaint.setColor(Color.WHITE);
        thumbOutlinePaint.setStyle(Paint.Style.STROKE);
        thumbOutlinePaint.setColor(0x99000000);

        hsv[0] = hue;
        hsv[1] = saturation;
        hsv[2] = 1f;
        fullColor = Color.HSVToColor(hsv);
    }

    /** Sets the hue and saturation used by the track and handle. Hue wraps around 360 degrees. */
    public void setHueSaturation(float h, float s) {
        if (!Float.isFinite(h) || !Float.isFinite(s)) return;
        float nextHue = normalizeHue(h);
        float nextSaturation = clamp(s, 0f, 1f);
        if (nextHue == hue && nextSaturation == saturation) return;
        hue = nextHue;
        saturation = nextSaturation;
        hsv[0] = hue;
        hsv[1] = saturation;
        hsv[2] = 1f;
        fullColor = Color.HSVToColor(hsv);
        trackShader = null;
        invalidate();
    }

    /** Sets HSV value. Values outside 0..1 and non-finite values are clamped or ignored. */
    public void setBrightness(float value) {
        if (!Float.isFinite(value)) return;
        float next = clamp(value, 0f, 1f);
        if (next == brightness) return;
        brightness = next;
        invalidate();
    }

    public float getBrightness() {
        return brightness;
    }

    /** Sets the callback for user gestures. Pass {@code null} to remove it. */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = Math.round(240f * density) + getPaddingLeft() + getPaddingRight();
        int desiredHeight = Math.round(48f * density) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        trackShader = null;
        shaderLeft = Float.NaN;
        shaderRight = Float.NaN;
        invalidate();
    }

    private boolean geometry() {
        int contentWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        int contentHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        if (contentWidth <= 0 || contentHeight <= 0) return false;

        float left = getPaddingLeft();
        float right = getWidth() - getPaddingRight();
        float barHeight = Math.min(contentHeight,
                Math.max(4f * density, Math.min(20f * density, contentHeight * .34f)));
        thumbCenterY = getPaddingTop() + contentHeight * .5f;
        track.set(left, thumbCenterY - barHeight * .5f, right, thumbCenterY + barHeight * .5f);

        float radius = Math.min(11f * density, Math.min(contentHeight * .48f, contentWidth * .46f));
        thumbRadius = Math.max(1f, radius);
        thumbMinX = left + thumbRadius;
        thumbMaxX = right - thumbRadius;
        if (thumbMaxX < thumbMinX) {
            float middle = (left + right) * .5f;
            thumbMinX = middle;
            thumbMaxX = middle;
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!geometry()) return;

        if (trackShader == null || shaderLeft != track.left || shaderRight != track.right) {
            trackShader = new LinearGradient(track.left, thumbCenterY, track.right, thumbCenterY,
                    Color.BLACK, fullColor, Shader.TileMode.CLAMP);
            shaderLeft = track.left;
            shaderRight = track.right;
        }

        float round = track.height() * .5f;
        trackPaint.setShader(trackShader);
        canvas.drawRoundRect(track, round, round, trackPaint);
        canvas.drawRoundRect(track, round, round, trackBorderPaint);

        float span = thumbMaxX - thumbMinX;
        float x = span <= 0f ? (thumbMinX + thumbMaxX) * .5f : thumbMinX + brightness * span;

        float ringWidth = Math.min(2f * density, thumbRadius * .30f);
        float outlineWidth = Math.min(1f * density, thumbRadius * .18f);
        float whiteCenter = Math.max(0f, thumbRadius - outlineWidth - ringWidth * .5f);
        float fillRadius = Math.max(0f, whiteCenter - ringWidth * .5f);

        hsv[0] = hue;
        hsv[1] = saturation;
        hsv[2] = brightness;
        thumbFillPaint.setColor(Color.HSVToColor(hsv));
        canvas.drawCircle(x, thumbCenterY, fillRadius, thumbFillPaint);

        thumbRingPaint.setStrokeWidth(ringWidth);
        canvas.drawCircle(x, thumbCenterY, whiteCenter, thumbRingPaint);
        thumbOutlinePaint.setStrokeWidth(outlineWidth);
        canvas.drawCircle(x, thumbCenterY, Math.max(0f, thumbRadius - outlineWidth * .5f),
                thumbOutlinePaint);
    }

    private void setFromX(float x) {
        if (!geometry()) return;
        float span = thumbMaxX - thumbMinX;
        float fraction;
        if (span > 0f) {
            fraction = (x - thumbMinX) / span;
        } else {
            float width = track.width();
            fraction = width > 0f ? (x - track.left) / width : .5f;
        }
        float next = clamp(fraction, 0f, 1f);
        if (Math.abs(next - brightness) <= .0001f) return;
        brightness = next;
        invalidate();
        if (listener != null) listener.onBrightnessChanged(brightness);
    }

    private void endGesture() {
        if (activePointerId == MotionEvent.INVALID_POINTER_ID) return;
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!geometry()) return false;
                activePointerId = event.getPointerId(0);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                setFromX(event.getX());
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return true;
                int index = event.findPointerIndex(activePointerId);
                if (index >= 0) setFromX(event.getX(index));
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == activePointerId) endGesture();
                return true;

            case MotionEvent.ACTION_UP: {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return true;
                int index = event.findPointerIndex(activePointerId);
                if (index >= 0) setFromX(event.getX(index));
                endGesture();
                performClick();
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                endGesture();
                return true;

            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        endGesture();
        super.onDetachedFromWindow();
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        SavedState state = new SavedState(super.onSaveInstanceState());
        state.hue = hue;
        state.saturation = saturation;
        state.brightness = brightness;
        return state;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }
        SavedState saved = (SavedState) state;
        super.onRestoreInstanceState(saved.getSuperState());
        hue = saved.hue;
        saturation = saved.saturation;
        brightness = saved.brightness;
        hsv[0] = hue;
        hsv[1] = saturation;
        hsv[2] = 1f;
        fullColor = Color.HSVToColor(hsv);
        trackShader = null;
        invalidate();
    }

    static class SavedState extends BaseSavedState {
        float hue;
        float saturation;
        float brightness;

        SavedState(Parcelable superState) {
            super(superState);
        }

        private SavedState(Parcel in) {
            super(in);
            hue = in.readFloat();
            saturation = in.readFloat();
            brightness = in.readFloat();
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeFloat(hue);
            out.writeFloat(saturation);
            out.writeFloat(brightness);
        }

        public static final Parcelable.Creator<SavedState> CREATOR =
                new Parcelable.Creator<SavedState>() {
                    @Override
                    public SavedState createFromParcel(Parcel in) {
                        return new SavedState(in);
                    }

                    @Override
                    public SavedState[] newArray(int size) {
                        return new SavedState[size];
                    }
                };
    }

    private static float normalizeHue(float value) {
        float h = value % 360f;
        return h < 0f ? h + 360f : h;
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : value > max ? max : value;
    }
}
