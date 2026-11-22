/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation.
 */
package io.github.kkkzheli.paperharbor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.LruCache;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * A picture placed on the page. The pixels live in the notebook folder as {@code images/img-N.png},
 * so the note stays a plain folder and a backup carries every picture with it.
 *
 * <p>The decoded bitmap is cached process-wide by notebook and path. {@link #prefetch} warms that
 * cache from the page-loading worker so the first frame never decodes on the UI thread.
 */
final class PageImage {
    static final Pattern PATH = Pattern.compile("images/img-[1-9][0-9]{0,3}\\.png");
    private static final int CACHE_BYTES = 32 * 1024 * 1024;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    float x, y, w, h;
    String path;

    static PageImage of(float x, float y, float w, float h, String path) {
        PageImage i = new PageImage();
        i.x = x; i.y = y; i.w = Math.max(1, w); i.h = Math.max(1, h); i.path = path;
        return i;
    }

    PageImage copy() { return of(x, y, w, h, path); }
    void move(float dx, float dy) { x += dx; y += dy; }

    RectF bounds() { return new RectF(x, y, x + w, y + h); }

    boolean hit(float px, float py) { return bounds().contains(px, py); }

    void draw(Canvas c, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        c.drawBitmap(bitmap, null, new RectF(x, y, x + w, y + h), PAINT);
    }

    JSONObject encode() {
        try {
            return new JSONObject().put("x", (double)x).put("y", (double)y)
                .put("w", (double)w).put("h", (double)h).put("path", path);
        } catch (JSONException e) { throw new IllegalStateException("Cannot encode image", e); }
    }

    static PageImage decode(JSONObject o) {
        if (o == null) return null;
        String p = o.optString("path", "");
        if (!PATH.matcher(p).matches()) return null;
        float x = (float)o.optDouble("x"), y = (float)o.optDouble("y");
        float w = (float)o.optDouble("w"), h = (float)o.optDouble("h");
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(w) || !Float.isFinite(h)) return null;
        if (w <= 0 || h <= 0 || w > 20000 || h > 20000) return null;
        return of(x, y, w, h, p);
    }

    /** Decoded bitmap for one stored picture, or null when it is missing or unreadable. */
    static Bitmap load(Store store, String id, String path) {
        if (store == null || id == null || path == null) return null;
        String key = id + "/" + path;
        Bitmap cached = CACHE.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        try {
            Bitmap bitmap = store.image(id, path);
            if (bitmap != null) CACHE.put(key, bitmap);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    /** Warm the cache for every picture on a page. Call from a worker thread. */
    static void prefetch(Store store, String id, JSONObject page) {
        if (page == null) return;
        org.json.JSONArray images = page.optJSONArray("images");
        if (images == null) return;
        for (int i = 0; i < images.length(); i++) {
            PageImage image = decode(images.optJSONObject(i));
            if (image != null) load(store, id, image.path);
        }
    }

    static void clear() { CACHE.evictAll(); }
}
