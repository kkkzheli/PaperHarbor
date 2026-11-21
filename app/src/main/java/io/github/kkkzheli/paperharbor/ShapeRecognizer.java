/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation, not affiliated with Goodnotes.
 */
package io.github.kkkzheli.paperharbor;

import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a freehand sample into a regular shape when the drawing is close enough to one.
 *
 * <p>All tests run on a uniform resample of the stroke, in page units. A straight scribble becomes
 * a line, a closed loop with high circularity becomes a circle or ellipse, and a closed loop with
 * three or four simplified corners becomes a triangle or rectangle. Anything else returns
 * {@code null} and the freehand stroke is kept.
 */
final class ShapeRecognizer {
    private static final int RESAMPLE = 56;

    static final class Match {
        final String kind;
        final float x0, y0, x1, y1;
        Match(String kind, float x0, float y0, float x1, float y1) {
            this.kind = kind; this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
        }
    }

    private ShapeRecognizer() {}

    /** Returns a shape guess, or null to keep the freehand line. */
    static Match recognize(InkStroke stroke) {
        if (stroke == null || stroke.count() < 8 || stroke.isShape()) return null;
        float[] pts = resample(stroke);
        if (pts == null) return null;
        int n = RESAMPLE;
        RectF box = bounds(pts, n);
        float diag = (float)Math.hypot(box.width(), box.height());
        if (diag < 26) return null;

        float perimeter = 0;
        for (int i = 1; i < n; i++) perimeter += (float)Math.hypot(pts[i*2] - pts[i*2-2], pts[i*2+1] - pts[i*2-1]);
        float gap = (float)Math.hypot(pts[0] - pts[(n-1)*2], pts[1] - pts[(n-1)*2+1]);
        boolean closed = gap <= Math.max(18f, diag * .28f) && perimeter > diag * 2.1f;

        if (!closed) {
            // A line must hug its chord; a wiggly or curved stroke stays freehand.
            float chord = (float)Math.hypot(pts[(n-1)*2] - pts[0], pts[(n-1)*2+1] - pts[1]);
            if (chord < 26) return null;
            float deviation = 0;
            for (int i = 1; i < n - 1; i++)
                deviation = Math.max(deviation, perpendicular(pts[i*2], pts[i*2+1],
                    pts[0], pts[1], pts[(n-1)*2], pts[(n-1)*2+1]));
            if (deviation <= chord * .045f && perimeter <= chord * 1.08f)
                return new Match("line", pts[0], pts[1], pts[(n-1)*2], pts[(n-1)*2+1]);
            return null;
        }

        float area = Math.abs(shoelace(pts, n));
        float circularity = perimeter <= 0 ? 0 : (float)(4 * Math.PI * area / (perimeter * perimeter));

        // A rectangle hugs its bounding box and reaches all four corners; a circle or ellipse
        // never gets near a corner, so this separates the two before shape roundness is considered.
        int cornerHits = 0;
        float[][] boxCorners = {{box.left, box.top}, {box.right, box.top},
            {box.right, box.bottom}, {box.left, box.bottom}};
        for (float[] corner : boxCorners) {
            for (int i = 0; i < n; i++) {
                if (Math.hypot(pts[i*2] - corner[0], pts[i*2+1] - corner[1]) <= diag * .13f) {
                    cornerHits++;
                    break;
                }
            }
        }
        float edgeScore = edgeCoverage(pts, n, box, diag * .09f);
        if (cornerHits >= 3 && edgeScore > .62f)
            return new Match("rect", box.left, box.top, box.right, box.bottom);
        if (circularity > .80f) {
            float aspect = box.width() / Math.max(1f, box.height());
            return new Match(aspect > .8f && aspect < 1.25f ? "circle" : "ellipse",
                box.left, box.top, box.right, box.bottom);
        }

        List<float[]> corners = simplify(pts, n, Math.max(4f, diag * .055f));
        if (corners.size() == 4) return new Match("rect", box.left, box.top, box.right, box.bottom);
        if (corners.size() == 3) {
            float apexX = corners.get(0)[0];
            for (float[] c : corners) if (c[1] < box.top + box.height() * .35f) apexX = c[0];
            float center = (box.left + box.right) * .5f;
            if (Math.abs(apexX - center) <= box.width() * .3f)
                return new Match("triangle", box.left, box.top, box.right, box.bottom);
        }
        return null;
    }

    /** Share of samples that sit close to one of the four bounding-box edges. */
    private static float edgeCoverage(float[] pts, int n, RectF box, float tolerance) {
        int inside = 0;
        for (int i = 0; i < n; i++) {
            float x = pts[i*2], y = pts[i*2+1];
            float dx = Math.min(x - box.left, box.right - x);
            float dy = Math.min(y - box.top, box.bottom - y);
            if (Math.min(dx, dy) <= tolerance) inside++;
        }
        return inside / (float)n;
    }

    private static float[] resample(InkStroke stroke) {
        int count = stroke.count();
        float total = 0;
        float[] seg = new float[count];
        for (int i = 1; i < count; i++) {
            seg[i] = (float)Math.hypot(stroke.x(i) - stroke.x(i-1), stroke.y(i) - stroke.y(i-1));
            total += seg[i];
        }
        if (total <= 0) return null;
        float[] out = new float[RESAMPLE * 2];
        float step = total / (RESAMPLE - 1);
        int index = 1;
        float walked = 0;
        out[0] = stroke.x(0); out[1] = stroke.y(0);
        for (int i = 1; i < RESAMPLE; i++) {
            float target = step * i;
            while (index < count - 1 && walked + seg[index] < target) { walked += seg[index]; index++; }
            float span = Math.max(1e-4f, seg[index]);
            float t = Math.min(1f, Math.max(0f, (target - walked) / span));
            out[i*2] = stroke.x(index-1) + (stroke.x(index) - stroke.x(index-1)) * t;
            out[i*2+1] = stroke.y(index-1) + (stroke.y(index) - stroke.y(index-1)) * t;
        }
        return out;
    }

    private static RectF bounds(float[] pts, int n) {
        RectF r = new RectF(pts[0], pts[1], pts[0], pts[1]);
        for (int i = 1; i < n; i++) {
            r.left = Math.min(r.left, pts[i*2]); r.right = Math.max(r.right, pts[i*2]);
            r.top = Math.min(r.top, pts[i*2+1]); r.bottom = Math.max(r.bottom, pts[i*2+1]);
        }
        return r;
    }

    private static float shoelace(float[] pts, int n) {
        float sum = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            sum += pts[i*2] * pts[j*2+1] - pts[j*2] * pts[i*2+1];
        }
        return sum * .5f;
    }

    private static float perpendicular(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float len = (float)Math.hypot(dx, dy);
        if (len <= 0) return (float)Math.hypot(px - ax, py - ay);
        return Math.abs((px - ax) * dy - (py - ay) * dx) / len;
    }

    /** Ramer–Douglas–Peucker over the resampled loop, tolerance in page units. */
    private static List<float[]> simplify(float[] pts, int n, float epsilon) {
        boolean[] keep = new boolean[n];
        keep[0] = true; keep[n-1] = true;
        List<int[]> stack = new ArrayList<>();
        stack.add(new int[] {0, n - 1});
        while (!stack.isEmpty()) {
            int[] range = stack.remove(stack.size() - 1);
            int first = range[0], last = range[1];
            if (last <= first + 1) continue;
            float ax = pts[first*2], ay = pts[first*2+1], bx = pts[last*2], by = pts[last*2+1];
            float worst = 0; int at = -1;
            for (int i = first + 1; i < last; i++) {
                float d = perpendicular(pts[i*2], pts[i*2+1], ax, ay, bx, by);
                if (d > worst) { worst = d; at = i; }
            }
            if (at > 0 && worst > epsilon) {
                keep[at] = true;
                stack.add(new int[] {first, at});
                stack.add(new int[] {at, last});
            }
        }
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) if (keep[i]) {
            float[] point = {pts[i*2], pts[i*2+1]};
            if (!out.isEmpty() && close(out.get(out.size()-1), point)) continue;
            out.add(point);
        }
        if (out.size() > 1 && close(out.get(0), out.get(out.size()-1))) out.remove(out.size()-1);
        return out;
    }

    private static boolean close(float[] a, float[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]) < 6;
    }
}
