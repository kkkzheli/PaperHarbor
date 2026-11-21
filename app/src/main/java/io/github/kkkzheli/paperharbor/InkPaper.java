package io.github.kkkzheli.paperharbor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import org.json.JSONObject;

/** Draws the non-ink layers. All distances, including typography, are page units, not dp/sp. */
final class InkPaper {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint type = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Rect clip = new Rect();
    private final RectF destination = new RectF();
    private String lastText;
    private int lastWidth, lastLines;
    private StaticLayout layout;

    InkPaper() {
        type.setColor(0xff25242a); type.setTextSize(14); type.density = 1;
    }

    // Caller clips to the page before invoking.
    void draw(Canvas c, JSONObject page, int w, int h, Bitmap background) {
        paint.setColor(Color.WHITE);
        c.drawRect(0, 0, w, h, paint);
        String paper = page.optString("paper", "plain");
        boolean grid = "grid".equals(paper);
        if ((grid || "ruled".equals(paper)) && c.getClipBounds(clip)) {
            int step = grid ? 18 : 24;
            paint.setColor(0xffdce2e6); paint.setStrokeWidth(.6f);
            long startY = Math.max(1L, Math.floorDiv(clip.top, step)) * step;
            for (long y=startY; y<=(long)Math.min(clip.bottom,h); y+=step) c.drawLine(0,y,w,y,paint);
            if (grid) {
                long startX = Math.max(1L, Math.floorDiv(clip.left, step)) * step;
                for (long x=startX; x<=(long)Math.min(clip.right,w); x+=step) c.drawLine(x,0,x,h,paint);
            }
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
        String text = page.optString("text", "");
        if (text.isEmpty()) { layout = null; lastText = null; return; }
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
}
