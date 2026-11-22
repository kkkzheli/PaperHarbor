/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation.
 */
package io.github.kkkzheli.paperharbor;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A block of typed text that lives on the page, with its own font, size, colour and emphasis.
 *
 * <p>Page coordinates and page units throughout, so a block keeps its place in the PDF, in the PNG
 * export and in page thumbnails. {@code w} is the wrap width; zero means the block grows with its
 * longest line. The layout is cached until one of its inputs changes, because a page can be drawn
 * many times per second while the book is panned.
 */
final class PageText {
    static final String[] FONTS = {"serif", "sans", "mono", "hand"};
    static final String[] FONT_NAMES = {"衬线", "无衬线", "等宽", "手写"};
    static final float MIN_SIZE = 8f, MAX_SIZE = 160f;

    float x, y, w;
    float size = 22f;
    int argb = 0xff22272e;
    String text = "";
    /** LaTeX source when this block is a formula; empty means ordinary text. */
    String tex = "";
    String font = "serif";
    boolean bold, italic, underline;
    int align; // 0 left, 1 centre, 2 right

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private StaticLayout layout;
    private String cachedText;
    private float cachedSize, cachedW;
    private int cachedArgb;
    private String cachedFont;
    private boolean cachedBold, cachedItalic, cachedUnderline;
    private int cachedAlign;
    private final RectF box = new RectF();

    PageText() { paint.density = 1; }

    static PageText of(float x, float y, String text, float size, int argb, String font,
                       boolean bold, boolean italic, boolean underline, int align, float wrap) {
        PageText t = new PageText();
        t.x = x; t.y = y; t.text = text == null ? "" : text;
        t.size = clamp(size, MIN_SIZE, MAX_SIZE);
        t.argb = argb;
        t.font = fontName(font);
        t.bold = bold; t.italic = italic; t.underline = underline;
        t.align = align < 0 || align > 2 ? 0 : align;
        t.w = Math.max(0, wrap);
        return t;
    }

    static String fontName(String value) {
        if (value == null) return "serif";
        for (String candidate : FONTS) if (candidate.equals(value)) return candidate;
        return "serif";
    }

    static String label(String font) {
        switch (fontName(font)) {
            case "sans": return "无衬线";
            case "mono": return "等宽";
            case "hand": return "手写";
            default: return "衬线";
        }
    }

    static Typeface typeface(String font, boolean bold, boolean italic) {
        String family;
        switch (fontName(font)) {
            case "sans": family = "sans-serif"; break;
            case "mono": family = "monospace"; break;
            case "hand": family = "casual"; break;
            default: family = "serif";
        }
        int style = bold && italic ? Typeface.BOLD_ITALIC : bold ? Typeface.BOLD
            : italic ? Typeface.ITALIC : Typeface.NORMAL;
        Typeface base = Typeface.create(family, Typeface.NORMAL);
        return Typeface.create(base, style);
    }

    PageText copy() {
        PageText t = of(x, y, text, size, argb, font, bold, italic, underline, align, w);
        t.tex = tex;
        return t;
    }

    /** Marks this block as a formula; the page then typesets {@code source} instead of the text. */
    void formula(String source) { tex = source == null ? "" : source; }

    boolean isFormula() { return tex != null && !tex.isEmpty(); }

    private void prepareMathPaint() {
        paint.setTextSize(size);
        paint.setColor(argb);
        paint.setTypeface(typeface(font, bold, italic));
        paint.setUnderlineText(false);
        paint.setStyle(Paint.Style.FILL);
    }

    void move(float dx, float dy) { x += dx; y += dy; layout = null; }

    private StaticLayout build() {
        if (layout != null && cachedText != null && cachedText.equals(text)
                && cachedSize == size && cachedW == w && cachedArgb == argb
                && cachedFont.equals(font) && cachedBold == bold && cachedItalic == italic
                && cachedUnderline == underline && cachedAlign == align) return layout;
        paint.setTextSize(size);
        paint.setColor(argb);
        paint.setTypeface(typeface(font, bold, italic));
        paint.setUnderlineText(underline);
        int width;
        if (w > 0) width = Math.max(1, Math.round(w));
        else {
            String[] lines = text.split("\n", -1);
            float widest = 1;
            for (String line : lines) widest = Math.max(widest, paint.measureText(line));
            width = Math.max(1, (int)Math.ceil(widest) + 2);
        }
        Layout.Alignment alignment = align == 1 ? Layout.Alignment.ALIGN_CENTER
            : align == 2 ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
        layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
            .setAlignment(alignment).setIncludePad(false).build();
        cachedText = text; cachedSize = size; cachedW = w; cachedArgb = argb;
        cachedFont = font; cachedBold = bold; cachedItalic = italic;
        cachedUnderline = underline; cachedAlign = align;
        return layout;
    }

    void draw(Canvas c) {
        if (isFormula()) {
            prepareMathPaint();
            int save = c.save();
            try {
                c.translate(x, y);
                MathLayout.draw(c, paint, tex, 0, 0, size, align, w);
            } finally { c.restoreToCount(save); }
            return;
        }
        if (text == null || text.isEmpty()) return;
        StaticLayout l = build();
        int save = c.save();
        try {
            c.translate(x, y);
            l.draw(c);
        } finally { c.restoreToCount(save); }
    }

    /** Page-space bounds of the laid-out block. */
    RectF bounds() {
        if (isFormula()) {
            prepareMathPaint();
            float[] m = MathLayout.measure(tex, paint, size);
            float width = Math.max(w, m[0]);
            box.set(x, y, x + width, y + m[1] + m[2]);
            return box;
        }
        if (text == null || text.isEmpty()) { box.set(x, y, x, y); return box; }
        StaticLayout l = build();
        box.set(x, y, x + l.getWidth(), y + l.getHeight());
        return box;
    }

    boolean hit(float px, float py) {
        if (!isFormula() && (text == null || text.isEmpty())) return false;
        RectF b = new RectF(bounds());
        b.inset(-6, -4);
        return b.contains(px, py);
    }

    JSONObject encode() {
        try {
            JSONObject o = new JSONObject().put("x", (double)x).put("y", (double)y).put("w", (double)w)
                .put("size", (double)size).put("argb", argb).put("text", text)
                .put("font", font).put("bold", bold).put("italic", italic)
                .put("underline", underline).put("align", align);
            if (isFormula()) o.put("tex", tex);
            return o;
        } catch (JSONException e) { throw new IllegalStateException("Cannot encode text", e); }
    }

    static PageText decode(JSONObject o, float pageW, float pageH) {
        if (o == null) return null;
        String value = o.optString("text", "");
        if (value.length() > 20000) return null;
        PageText t = new PageText();
        t.x = (float)o.optDouble("x", 24);
        t.y = (float)o.optDouble("y", 24);
        t.w = clamp((float)o.optDouble("w", 0), 0, 20000);
        t.size = clamp((float)o.optDouble("size", 22), MIN_SIZE, MAX_SIZE);
        t.argb = o.optInt("argb", Color.BLACK);
        t.text = value;
        String formula = o.optString("tex", "");
        t.tex = formula.length() > 20000 ? "" : formula;
        t.font = fontName(o.optString("font", "serif"));
        t.bold = o.optBoolean("bold");
        t.italic = o.optBoolean("italic");
        t.underline = o.optBoolean("underline");
        t.align = Math.max(0, Math.min(2, o.optInt("align", 0)));
        if (!Float.isFinite(t.x) || !Float.isFinite(t.y)) return null;
        t.x = clamp(t.x, -pageW, 2 * pageW);
        t.y = clamp(t.y, -pageH, 2 * pageH);
        return t;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
