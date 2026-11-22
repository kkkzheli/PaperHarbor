/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation.
 */
package io.github.kkkzheli.paperharbor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * A notebook cover: the board colour, one of four construction styles, the note title and a small
 * window onto the first page. Drawn rather than composed from views so the shelf, the new-note sheet
 * and the cover picker all show exactly the same thing at any size.
 */
final class CoverArt extends View {
    static final String[] STYLES = {"spine", "band", "label", "plain"};
    static final String[] STYLE_NAMES = {"书脊", "横带", "标签", "素面"};
    static final int[] PALETTE = {
        0xff365a4f, 0xff7a4b42, 0xff3f5b76, 0xff7b6440, 0xff5d4f6b, 0xff4c6b6a,
        0xff8a5a3c, 0xff2f4a5e
    };
    static final int[] PALETTE_DARK = {
        0xff2c4a42, 0xff5f3a33, 0xff33495e, 0xff5f4d31, 0xff4a4057, 0xff3b5453,
        0xff6b4630, 0xff263c4c
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint type = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final RectF window = new RectF();
    private final RectF label = new RectF();
    private String style = "spine";
    private int color = PALETTE[0];
    private String title = "";
    private Bitmap page;
    private boolean chosen;

    CoverArt(Context c) {
        super(c);
        setWillNotDraw(false);
    }

    void setCover(String style, int color) {
        for (String candidate : STYLES) if (candidate.equals(style)) this.style = style;
        this.color = color;
        invalidate();
    }

    void setTitle(String value) { title = value == null ? "" : value; invalidate(); }
    void setPage(Bitmap value) { page = value; invalidate(); }
    void setChosen(boolean value) { chosen = value; invalidate(); }

    static int colorFor(String style, int color, boolean dark) {
        // The picker offers palette colours; a stored colour is always used as given.
        return color;
    }

    @Override protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float radius = Math.max(6 * d, Math.min(getWidth(), getHeight()) * .06f);
        box.set(0, 0, getWidth(), getHeight());
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, getWidth() * .35f, getHeight(),
            lighten(color, 1.12f), darken(color, .78f), Shader.TileMode.CLAMP));
        c.drawRoundRect(box, radius, radius, paint);
        paint.setShader(null);

        float pad = Math.max(4 * d, getWidth() * .055f);
        float titleSize = Math.max(9 * d, Math.min(getWidth() * .095f, getHeight() * .16f));
        boolean onBand = false;

        if ("spine".equals(style)) {
            paint.setColor(darken(color, .72f));
            c.drawRect(0, 0, Math.max(6 * d, getWidth() * .07f), getHeight(), paint);
            paint.setColor(lighten(color, 1.35f));
            paint.setAlpha(120);
            float sx = Math.max(6 * d, getWidth() * .07f) * .45f;
            for (int i = 0; i < 3; i++) {
                float y = getHeight() * (.22f + i * .28f);
                c.drawCircle(sx, y, Math.max(1.2f, 1.6f * d), paint);
            }
            paint.setAlpha(255);
        } else if ("band".equals(style)) {
            label.set(pad * .7f, pad, getWidth() - pad * .7f, pad + titleSize * 2.6f);
            paint.setColor(0xfff7f0e2);
            paint.setAlpha(242);
            c.drawRoundRect(label, 4 * d, 4 * d, paint);
            paint.setAlpha(255);
            onBand = true;
        } else if ("label".equals(style)) {
            float w = getWidth() * .58f, h = titleSize * 2.5f;
            label.set((getWidth() - w) * .5f, pad, (getWidth() + w) * .5f, pad + h);
            paint.setColor(0xfff7f0e2);
            paint.setAlpha(242);
            c.drawRoundRect(label, 5 * d, 5 * d, paint);
            paint.setAlpha(255);
            onBand = true;
        }

        // Title.
        type.setTextSize(titleSize);
        type.setTypeface(Ui.serif(getContext()));
        type.setColor(onBand ? 0xff26302b : 0xfffffdf8);
        type.setAlpha(onBand ? 255 : 235);
        float textLeft = "spine".equals(style) ? Math.max(8 * d, getWidth() * .12f) : pad * 1.25f;
        float textWidth = getWidth() - textLeft - pad;
        if (textWidth > 4 && !title.isEmpty()) {
            Layout layout = StaticLayout.Builder
                .obtain(title, 0, title.length(), type, Math.max(1, (int)textWidth))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build();
            float top = onBand ? label.top + (label.height() - layout.getHeight()) * .5f
                : pad * 1.2f;
            c.save();
            c.translate(textLeft, top);
            layout.draw(c);
            c.restore();
        }

        // Page window: the first page, so a shelf of covers stays recognisable.
        float top = onBand ? label.bottom + pad * .8f : pad * 1.2f + titleSize * 2.4f;
        top = Math.min(top, getHeight() * .48f);
        float left = "spine".equals(style) ? Math.max(8 * d, getWidth() * .12f) : pad;
        window.set(left, top, getWidth() - pad, getHeight() - pad);
        if (window.height() > 6) {
            paint.setColor(0xfffffdf8);
            c.drawRoundRect(window, 5 * d, 5 * d, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1, d * .7f));
            paint.setColor(darken(color, .78f));
            c.drawRoundRect(window, 5 * d, 5 * d, paint);
            paint.setStyle(Paint.Style.FILL);
            if (page != null && !page.isRecycled()) {
                float inset = Math.max(1, 2 * d);
                RectF dst = new RectF(window.left + inset, window.top + inset,
                    window.right - inset, window.bottom - inset);
                float fit = Math.min(dst.width() / page.getWidth(), dst.height() / page.getHeight());
                float bw = page.getWidth() * fit, bh = page.getHeight() * fit;
                float cx = (dst.left + dst.right) * .5f, cy = (dst.top + dst.bottom) * .5f;
                RectF fitted = new RectF(cx - bw * .5f, cy - bh * .5f, cx + bw * .5f, cy + bh * .5f);
                c.drawBitmap(page, null, fitted, paint);
            } else {
                paint.setColor(0xffe6e2d8);
                paint.setStrokeWidth(Math.max(1, d * .6f));
                float step = Math.max(5 * d, window.height() / 9f);
                for (float y = window.top + step; y < window.bottom - step * .4f; y += step)
                    c.drawLine(window.left + 4 * d, y, window.right - 4 * d, y, paint);
            }
        }

        if (chosen) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2, 2 * d));
            paint.setColor(Ui.ACCENT);
            c.drawRoundRect(box, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private static int lighten(int color, float factor) {
        int r = Math.min(255, Math.round(((color >> 16) & 0xff) * factor));
        int g = Math.min(255, Math.round(((color >> 8) & 0xff) * factor));
        int b = Math.min(255, Math.round((color & 0xff) * factor));
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static int darken(int color, float factor) {
        int r = Math.round(((color >> 16) & 0xff) * factor);
        int g = Math.round(((color >> 8) & 0xff) * factor);
        int b = Math.round((color & 0xff) * factor);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }
}
