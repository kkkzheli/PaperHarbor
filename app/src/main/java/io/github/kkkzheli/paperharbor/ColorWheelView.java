package io.github.kkkzheli.paperharbor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Circular hue/saturation picker at a separately controlled brightness.
 *
 * <p>Hue maps to the angle (0 degrees at 3 o'clock, clockwise) and saturation maps to the
 * distance from the center. The wheel is drawn at the current brightness, so its center is white
 * at full brightness and gray at lower brightness. Dragging inside the wheel reports the composed
 * ARGB color; setters do not call the listener, which keeps host wiring free of feedback loops.
 *
 * <p>The two gradient shaders are cached until the wheel's center or radius changes. The view is
 * hardware-accelerated by default and does not allocate in {@link #onDraw(Canvas)}.
 */
public class ColorWheelView extends View {
    /** Receives user-driven color changes. */
    public interface Listener {
        void onColorChanged(int argb);
    }

    // A closed HSV hue loop. The first and last entries are both red for a seamless seam.
    private static final int[] HUE_COLORS = hueColors();

    private final float density;
    private final float[] hsv = new float[3];

    private final Paint wheelPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint washPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorWhitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorDarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Shader wheelShader;
    private Shader washShader;
    private float shaderCenterX = Float.NaN;
    private float shaderCenterY = Float.NaN;
    private float shaderRadius = Float.NaN;

    private float hue = 198f;
    private float saturation = .72f;
    private float brightness = 1f;
    private int alpha = 255;

    private float centerX;
    private float centerY;
    private float wheelRadius;
    private float cursorRadius;

    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private Listener listener;

    public ColorWheelView(Context context) {
        this(context, null);
    }

    public ColorWheelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setClickable(true);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(1f, density));
        borderPaint.setColor(0x22000000);
        dimPaint.setColor(Color.BLACK);

        cursorWhitePaint.setStyle(Paint.Style.STROKE);
        cursorWhitePaint.setStrokeWidth(2f * density);
        cursorWhitePaint.setColor(Color.WHITE);
        cursorDarkPaint.setStyle(Paint.Style.STROKE);
        cursorDarkPaint.setStrokeWidth(Math.max(1f, density));
        cursorDarkPaint.setColor(0x99000000);
    }

    private static int[] hueColors() {
        int[] colors = new int[25];
        float[] hsv = {0f, 1f, 1f};
        for (int i = 0; i < colors.length; i++) {
            hsv[0] = i * 360f / (colors.length - 1);
            colors[i] = Color.HSVToColor(hsv);
        }
        return colors;
    }

    /** Sets the color and listener-free state. Hue is retained for achromatic colors. */
    public void setColor(int argb) {
        Color.colorToHSV(argb, hsv);
        if (hsv[1] > 0f) hue = normalizeHue(hsv[0]);
        saturation = clamp(hsv[1], 0f, 1f);
        brightness = clamp(hsv[2], 0f, 1f);
        alpha = Color.alpha(argb);
        invalidate();
    }

    /** Returns the selected color, including the alpha of the last {@link #setColor(int)}. */
    public int getColor() {
        hsv[0] = hue;
        hsv[1] = saturation;
        hsv[2] = brightness;
        return Color.HSVToColor(alpha, hsv);
    }

    /** Returns the selected hue in degrees [0, 360). Useful for syncing a brightness slider. */
    public float getHue() {
        return hue;
    }

    /** Returns the selected saturation in [0, 1]. Useful for syncing a brightness slider. */
    public float getSaturation() {
        return saturation;
    }

    /** Sets HSV value while preserving hue and saturation. */
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
        int desired = Math.round(220f * density);
        int width = resolveSize(desired + getPaddingLeft() + getPaddingRight(), widthMeasureSpec);
        int height = resolveSize(desired + getPaddingTop() + getPaddingBottom(), heightMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        if (widthMode == MeasureSpec.UNSPECIFIED && heightMode != MeasureSpec.UNSPECIFIED) {
            width = height;
        } else if (heightMode == MeasureSpec.UNSPECIFIED && widthMode != MeasureSpec.UNSPECIFIED) {
            height = width;
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        wheelShader = null;
        washShader = null;
        shaderCenterX = Float.NaN;
        shaderCenterY = Float.NaN;
        shaderRadius = Float.NaN;
        invalidate();
    }

    private boolean geometry() {
        int contentWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        int contentHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        if (contentWidth <= 0 || contentHeight <= 0) return false;

        float maxRadius = Math.min(contentWidth, contentHeight) * .5f;
        if (maxRadius <= 0f) return false;

        float ring = Math.min(10f * density, Math.max(3f * density, maxRadius * .14f));
        ring = Math.min(ring, maxRadius * .3f);
        float radius = maxRadius - ring - Math.max(1f, density);
        if (radius < 2f * density) return false;

        centerX = getPaddingLeft() + contentWidth * .5f;
        centerY = getPaddingTop() + contentHeight * .5f;
        cursorRadius = ring;
        wheelRadius = radius;
        return true;
    }

    private void shaders() {
        if (wheelShader != null && washShader != null
                && shaderCenterX == centerX && shaderCenterY == centerY
                && shaderRadius == wheelRadius) {
            return;
        }
        wheelShader = new SweepGradient(centerX, centerY, HUE_COLORS, null);
        // Transparent white avoids a dark fringe during premultiplied interpolation.
        washShader = new RadialGradient(centerX, centerY, wheelRadius,
                0xffffffff, 0x00ffffff, Shader.TileMode.CLAMP);
        shaderCenterX = centerX;
        shaderCenterY = centerY;
        shaderRadius = wheelRadius;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!geometry()) return;
        shaders();

        wheelPaint.setShader(wheelShader);
        canvas.drawCircle(centerX, centerY, wheelRadius, wheelPaint);
        washPaint.setShader(washShader);
        canvas.drawCircle(centerX, centerY, wheelRadius, washPaint);

        // The wheel always shows hue/saturation at full value; brightness is a separate control.
        // Dimming it here would make low-brightness palettes almost impossible to pick from.

        canvas.drawCircle(centerX, centerY, wheelRadius - .5f * density, borderPaint);

        float angle = (float) Math.toRadians(hue);
        float distance = wheelRadius * saturation;
        float x = centerX + (float) Math.cos(angle) * distance;
        float y = centerY + (float) Math.sin(angle) * distance;

        // Outer dark ring plus inner white ring remains legible on both white and dark hues.
        canvas.drawCircle(x, y, Math.max(1f, cursorRadius - density), cursorWhitePaint);
        canvas.drawCircle(x, y, cursorRadius, cursorDarkPaint);
    }

    private boolean hits(float x, float y) {
        float dx = x - centerX;
        float dy = y - centerY;
        float limit = wheelRadius + cursorRadius + 2f * density;
        return dx * dx + dy * dy <= limit * limit;
    }

    private void select(float x, float y) {
        if (!geometry()) return;
        float dx = x - centerX;
        float dy = y - centerY;
        float distance = (float) Math.hypot(dx, dy);
        float nextSaturation = clamp(distance / wheelRadius, 0f, 1f);
        float nextHue = hue;
        // Near the exact center the angle is noise; keep the last useful hue.
        if (distance > .75f * density) {
            nextHue = normalizeHue((float) Math.toDegrees(Math.atan2(dy, dx)));
        }

        boolean changed = Math.abs(nextHue - hue) > .01f
                || Math.abs(nextSaturation - saturation) > .0005f;
        if (!changed) return;
        hue = nextHue;
        saturation = nextSaturation;
        invalidate();
        if (listener != null) listener.onColorChanged(getColor());
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
                if (!geometry() || !hits(event.getX(), event.getY())) return false;
                activePointerId = event.getPointerId(0);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                select(event.getX(), event.getY());
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return true;
                int index = event.findPointerIndex(activePointerId);
                if (index >= 0) select(event.getX(index), event.getY(index));
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == activePointerId) endGesture();
                return true;

            case MotionEvent.ACTION_UP: {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return true;
                int index = event.findPointerIndex(activePointerId);
                if (index >= 0) select(event.getX(index), event.getY(index));
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
        state.alpha = alpha;
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
        alpha = saved.alpha;
        invalidate();
    }

    static class SavedState extends BaseSavedState {
        float hue;
        float saturation;
        float brightness;
        int alpha;

        SavedState(Parcelable superState) {
            super(superState);
        }

        private SavedState(Parcel in) {
            super(in);
            hue = in.readFloat();
            saturation = in.readFloat();
            brightness = in.readFloat();
            alpha = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeFloat(hue);
            out.writeFloat(saturation);
            out.writeFloat(brightness);
            out.writeInt(alpha);
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
