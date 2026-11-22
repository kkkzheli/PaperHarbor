package io.github.kkkzheli.paperharbor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.LruCache;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Renders small page previews for notebook grids and pickers.
 *
 * <p>{@link #render} is synchronous and may open/read a PDF, so call it from a worker thread,
 * never the UI thread. The bitmap keeps the page aspect ratio: the longer side is exactly
 * {@code maxEdge} and the shorter side is rounded and clamped to at least one pixel, so previews
 * are never distorted. The page is drawn with {@link InkView#drawPage}; PDF-backed pages also get
 * a {@code maxEdge * 2} raster from {@link Store#background}, which is recycled before the call
 * returns. Paper pages pass {@code null}. The host owns framing, decoration and cropping.
 *
 * <p>Results live in a process-wide cache bounded to about 24 MB, measured by
 * {@link Bitmap#getByteCount()}. Entries are keyed by notebook id, page dimensions and a SHA-256
 * of the page JSON, so editing a page or changing its geometry produces a new entry; the key
 * contains no independent page index. Entries larger than the whole budget (very large
 * {@code maxEdge}) are rendered but not retained.
 *
 * <p><b>Callers must never recycle, mutate or otherwise take ownership of a returned bitmap.</b>
 * A returned instance may be the same object handed to another caller; it is shared, read-only,
 * and stays valid after eviction (evicted bitmaps are left to the garbage collector, never
 * recycled). {@link #clear()} empties the cache without recycling, so outstanding references
 * remain usable.
 *
 * <p>Cache access is synchronized. Concurrent renders of the same key may duplicate work, but a
 * partially drawn bitmap is never cached or returned: on any failure the in-progress bitmap and
 * background are recycled, the key is dropped, and the exception is rethrown.
 */
public final class Thumbnails {
    private static final int CACHE_BYTES = 24 * 1024 * 1024;
    private static final int MIN_EDGE = 64;
    private static final Object LOCK = new Object();
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    private Thumbnails() {}

    /**
     * Draws {@code page} into a new or cached ARGB_8888 bitmap whose longest edge is
     * {@code maxEdge} (at least 64). Synchronous; call on a worker thread.
     *
     * <p>The returned bitmap must not be recycled by the caller.
     */
    public static Bitmap render(Context ctx, Store store, String id, JSONObject page, int maxEdge) throws Exception {
        if (ctx == null) throw new IllegalArgumentException("Context required");
        if (store == null) throw new IllegalArgumentException("Store required");
        if (id == null) throw new IllegalArgumentException("Notebook id required");
        if (page == null) throw new IllegalArgumentException("Page required");
        if (maxEdge < MIN_EDGE) throw new IllegalArgumentException("maxEdge must be at least " + MIN_EDGE);

        int w = Math.max(1, page.optInt("w", 1));
        int h = Math.max(1, page.optInt("h", 1));
        int bw, bh;
        if (w >= h) { bw = maxEdge; bh = (int) Math.round(h * (maxEdge / (double) w)); }
        else { bh = maxEdge; bw = (int) Math.round(w * (maxEdge / (double) h)); }
        bw = Math.max(1, Math.min(maxEdge, bw));
        bh = Math.max(1, Math.min(maxEdge, bh));

        String key = key(id, w, h, page);
        synchronized (LOCK) {
            Bitmap cached = CACHE.get(key);
            if (cached != null && !cached.isRecycled()) return cached;
            if (cached != null) CACHE.remove(key);
        }

        // Only publish fully drawn bitmaps, so a failed render can never be observed by another
        // caller. Racing renders may repeat this work; the first finisher wins the cache slot.
        Bitmap bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Bitmap background = null;
        boolean complete = false;
        try {
            if (page.optInt("pdf", -1) >= 0) background = store.background(id, page, maxEdge * 2);
            Canvas canvas = new Canvas(bitmap);
            // Non-uniform by at most half a pixel: maps the page rectangle exactly onto the
            // rounded bitmap so no transparent edge remains.
            canvas.scale(bw / (float) w, bh / (float) h);
            InkView.drawPage(canvas, page, background, store, id);
            complete = true;
        } finally {
            if (background != null) background.recycle();
            if (!complete) {
                bitmap.recycle();
                synchronized (LOCK) { CACHE.remove(key); }
            }
        }

        synchronized (LOCK) {
            Bitmap raced = CACHE.get(key);
            if (raced != null && !raced.isRecycled()) {
                // Another thread finished this key first; no caller has seen ours, so drop it.
                bitmap.recycle();
                return raced;
            }
            CACHE.put(key, bitmap);
            return bitmap;
        }
    }

    /** Drops every cached preview. Bitmaps are not recycled because callers may still show them. */
    public static void clear() {
        synchronized (LOCK) { CACHE.evictAll(); }
    }

    private static String key(String id, int w, int h, JSONObject page) {
        String text = page.toString();
        if (text == null) text = "page@" + System.identityHashCode(page);
        return id + ':' + w + 'x' + h + ':' + digest(text);
    }

    private static String digest(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; ++i) {
                int v = bytes[i] & 0xff;
                hex[i * 2] = Character.forDigit(v >>> 4, 16);
                hex[i * 2 + 1] = Character.forDigit(v & 0xf, 16);
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException e) {
            return "raw:" + text;
        }
    }
}
