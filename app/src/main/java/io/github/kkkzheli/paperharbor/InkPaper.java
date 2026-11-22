/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation.
 */
package io.github.kkkzheli.paperharbor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import org.json.JSONObject;

/** Draws the non-ink layers. All distances, including typography, are page units, not dp/sp. */
final class InkPaper {
    /** Paper colours offered when a note or page is created. */
    static final int[] TINTS = {0xfffffdf8, 0xfff6efdd, 0xffeef3ec, 0xffeef1f8, 0xfff2f2f2, 0xff23252a};
    /** Template patterns; the first four are the originals plus the dot grid. */
    static final String[] PATTERNS = {"ruled", "grid", "dot", "plain", "cornell"};
    static final String[] PATTERN_NAMES = {"横线", "方格", "点阵", "空白", "康奈尔"};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint type = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint docType = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private StaticLayout docLayout;
    private String lastDoc;
    private int lastDocWidth, lastDocColor;
    private final Rect clip = new Rect();
    private final RectF destination = new RectF();
    private String lastText;
    private int lastWidth, lastLines, lastColor;
    private StaticLayout layout;

    InkPaper() {
        type.setColor(0xff25242a); type.setTextSize(14); type.density = 1;
    }

    static boolean pattern(String value) {
        if (value == null) return false;
        for (String candidate : PATTERNS) if (candidate.equals(value)) return true;
        return false;
    }

    static int label(String pattern) {
        switch (pattern == null ? "" : pattern) {
            case "grid": return 1;
            case "dot": return 2;
            case "plain": return 3;
            case "cornell": return 4;
            default: return 0;
        }
    }

    static boolean darkTint(int tint) {
        // Rec. 709 luma, 0–1.
        return (.2126f * Color.red(tint) + .7152f * Color.green(tint) + .0722f * Color.blue(tint)) / 255f < .45f;
    }

    // Caller clips to the page before invoking.
    void draw(Canvas c, JSONObject page, int w, int h, Bitmap background) {
        int tint = page.has("tint") ? page.optInt("tint", Ui.PAPER) : Ui.PAPER;
        boolean darkTint = darkTint(tint);
        paint.setColor(tint);
        c.drawRect(0, 0, w, h, paint);
        String paper = page.optString("paper", "plain");
        if (!"plain".equals(paper) && c.getClipBounds(clip)) {
            int lineColor = darkTint ? 0x33ffffff : 0xffd9dfe2;
            paint.setColor(lineColor);
            paint.setStrokeWidth(.6f);
            if ("grid".equals(paper) || "dot".equals(paper)) {
                int step = 18;
                if ("dot".equals(paper)) {
                    paint.setStyle(Paint.Style.FILL);
                    long startY = Math.max(1L, Math.floorDiv(clip.top, step)) * step;
                    long startX = Math.max(1L, Math.floorDiv(clip.left, step)) * step;
                    for (long y = startY; y <= Math.min(clip.bottom, h); y += step)
                        for (long x = startX; x <= Math.min(clip.right, w); x += step)
                            c.drawCircle(x, y, .9f, paint);
                } else {
                    long startY = Math.max(1L, Math.floorDiv(clip.top, step)) * step;
                    long startX = Math.max(1L, Math.floorDiv(clip.left, step)) * step;
                    for (long y = startY; y <= Math.min(clip.bottom, h); y += step) c.drawLine(0, y, w, y, paint);
                    for (long x = startX; x <= Math.min(clip.right, w); x += step) c.drawLine(x, 0, x, h, paint);
                }
            } else {
                int step = "cornell".equals(paper) ? 24 : 24;
                long startY = Math.max(1L, Math.floorDiv(clip.top, step)) * step;
                for (long y = startY; y <= Math.min(clip.bottom, h); y += step) c.drawLine(0, y, w, y, paint);
                if ("cornell".equals(paper)) {
                    // A title strip and a cue column, the two lines that make the layout useful.
                    paint.setStrokeWidth(1.1f);
                    c.drawLine(0, 96, w, 96, paint);
                    c.drawLine(112, 96, 112, h, paint);
                    c.drawLine(0, Math.max(120, h - 150), w, Math.max(120, h - 150), paint);
                }
            }
            paint.setStyle(Paint.Style.FILL);
        }
        if (background != null && !background.isRecycled()) {
            float f = Math.min(w/(float)background.getWidth(), h/(float)background.getHeight());
            float bw = background.getWidth()*f, bh = background.getHeight()*f;
            destination.set((w-bw)/2, (h-bh)/2, (w+bw)/2, (h+bh)/2);
            Bitmap source = background;
            // PdfDocument and bitmap canvases cannot draw hardware-backed Bitmap instances.
            if (!c.isHardwareAccelerated() && source.getConfig() == Bitmap.Config.HARDWARE)
                source = source.copy(Bitmap.Config.ARGB_8888, false);
            paint.setColor(Color.WHITE);
            if (source != null) c.drawBitmap(source, null, destination, paint);
        }
        String doc = page.optString("docText", "");
        if (!doc.isEmpty()) drawDoc(c, w, h, doc, darkTint);
        String text = page.optString("text", "");
        if (text.isEmpty()) { layout = null; lastText = null; return; }
        int inkColor = darkTint ? 0xffe9e3d6 : 0xff25242a;
        if (lastColor != inkColor) { type.setColor(inkColor); lastColor = inkColor; layout = null; }
        float margin = Math.min(30f, Math.min(w,h)*.07f);
        int wrapWidth = Math.max(1, (int)(w-2*margin));
        int lines = Math.max(1, (int)Math.ceil(h / (double)type.getFontSpacing()) + 2);
        if (layout == null || !text.equals(lastText) || lastWidth != wrapWidth || lastLines != lines) {
            layout = StaticLayout.Builder.obtain(text,0,text.length(),type,wrapWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                    .setMaxLines(lines).build();
            lastText = text; lastWidth = wrapWidth; lastLines = lines;
        }
        int save=c.save();
        try { c.translate(margin,margin); layout.draw(c); }
        finally { c.restoreToCount(save); }
    }

    /** Text imported from a Word document; read-only here, annotated with ink and text blocks. */
    private void drawDoc(Canvas c, int w, int h, String value, boolean darkTint) {
        int color = darkTint ? 0xffece6d8 : 0xff22252a;
        int width = Math.max(1, w - 96);
        if (docLayout == null || !value.equals(lastDoc) || lastDocWidth != width || lastDocColor != color) {
            docType.setColor(color);
            docType.setTextSize(15);
            docType.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            docType.density = 1;
            docLayout = StaticLayout.Builder.obtain(value, 0, value.length(), docType, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build();
            lastDoc = value;
            lastDocWidth = width;
            lastDocColor = color;
        }
        int save = c.save();
        try {
            c.translate(48, 48);
            docLayout.draw(c);
        } finally { c.restoreToCount(save); }
    }
}
