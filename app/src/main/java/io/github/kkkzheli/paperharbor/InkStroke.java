/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation.
 */
package io.github.kkkzheli.paperharbor;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Page-space geometry shared by input, hit testing, history and rendering.
 *
 * <p>Tools: {@code pen} (pressure-tapered), {@code brush} (speed- and pressure-tapered),
 * {@code marker} (flat translucent highlight with its own opacity) and shapes
 * ({@code line}, {@code rect}, or {@code shape} with a {@code kind}). The third sample value holds
 * pressure for the pen, the stored width factor for the brush and is unused for the marker, so a
 * reloaded page redraws exactly what was written.
 *
 * <p>Freehand outlines are built as one ribbon: the centreline is smoothed, each vertex takes the
 * averaged normal of its two segments with a clamped miter and a curvature-dependent width, and
 * round caps close both ends. That keeps a hand-drawn curve free of the spikes a naive per-segment
 * quad produces where the direction changes quickly.
 */
final class InkStroke {
    static final String[] SHAPES = {"line", "arrow", "rect", "circle", "ellipse",
        "triangle", "diamond", "pentagon", "star", "hexagon"};

    final String tool;
    final String kind;
    final int argb;
    final float width;
    final RectF bounds = new RectF(); // sample coordinates; brush radii are additional
    private final Path path = new Path();
    private float[] xyz = new float[24];
    private int count;
    private float markerAlpha = .38f;
    private boolean dirty = true;

    InkStroke(String tool, int argb, float width) {
        this(tool, null, argb, width);
    }

    InkStroke(String tool, String kind, int argb, float width) {
        this.tool = tool;
        this.kind = normalize(kind);
        this.argb = argb;
        this.width = width;
    }

    private String normalize(String value) {
        if (tool.equals("shape")) return shapeKind(value) ? value : "rect";
        if (tool.equals("line") || tool.equals("rect")) return tool;
        return null;
    }

    static boolean shapeKind(String kind) {
        if (kind == null) return false;
        for (String candidate : SHAPES) if (candidate.equals(kind)) return true;
        return false;
    }

    /** The persisted tool names this build can draw. */
    static boolean drawable(String tool) {
        return "pen".equals(tool) || "brush".equals(tool) || "marker".equals(tool)
            || "line".equals(tool) || "rect".equals(tool) || "shape".equals(tool);
    }

    boolean isShape() { return "shape".equals(tool) || "line".equals(tool) || "rect".equals(tool); }

    /** Sub-type used for drawing; legacy line/rect entries map onto the same shapes. */
    String shape() {
        if ("line".equals(tool)) return "line";
        if ("rect".equals(tool)) return "rect";
        return kind == null ? "rect" : kind;
    }

    /** Marker opacity, 0.05–1. Ignored by every other tool. */
    void markerAlpha(float value) {
        if (Float.isFinite(value)) markerAlpha = clamp(value, .05f, 1f);
        dirty = true;
    }

    float markerAlpha() { return markerAlpha; }

    int count() { return count; }
    float x(int i) { return xyz[3*i]; }
    float y(int i) { return xyz[3*i + 1]; }
    float value(int i) { return xyz[3*i + 2]; }

    /** Width multiplier at sample i. Brush values are stored factors, pen values are pressure. */
    private float factor(int i) {
        if ("brush".equals(tool)) return Math.max(.05f, xyz[3*i + 2]);
        if ("pen".equals(tool)) return .35f + .65f * xyz[3*i + 2];
        return 1f;
    }
    private float radius(int i) { return width * .5f * factor(i); }

    private float lastFactor = 1f;
    private long lastTime;
    private float speed;

    /**
     * Live input. The brush turns stroke speed and pressure into its stored width factor; the
     * factor is low-pass filtered so a change of speed reads as a swell rather than a step.
     *
     * <p>Speed is measured from the event timestamps, not from the distance between two samples.
     * Touch panels report samples every few milliseconds, so a per-sample distance is tiny even
     * during a fast flick and the brush would read as "slow" for every stroke — which is why it
     * used to look exactly like the hard pen.
     */
    void sample(float x, float y, float p) { sample(x, y, p, 0); }

    void sample(float x, float y, float p, long time) {
        if ("brush".equals(tool)) {
            if (count > 0 && time > 0 && lastTime > 0) {
                long dt = time - lastTime;
                if (dt > 0 && dt < 400) {
                    double distance = Math.hypot(x - x(count-1), y - y(count-1));
                    float instant = (float)(distance * 1000.0 / dt);
                    speed = speed <= 0 ? instant : speed * .80f + instant * .20f;
                }
            }
            if (time > 0) lastTime = time;
            float target;
            if (p < .999f) target = .55f + .85f * p;                   // real stylus pressure
            else {
                // About 1.40× when moving slowly, 0.75× in a fast flick, 1.0× around a
                // comfortable writing pace of 500 page units per second.
                target = .60f + .80f / (1f + speed / 500f);
            }
            // The first sample has no speed yet; starting neutral avoids a fat blob where the
            // brush lands and then thins out as the hand gets moving.
            lastFactor = count == 0 ? (p < .999f ? target : 1f)
                : lastFactor * .90f + target * .10f;
            p = clamp(lastFactor, .50f, 1.45f);
            lastFactor = p;
        }
        sampleRaw(x, y, p);
    }

    /** Stores an already finalised sample value. Used by decode, transforms and splits. */
    void sampleRaw(float x, float y, float p) {
        if (isShape() && count == 2) count = 1; // replace preview endpoint
        if (!isShape() && count > 0 && Math.hypot(x - x(count-1), y - y(count-1)) < .35f
                && !"marker".equals(tool)) return; // drop duplicate and tremor-level samples
        if (count * 3 == xyz.length) xyz = Arrays.copyOf(xyz, xyz.length * 2);
        int i = count++;
        xyz[i*3] = x; xyz[i*3+1] = y; xyz[i*3+2] = p;
        if (i == 0 || isShape()) bounds.set(x(0), y(0), x(0), y(0));
        bounds.left = Math.min(bounds.left, x); bounds.top = Math.min(bounds.top, y);
        bounds.right = Math.max(bounds.right, x); bounds.bottom = Math.max(bounds.bottom, y);
        lastFactor = p;
        dirty = true;
    }

    /** Softens the ends and evens out the width and centreline before the stroke is committed. */
    void taper() {
        if (count < 4 || isShape() || "marker".equals(tool)) { dirty = true; return; }
        smoothValues("brush".equals(tool) ? 4 : 2);
        if ("brush".equals(tool)) {
            // A brush is put down and lifted gradually. The ramps are proportional to the
            // number of samples so a quick flick does not turn into one long thin needle.
            int head = Math.min(6, Math.max(1, count / 8));
            for (int i = 0; i < head; i++) {
                float t = (i + 1f) / head;
                xyz[3*i + 2] *= .55f + .45f * t;
            }
            int tail = Math.min(10, Math.max(2, count / 6));
            for (int k = 0; k < tail; k++) {
                float t = 1f - (k + 1f) / tail;
                xyz[3*(count - tail + k) + 2] *= .45f + .55f * t;
            }
        } else {
            int[] head = {0, 1};
            for (int i : head) if (i < count) xyz[3*i + 2] *= i == 0 ? .55f : .8f;
            int[] tail = {count - 3, count - 2, count - 1};
            float[] scale = {.75f, .58f, .4f};
            for (int i = 0; i < tail.length; i++) xyz[3*tail[i] + 2] *= scale[i];
        }
        // The brush is a soft medium: extra passes round off the corners that sparse touch
        // samples leave in a thick curve, while the pen keeps its crisp corners.
        smoothPoints("brush".equals(tool) ? 4 : 2);
        dirty = true;
    }

    private void smoothValues(int passes) {
        if (count < 3) return;
        float[] v = new float[count];
        for (int pass = 0; pass < passes; pass++) {
            for (int i = 0; i < count; i++) {
                if (i == 0 || i == count - 1) v[i] = xyz[3*i + 2];
                else v[i] = (xyz[3*(i-1) + 2] + 2 * xyz[3*i + 2] + xyz[3*(i+1) + 2]) * .25f;
            }
            for (int i = 0; i < count; i++) xyz[3*i + 2] = v[i];
        }
    }

    /** Gentle passes over interior samples only; the endpoints never move. */
    private void smoothPoints(int passes) {
        if (count < 3) return;
        float[] px = new float[count], py = new float[count];
        for (int pass = 0; pass < passes; pass++) {
            for (int i = 0; i < count; i++) {
                if (i == 0 || i == count - 1) { px[i] = x(i); py[i] = y(i); }
                else {
                    px[i] = (x(i-1) + 2 * x(i) + x(i+1)) * .25f;
                    py[i] = (y(i-1) + 2 * y(i) + y(i+1)) * .25f;
                }
            }
            for (int i = 0; i < count; i++) { xyz[3*i] = px[i]; xyz[3*i+1] = py[i]; }
            bounds.set(x(0), y(0), x(0), y(0));
            for (int i = 1; i < count; i++) {
                bounds.left = Math.min(bounds.left, x(i)); bounds.top = Math.min(bounds.top, y(i));
                bounds.right = Math.max(bounds.right, x(i)); bounds.bottom = Math.max(bounds.bottom, y(i));
            }
        }
    }

    /**
     * Rebuilds the filled outline: one smooth ribbon, with the half-width damped where the stroke
     * turns sharply so a loaded brush never folds over itself into a spike.
     */
    private void rebuild() {
        path.rewind();
        int n = count;
        if (n == 0) return;
        if ("marker".equals(tool)) {
            for (int i = 0; i < n; i++) {
                if (i == 0) path.moveTo(x(i), y(i)); else path.lineTo(x(i), y(i));
            }
            return;
        }
        if (n == 1) {
            path.addCircle(x(0), y(0), Math.max(.05f, radius(0)), Path.Direction.CW);
            return;
        }
        boolean brush = "brush".equals(tool);
        float[] px = new float[n], py = new float[n], pr = new float[n];
        for (int i = 0; i < n; i++) {
            px[i] = x(i); py[i] = y(i); pr[i] = radius(i);
        }
        // A thick brush drawn from sparse samples is a chain of straight facets. Refine the
        // centreline with Chaikin corner cutting, keeping both endpoints, so a curve stays a
        // curve even when the touch panel reported only a few points.
        if (brush && n >= 3) {
            float length = 0;
            for (int i = 1; i < n; i++) length += (float)Math.hypot(px[i] - px[i-1], py[i] - py[i-1]);
            float average = length / Math.max(1, n - 1);
            int rounds = average > 8 ? 2 : average > 3 ? 1 : 0;
            for (int round = 0; round < rounds; round++) {
                int m = px.length;
                float[] nx = new float[2 * m], ny = new float[2 * m], nr = new float[2 * m];
                nx[0] = px[0]; ny[0] = py[0]; nr[0] = pr[0];
                int k = 1;
                for (int i = 0; i < m - 1; i++) {
                    nx[k] = px[i] * .75f + px[i+1] * .25f;
                    ny[k] = py[i] * .75f + py[i+1] * .25f;
                    nr[k] = pr[i] * .75f + pr[i+1] * .25f;
                    k++;
                    nx[k] = px[i] * .25f + px[i+1] * .75f;
                    ny[k] = py[i] * .25f + py[i+1] * .75f;
                    nr[k] = pr[i] * .25f + pr[i+1] * .75f;
                    k++;
                }
                nx[k] = px[m-1]; ny[k] = py[m-1]; nr[k] = pr[m-1];
                px = nx; py = ny; pr = nr;
            }
            n = px.length;
        }
        float[] lx = new float[n], ly = new float[n], rx = new float[n], ry = new float[n];
        for (int i = 0; i < n; i++) {
            float inx, iny, outx, outy;
            if (i == 0) { inx = outx = px[1] - px[0]; iny = outy = py[1] - py[0]; }
            else if (i == n - 1) {
                inx = outx = px[n-1] - px[n-2]; iny = outy = py[n-1] - py[n-2];
            } else {
                inx = px[i] - px[i-1]; iny = py[i] - py[i-1];
                outx = px[i+1] - px[i]; outy = py[i+1] - py[i];
            }
            float lin = (float)Math.hypot(inx, iny), lout = (float)Math.hypot(outx, outy);
            if (lin < 1e-4f) { inx = outx; iny = outy; lin = lout; }
            if (lout < 1e-4f) { outx = inx; outy = iny; lout = lin; }
            if (lin < 1e-4f) {
                lx[i] = rx[i] = px[i]; ly[i] = ry[i] = py[i];
                continue;
            }
            float n0x = -iny / lin, n0y = inx / lin;
            float n1x = -outy / lout, n1y = outx / lout;
            float mx = n0x + n1x, my = n0y + n1y;
            float ml = (float)Math.hypot(mx, my);
            if (ml < 1e-3f) { mx = n1x; my = n1y; } else { mx /= ml; my /= ml; }
            float dot = mx * n1x + my * n1y;
            // A loaded brush should not grow a spike where the hand turns; the pen keeps a
            // sharper miter because that is what gives it its crisp corner.
            float miter = Math.min(brush ? 1.25f : 2.4f, 1f / Math.max(.42f, Math.abs(dot)));
            float cos = clamp(n0x * n1x + n0y * n1y, -1f, 1f);
            float turn = (float)Math.acos(cos);
            float damp = 1f - (brush ? .26f : .40f) * (turn / (float)Math.PI);
            float r = pr[i] * damp;
            lx[i] = px[i] + mx * r * miter; ly[i] = py[i] + my * r * miter;
            rx[i] = px[i] - mx * r * miter; ry[i] = py[i] - my * r * miter;
        }
        // One continuous contour: the two long sides and a semicircular cap at each end. The
        // caps are part of the contour, not separate circles, so the overlap never punches a
        // hole through the end of a thick stroke.
        path.setFillType(Path.FillType.WINDING);
        path.moveTo(lx[0], ly[0]);
        for (int i = 1; i < n; i++) path.lineTo(lx[i], ly[i]);
        capArc(px[n-1], py[n-1], lx[n-1], ly[n-1]);
        for (int i = n - 2; i >= 0; i--) path.lineTo(rx[i], ry[i]);
        capArc(px[0], py[0], rx[0], ry[0]);
        path.close();
    }

    /** Half-turn around the endpoint, from the current side of the ribbon to the other one. */
    private void capArc(float cx, float cy, float fromX, float fromY) {
        float r = (float)Math.hypot(fromX - cx, fromY - cy);
        if (r < .25f) { path.lineTo(2 * cx - fromX, 2 * cy - fromY); return; }
        RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);
        float start = (float)Math.toDegrees(Math.atan2(fromY - cy, fromX - cx));
        path.arcTo(oval, start, -180f, false);
    }

    void draw(Canvas c, Paint paint) {
        if (count == 0) return;
        if (dirty) { rebuild(); dirty = false; }
        paint.setColor(argb);
        paint.setStyle(Paint.Style.FILL);
        int baseAlpha = Color.alpha(argb);
        paint.setAlpha("marker".equals(tool) ? Math.round(baseAlpha * markerAlpha) : baseAlpha);
        if (!isShape()) {
            if ("marker".equals(tool)) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(width);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
            }
            c.drawPath(path, paint);
            return;
        }
        if (count == 1 || (x(0) == x(1) && y(0) == y(1))) {
            c.drawCircle(x(0), y(0), width * .5f, paint); return;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        c.drawPath(outline(), paint);
    }

    /** The drawn outline in page space; shapes are rebuilt from their two corner samples. */
    Path outline() {
        Path p = new Path();
        if (!isShape()) { ensurePath(); p.addPath(path); return p; }
        float ax = x(0), ay = y(0), bx = x(1), by = y(1);
        String s = shape();
        if ("line".equals(s)) { p.moveTo(ax, ay); p.lineTo(bx, by); return p; }
        float l = Math.min(ax, bx), t = Math.min(ay, by), r = Math.max(ax, bx), b = Math.max(ay, by);
        float cx = (l + r) * .5f, cy = (t + b) * .5f;
        switch (s) {
            case "rect": p.addRect(l, t, r, b, Path.Direction.CW); break;
            case "ellipse": p.addOval(l, t, r, b, Path.Direction.CW); break;
            case "circle": {
                float half = Math.min(r - l, b - t) * .5f;
                p.addCircle(cx, cy, half, Path.Direction.CW); break;
            }
            case "triangle":
                p.moveTo(cx, t); p.lineTo(r, b); p.lineTo(l, b); p.close(); break;
            case "diamond":
                p.moveTo(cx, t); p.lineTo(r, cy); p.lineTo(cx, b); p.lineTo(l, cy); p.close(); break;
            case "arrow":
                p.moveTo(ax, ay); p.lineTo(bx, by);
                arrowHead(p, ax, ay, bx, by);
                break;
            case "pentagon": polygon(p, cx, cy, (r - l) * .5f, (b - t) * .5f, 5, -90); break;
            case "hexagon": polygon(p, cx, cy, (r - l) * .5f, (b - t) * .5f, 6, -90); break;
            case "star": star(p, cx, cy, (r - l) * .5f, (b - t) * .5f); break;
            default: p.addRect(l, t, r, b, Path.Direction.CW);
        }
        return p;
    }

    private void ensurePath() { if (dirty) { rebuild(); dirty = false; } }

    private static void arrowHead(Path p, float ax, float ay, float bx, float by) {
        double angle = Math.atan2(by - ay, bx - ax);
        double len = Math.hypot(bx - ax, by - ay);
        float size = (float)Math.max(6, Math.min(len * .32, 40));
        for (int side = 1; side >= -1; side -= 2) {
            double a = angle + Math.PI + side * .42;
            p.moveTo(bx, by);
            p.lineTo(bx + (float)(Math.cos(a) * size), by + (float)(Math.sin(a) * size));
        }
    }

    private static void polygon(Path p, float cx, float cy, float rx, float ry, int sides, float startDeg) {
        for (int i = 0; i < sides; i++) {
            double a = Math.toRadians(startDeg + 360.0 * i / sides);
            float x = cx + (float)(Math.cos(a) * rx), y = cy + (float)(Math.sin(a) * ry);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();
    }

    private static void star(Path p, float cx, float cy, float rx, float ry) {
        for (int i = 0; i < 10; i++) {
            double a = Math.toRadians(-90 + 36.0 * i);
            double k = i % 2 == 0 ? 1 : .42;
            float x = cx + (float)(Math.cos(a) * rx * k), y = cy + (float)(Math.sin(a) * ry * k);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();
    }

    InkStroke shifted(float dx, float dy, float w, float h) {
        InkStroke s = copy();
        for (int i=0; i<count; i++)
            s.sampleRaw(clamp(x(i)+dx, 0, w), clamp(y(i)+dy, 0, h), value(i));
        return s;
    }

    InkStroke copy() {
        InkStroke s = new InkStroke(tool, kind, argb, width);
        s.markerAlpha = markerAlpha;
        return s;
    }

    /** Uniform scale and rotation about (cx, cy). Points may leave the page; the canvas clips. */
    InkStroke transformed(float cx, float cy, float k, float angle) {
        InkStroke s = copy();
        double cos = Math.cos(angle), sin = Math.sin(angle);
        for (int i=0; i<count; i++) {
            double dx = (x(i) - cx) * k, dy = (y(i) - cy) * k;
            s.sampleRaw(cx + (float)(dx*cos - dy*sin), cy + (float)(dx*sin + dy*cos), value(i));
        }
        return s;
    }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(v, hi)); }

    JSONObject encode() {
        try {
            JSONArray pts = new JSONArray();
            for (int i=0; i<count; i++) pts.put(new JSONArray().put(x(i)).put(y(i)).put(value(i)));
            JSONObject o = new JSONObject().put("tool", tool).put("argb", argb)
                .put("width", (double)width).put("points", pts);
            if ("shape".equals(tool)) o.put("shape", shape());
            if ("marker".equals(tool)) o.put("alpha", (double)markerAlpha);
            return o;
        } catch (JSONException e) { throw new IllegalStateException("Non-finite ink sample", e); }
    }

    static InkStroke decode(JSONObject obj, float w, float h) {
        if (obj == null) return null;
        String tool = obj.optString("tool");
        String kind = obj.optString("shape", null);
        float width = (float)obj.optDouble("width");
        JSONArray pts = obj.optJSONArray("points");
        if (!drawable(tool) || !Float.isFinite(width) || width <= 0 || pts == null || pts.length() == 0) return null;
        InkStroke s = new InkStroke(tool, kind, obj.optInt("argb", Color.BLACK), width);
        if ("marker".equals(tool)) s.markerAlpha((float)obj.optDouble("alpha", .38));
        if (s.isShape() && pts.length() != 2) return null;
        float lo = -Math.max(w, h), hi = 2 * Math.max(w, h);
        for (int i=0; i<pts.length(); i++) {
            JSONArray a = pts.optJSONArray(i);
            if (a == null) return null;
            float x = (float)a.optDouble(0), y = (float)a.optDouble(1), p = (float)a.optDouble(2, 1);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(p)) return null;
            s.sampleRaw(clamp(x, lo, hi), clamp(y, lo, hi), clamp(p, 0, 2.5f));
        }
        return s;
    }

    /** Does any part of this rendered stroke meet the swept disk AB? Includes point/dot contacts. */
    boolean hits(float ax, float ay, float bx, float by, float er) {
        float pad = width*.5f + er;
        if (count == 0 || Math.min(ax,bx) > bounds.right+pad || Math.max(ax,bx) < bounds.left-pad ||
                Math.min(ay,by) > bounds.bottom+pad || Math.max(ay,by) < bounds.top-pad) return false;
        if (count == 1) return nearPoint(x(0),y(0),ax,ay,bx,by,radius(0)+er);
        if (isShape()) return hitShape(ax, ay, bx, by, er);
        for (int i=1; i<count; i++) {
            float cx=x(i-1), cy=y(i-1), dx=x(i), dy=y(i), r0=radius(i-1), r1=radius(i);
            if (!nearSegments(ax,ay,bx,by,cx,cy,dx,dy,Math.max(r0,r1)+er)) continue;
            if (r0 == r1 || nearPoint(cx,cy,ax,ay,bx,by,r0+er) || nearPoint(dx,dy,ax,ay,bx,by,r1+er)) return true;
            // Unequal radii: test the sides of the actual tapered quadrilateral, not a fat capsule.
            double len=Math.hypot(dx-cx,dy-cy), ux=(dx-cx)/len, uy=(dy-cy)/len;
            double along=(ax-cx)*ux+(ay-cy)*uy, perp=(ay-cy)*ux-(ax-cx)*uy;
            if (along>=0 && along<=len && Math.abs(perp)<=r0+(r1-r0)*(along/len)) return true;
            if (nearSegments(ax,ay,bx,by,cx-uy*r0,cy+ux*r0,dx-uy*r1,dy+ux*r1,er) ||
                    nearSegments(ax,ay,bx,by,cx+uy*r0,cy-ux*r0,dx+uy*r1,dy+ux*r1,er)) return true;
        }
        return false;
    }

    private boolean hitShape(float ax, float ay, float bx, float by, float er) {
        String s = shape();
        float pad = width * .5f + er;
        if ("line".equals(s) || "arrow".equals(s))
            return nearSegments(ax, ay, bx, by, x(0), y(0), x(1), y(1), pad);
        float l = bounds.left, r = bounds.right, t = bounds.top, b = bounds.bottom;
        if (Math.min(ax,bx) > r+pad || Math.max(ax,bx) < l-pad ||
            Math.min(ay,by) > b+pad || Math.max(ay,by) < t-pad) return false;
        float cx = (l+r)*.5f, cy = (t+b)*.5f;
        float[] poly = outlinePolygon(s, l, t, r, b, cx, cy);
        for (int i = 0; i + 3 < poly.length; i += 2)
            if (nearSegments(ax,ay,bx,by,poly[i],poly[i+1],poly[i+2],poly[i+3],pad)) return true;
        return false;
    }

    /** Flat [x0,y0,x1,y1,...] outline used for shape hit testing. */
    private static float[] outlinePolygon(String s, float l, float t, float r, float b, float cx, float cy) {
        switch (s) {
            case "circle": {
                float half = Math.min(r - l, b - t) * .5f;
                return ellipsePolygon(cx, cy, half, half);
            }
            case "ellipse": return ellipsePolygon(cx, cy, (r-l)*.5f, (b-t)*.5f);
            case "triangle": return new float[] {cx, t, r, b, l, b, cx, t};
            case "diamond": return new float[] {cx, t, r, cy, cx, b, l, cy, cx, t};
            case "pentagon": return regularPolygon(cx, cy, (r-l)*.5f, (b-t)*.5f, 5, -90);
            case "hexagon": return regularPolygon(cx, cy, (r-l)*.5f, (b-t)*.5f, 6, -90);
            case "star": return starPolygon(cx, cy, (r-l)*.5f, (b-t)*.5f);
            default: return new float[] {l, t, r, t, r, b, l, b, l, t};
        }
    }

    private static float[] ellipsePolygon(float cx, float cy, float rx, float ry) {
        float[] out = new float[50];
        for (int i = 0; i <= 24; i++) {
            double a = 2 * Math.PI * i / 24;
            out[i*2] = cx + (float)(Math.cos(a) * rx);
            out[i*2+1] = cy + (float)(Math.sin(a) * ry);
        }
        return out;
    }

    private static float[] regularPolygon(float cx, float cy, float rx, float ry, int sides, float start) {
        float[] out = new float[(sides + 1) * 2];
        for (int i = 0; i <= sides; i++) {
            double a = Math.toRadians(start + 360.0 * i / sides);
            out[i*2] = cx + (float)(Math.cos(a) * rx);
            out[i*2+1] = cy + (float)(Math.sin(a) * ry);
        }
        return out;
    }

    private static float[] starPolygon(float cx, float cy, float rx, float ry) {
        float[] out = new float[22];
        for (int i = 0; i <= 10; i++) {
            double a = Math.toRadians(-90 + 36.0 * i);
            double k = i % 2 == 0 ? 1 : .42;
            out[i*2] = cx + (float)(Math.cos(a) * rx * k);
            out[i*2+1] = cy + (float)(Math.sin(a) * ry * k);
        }
        return out;
    }

    /**
     * Splits this stroke around the swept eraser disk. Returns null when nothing was removed, an
     * empty list when the whole stroke was covered, otherwise the surviving fragments.
     *
     * <p>Shapes used to be removed whole; now their outline is sampled and cut like any other
     * stroke, so erasing across one side of a circle removes just that arc. The surviving pieces
     * are stored as freehand ink because a shape is defined by exactly two corner samples.
     */
    List<InkStroke> eraseRange(float x0, float y0, float x1, float y1, float er) {
        if (count == 0) return null;
        if (isShape()) return cutOutline(x0, y0, x1, y1, er);
        float step = Math.max(1f, er * .5f);
        List<InkStroke> out = new ArrayList<>();
        boolean touched = false;
        InkStroke current = null;
        float px = x(0), py = y(0), pv = value(0);
        if (covered(px, py, x0, y0, x1, y1, er)) { touched = true; current = null; }
        else { current = copy(); current.sampleRaw(px, py, pv); out.add(current); }
        for (int i = 1; i < count; i++) {
            float qx = x(i), qy = y(i), qv = value(i);
            double len = Math.hypot(qx - px, qy - py);
            int steps = Math.max(1, (int)Math.ceil(len / step));
            for (int j = 1; j <= steps; j++) {
                float t = j / (float)steps;
                float sx = px + (qx - px) * t, sy = py + (qy - py) * t, sv = pv + (qv - pv) * t;
                if (covered(sx, sy, x0, y0, x1, y1, er)) { touched = true; current = null; }
                else {
                    if (current == null) { current = copy(); out.add(current); }
                    current.sampleRaw(sx, sy, sv);
                }
            }
            px = qx; py = qy; pv = qv;
        }
        return touched ? out : null;
    }

    private List<InkStroke> cutOutline(float x0, float y0, float x1, float y1, float er) {
        List<float[]> lines = shapePolylines();
        float step = Math.max(1f, er * .5f);
        List<InkStroke> out = new ArrayList<>();
        boolean touched = false;
        for (float[] line : lines) {
            InkStroke current = null;
            for (int i = 0; i + 1 < line.length; i += 2) {
                if (i == 0) {
                    if (covered(line[0], line[1], x0, y0, x1, y1, er)) { touched = true; current = null; }
                    else {
                        current = new InkStroke("pen", argb, width);
                        current.sampleRaw(line[0], line[1], 1);
                        out.add(current);
                    }
                    continue;
                }
                float px = line[i-2], py = line[i-1], qx = line[i], qy = line[i+1];
                double len = Math.hypot(qx - px, qy - py);
                int steps = Math.max(1, (int)Math.ceil(len / step));
                for (int j = 1; j <= steps; j++) {
                    float t = j / (float)steps;
                    float sx = px + (qx - px) * t, sy = py + (qy - py) * t;
                    if (covered(sx, sy, x0, y0, x1, y1, er)) { touched = true; current = null; }
                    else {
                        if (current == null) {
                            current = new InkStroke("pen", argb, width);
                            out.add(current);
                        }
                        current.sampleRaw(sx, sy, 1);
                    }
                }
            }
        }
        if (!touched) return null;
        for (InkStroke piece : out) if (piece.count() > 0) piece.taperSingle();
        return out;
    }

    /** End softening without touching the stored width profile of a cut fragment. */
    private void taperSingle() {
        if (count < 4) return;
        int[] tail = {count - 2, count - 1};
        float[] scale = {.7f, .5f};
        for (int i = 0; i < tail.length; i++) xyz[3*tail[i] + 2] *= scale[i];
        dirty = true;
    }

    /** The shape as flat polylines, for cutting and hit testing. */
    private List<float[]> shapePolylines() {
        List<float[]> out = new ArrayList<>();
        String s = shape();
        float ax = x(0), ay = y(0), bx = x(1), by = y(1);
        if ("line".equals(s)) { out.add(new float[] {ax, ay, bx, by}); return out; }
        if ("arrow".equals(s)) {
            out.add(new float[] {ax, ay, bx, by});
            double angle = Math.atan2(by - ay, bx - ax);
            double len = Math.hypot(bx - ax, by - ay);
            float size = (float)Math.max(6, Math.min(len * .32, 40));
            for (int side = 1; side >= -1; side -= 2) {
                double a = angle + Math.PI + side * .42;
                out.add(new float[] {bx, by, bx + (float)(Math.cos(a) * size), by + (float)(Math.sin(a) * size)});
            }
            return out;
        }
        float l = bounds.left, t = bounds.top, r = bounds.right, b = bounds.bottom;
        float cx = (l + r) * .5f, cy = (t + b) * .5f;
        float[] poly = outlinePolygon(s, l, t, r, b, cx, cy);
        out.add(poly);
        return out;
    }

    private boolean covered(float x, float y, float ax, float ay, float bx, float by, float er) {
        return distanceToSegment(x, y, ax, ay, bx, by) <= er + width * .5f;
    }

    private static double distanceToSegment(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay, d = dx*dx + dy*dy;
        double t = d == 0 ? 0 : Math.max(0, Math.min(1, ((px-ax)*dx + (py-ay)*dy) / d));
        return Math.hypot(px - (ax + t*dx), py - (ay + t*dy));
    }

    private static boolean nearPoint(double px,double py,double ax,double ay,double bx,double by,double radius) {
        double dx=bx-ax, dy=by-ay, d=dx*dx+dy*dy;
        double t=d==0 ? 0 : Math.max(0,Math.min(1,((px-ax)*dx+(py-ay)*dy)/d));
        double x=(px-ax)-t*dx, y=(py-ay)-t*dy;
        return x*x+y*y <= radius*radius;
    }

    private static boolean nearSegments(double ax,double ay,double bx,double by,double cx,double cy,double dx,double dy,double r) {
        if (Math.max(ax,bx)+r<Math.min(cx,dx) || Math.max(cx,dx)+r<Math.min(ax,bx) ||
                Math.max(ay,by)+r<Math.min(cy,dy) || Math.max(cy,dy)+r<Math.min(ay,by)) return false;
        double ux=bx-ax, uy=by-ay, vx=dx-cx, vy=dy-cy, det=ux*vy-uy*vx;
        if (det!=0) {
            double wx=cx-ax, wy=cy-ay, s=(wx*vy-wy*vx)/det, t=(wx*uy-wy*ux)/det;
            if (s>=0 && s<=1 && t>=0 && t<=1) return true;
        }
        return nearPoint(ax,ay,cx,cy,dx,dy,r) || nearPoint(bx,by,cx,cy,dx,dy,r) ||
                nearPoint(cx,cy,ax,ay,bx,by,r) || nearPoint(dx,dy,ax,ay,bx,by,r);
    }
}
