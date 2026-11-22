/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation.
 */
package io.github.kkkzheli.paperharbor;

import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a freehand sample into a regular shape when the drawing is close enough to one.
 *
 * <p>The stroke is resampled uniformly and lightly smoothed, which removes stylus tremor without
 * rounding away a real corner. A stroke that stays near its chord becomes a line; a closed loop is
 * then classified by its corners first (three, or four at the sides or at the diagonals) and by its
 * ellipse fit second, which is what keeps a sloppy rectangle from being mistaken for a circle.
 * Anything that fits nothing returns {@code null} and the freehand stroke is kept unchanged.
 */
final class ShapeRecognizer {
    private static final int RESAMPLE = 64;

    static final class Match {
        final String kind;
        final float x0, y0, x1, y1;
        Match(String kind, float x0, float y0, float x1, float y1) {
            this.kind = kind; this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
        }
    }

    private ShapeRecognizer() {}

    /**
     * Recognises a stroke with an optional target chosen in the magic-pen sheet. {@code auto}
     * keeps the free guess; naming a kind turns the drawn stroke into exactly that shape, using
     * its box (or its two ends for a line), which is what "make this a rectangle" means even when
     * the hand was sloppy.
     */
    static Match recognize(InkStroke stroke, String target) {
        if (stroke == null || stroke.count() < 2 || stroke.isShape()) return null;
        if (target == null || "auto".equals(target)) return recognize(stroke);
        boolean closedKind = "rect".equals(target) || "circle".equals(target) || "ellipse".equals(target)
            || "triangle".equals(target) || "diamond".equals(target);
        if (!closedKind && !"line".equals(target)) return recognize(stroke);
        if ("line".equals(target)) {
            return new Match("line", stroke.x(0), stroke.y(0),
                stroke.x(stroke.count() - 1), stroke.y(stroke.count() - 1));
        }
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (int i = 0; i < stroke.count(); i++) {
            l = Math.min(l, stroke.x(i)); t = Math.min(t, stroke.y(i));
            r = Math.max(r, stroke.x(i)); b = Math.max(b, stroke.y(i));
        }
        if (Math.hypot(r - l, b - t) < 12) return null;
        return new Match(target, l, t, r, b);
    }

    /** Returns a shape guess, or null to keep the freehand line. */
    static Match recognize(InkStroke stroke) {
        if (stroke == null || stroke.count() < 6 || stroke.isShape()) return null;
        float[] pts = resample(stroke);
        if (pts == null) return null;
        int n = RESAMPLE;
        smooth(pts, n);
        RectF box = bounds(pts, n);
        float diag = (float)Math.hypot(box.width(), box.height());
        if (diag < 24) return null;

        float perimeter = 0;
        for (int i = 1; i < n; i++) perimeter += (float)Math.hypot(pts[i*2] - pts[i*2-2], pts[i*2+1] - pts[i*2-1]);
        float chord = (float)Math.hypot(pts[(n-1)*2] - pts[0], pts[(n-1)*2+1] - pts[1]);
        float gap = chord;
        boolean closed = gap <= Math.max(14f, perimeter * .17f) && perimeter > diag * 1.9f;

        if (!closed) {
            // A line must hug its chord and must not wander; a curve or a hook stays freehand.
            if (chord < 24 || perimeter > chord * 1.16f) return null;
            float deviation = 0;
            for (int i = 1; i < n - 1; i++)
                deviation = Math.max(deviation, perpendicular(pts[i*2], pts[i*2+1],
                    pts[0], pts[1], pts[(n-1)*2], pts[(n-1)*2+1]));
            if (deviation <= Math.max(5f, chord * .055f))
                return new Match("line", pts[0], pts[1], pts[(n-1)*2], pts[(n-1)*2+1]);
            return null;
        }

        float cx = (box.left + box.right) * .5f, cy = (box.top + box.bottom) * .5f;
        List<float[]> corners = corners(pts, n, Math.max(3.5f, diag * .052f));
        merge(corners, diag * .11f);
        if (rectFromSubset(corners, pts, n, box, diag)) {
            if (corners.size() == 4 && atEdgeMidpoints(corners, box, diag * .16f))
                return new Match("diamond", box.left, box.top, box.right, box.bottom);
            return new Match("rect", box.left, box.top, box.right, box.bottom);
        }
        if (triangleFromSubset(corners, pts, n, box, diag)) {
            return new Match("triangle", box.left, box.top, box.right, box.bottom);
        }

        float ellipseError = ellipseFit(pts, n, box);
        if (ellipseError < .16f) {
            float aspect = box.width() / Math.max(1f, box.height());
            return new Match(aspect > .82f && aspect < 1.22f ? "circle" : "ellipse",
                box.left, box.top, box.right, box.bottom);
        }
        return null;
    }


    /**
     * A rectangle is accepted when four corners survive dropping at most two extra vertices —
     * typically one hitch where the stroke met its own start. The candidates must sit on the
     * bounding box, meet at right angles and have the stroke run along their edges; a circle's
     * vertices fail the last two tests, so a loop no longer turns into a rectangle or vice versa.
     */
    private static boolean rectFromSubset(List<float[]> corners, float[] pts, int n,
                                          RectF box, float diag) {
        int size = corners.size();
        if (size == 4) return rectAngles(corners);
        if (size < 5 || size > 6) return false;
        int drop = size - 4;
        for (int a = 0; a < size; a++) {
            for (int b = a + 1; b < size; b++) {
                if (drop == 1 && b != a + 1 && !(a == 0 && b == size)) continue;
                if (drop == 2 && (b != a + 1 || a == 0)) continue;
                List<float[]> four = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    if (i == a || (drop == 2 && i == b)) continue;
                    four.add(corners.get(i));
                }
                if (four.size() != 4) continue;
                if (!rectAngles(four)) continue;
                if (!cornersAtBox(four, box, diag * .16f)) continue;
                if (coverage(pts, n, four, Math.max(6f, diag * .07f)) >= .78f) return true;
            }
        }
        return false;
    }

    /** Three corners near the ideal triangle, with the stroke running along all three edges. */
    private static boolean triangleFromSubset(List<float[]> corners, float[] pts, int n,
                                              RectF box, float diag) {
        int size = corners.size();
        if (size == 3) return triangleLooksRight(corners, box);
        if (size < 4 || size > 6) return false;
        float[][] ideal = {{(box.left + box.right) * .5f, box.top},
            {box.right, box.bottom}, {box.left, box.bottom}};
        float tolerance = diag * .2f;
        for (int a = 0; a < size; a++)
            for (int b = a + 1; b < size; b++)
                for (int c = b + 1; c < size; c++) {
                    List<float[]> three = new ArrayList<>();
                    three.add(corners.get(a)); three.add(corners.get(b)); three.add(corners.get(c));
                    boolean[] used = new boolean[3];
                    boolean ok = true;
                    for (float[] corner : three) {
                        int best = -1;
                        float bestD = Float.MAX_VALUE;
                        for (int i = 0; i < 3; i++) {
                            if (used[i]) continue;
                            float d = (float)Math.hypot(corner[0] - ideal[i][0], corner[1] - ideal[i][1]);
                            if (d < bestD) { bestD = d; best = i; }
                        }
                        if (best < 0 || bestD > tolerance) { ok = false; break; }
                        used[best] = true;
                    }
                    if (!ok) continue;
                    if (coverage(pts, n, three, Math.max(6f, diag * .07f)) >= .75f) return true;
                }
        return false;
    }

    /** Share of the resampled stroke lying close to the closed polygon through the corners. */
    private static float coverage(float[] pts, int n, List<float[]> poly, float tolerance) {
        int inside = 0;
        for (int i = 0; i < n; i++) {
            float best = Float.MAX_VALUE;
            for (int j = 0; j < poly.size(); j++) {
                float[] a = poly.get(j), b = poly.get((j + 1) % poly.size());
                best = Math.min(best, segmentDistance(pts[i*2], pts[i*2+1], a[0], a[1], b[0], b[1]));
            }
            if (best <= tolerance) inside++;
        }
        return inside / (float)n;
    }

    private static float segmentDistance(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float d = dx * dx + dy * dy;
        float t = d == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / d));
        return (float)Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /** Each of the four corners is near a different bounding-box corner. */
    private static boolean cornersAtBox(List<float[]> four, RectF box, float tolerance) {
        float[][] targets = {{box.left, box.top}, {box.right, box.top},
            {box.right, box.bottom}, {box.left, box.bottom}};
        boolean[] used = new boolean[4];
        for (float[] corner : four) {
            int best = -1;
            float bestD = Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                if (used[i]) continue;
                float d = (float)Math.hypot(corner[0] - targets[i][0], corner[1] - targets[i][1]);
                if (d < bestD) { bestD = d; best = i; }
            }
            if (best < 0 || bestD > tolerance) return false;
            used[best] = true;
        }
        return true;
    }

    /** Mean relative distance of the samples from the bounding-box ellipse. */
    private static float ellipseFit(float[] pts, int n, RectF box) {
        float rx = Math.max(1f, box.width() * .5f), ry = Math.max(1f, box.height() * .5f);
        float cx = (box.left + box.right) * .5f, cy = (box.top + box.bottom) * .5f;
        float sum = 0;
        for (int i = 0; i < n; i++) {
            double dx = (pts[i*2] - cx) / rx, dy = (pts[i*2+1] - cy) / ry;
            sum += Math.abs((float)Math.sqrt(dx*dx + dy*dy) - 1);
        }
        return sum / n;
    }

    private static boolean rectAngles(List<float[]> corners) {
        for (int i = 0; i < 4; i++) {
            float[] a = corners.get((i + 3) % 4), b = corners.get(i), c = corners.get((i + 1) % 4);
            double angle = angle(a, b, c);
            if (angle < 55 || angle > 125) return false;
        }
        return true;
    }

    /** A diamond puts its four corners on the four edge midpoints, one each. */
    private static boolean atEdgeMidpoints(List<float[]> corners, RectF box, float tolerance) {
        if (corners.size() != 4) return false;
        float mx = (box.left + box.right) * .5f, my = (box.top + box.bottom) * .5f;
        float[][] targets = {{mx, box.top}, {box.right, my}, {mx, box.bottom}, {box.left, my}};
        boolean[] used = new boolean[4];
        for (float[] corner : corners) {
            int best = -1;
            float bestD = Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                if (used[i]) continue;
                float d = (float)Math.hypot(corner[0] - targets[i][0], corner[1] - targets[i][1]);
                if (d < bestD) { bestD = d; best = i; }
            }
            if (best < 0 || bestD > tolerance) return false;
            used[best] = true;
        }
        return true;
    }

    private static boolean triangleLooksRight(List<float[]> corners, RectF box) {
        // One corner near the top or bottom centre, the other two on the opposite edge.
        float[] top = corners.get(0);
        for (float[] c : corners) if (c[1] < top[1]) top = c;
        float centre = (box.left + box.right) * .5f;
        return Math.abs(top[0] - centre) <= box.width() * .32f && box.height() > 18;
    }

    private static double angle(float[] a, float[] b, float[] c) {
        double v1x = a[0] - b[0], v1y = a[1] - b[1];
        double v2x = c[0] - b[0], v2y = c[1] - b[1];
        double l1 = Math.hypot(v1x, v1y), l2 = Math.hypot(v2x, v2y);
        if (l1 < 1e-4 || l2 < 1e-4) return 180;
        double cos = (v1x * v2x + v1y * v2y) / (l1 * l2);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    /** A closed loop takes an extra turn back to its start, so the last corner is dropped. */
    private static List<float[]> corners(float[] pts, int n, float epsilon) {
        List<float[]> raw = simplify(pts, n, epsilon);
        if (raw.size() > 1) {
            float[] first = raw.get(0), last = raw.get(raw.size() - 1);
            if (Math.hypot(first[0] - last[0], first[1] - last[1]) < epsilon * 2.2f)
                raw.remove(raw.size() - 1);
        }
        // The stroke may begin in the middle of an edge; that point is not a corner.
        boolean changed = true;
        while (changed && raw.size() > 3) {
            changed = false;
            for (int i = 0; i < raw.size(); i++) {
                float[] a = raw.get((i + raw.size() - 1) % raw.size()), b = raw.get(i);
                float[] c = raw.get((i + 1) % raw.size());
                if (angle(a, b, c) > 162) { raw.remove(i); changed = true; break; }
            }
        }
        return raw;
    }

    /** Folds corners that are closer than {@code tolerance} so a shaky corner counts once. */
    private static void merge(List<float[]> corners, float tolerance) {
        boolean changed = true;
        while (changed && corners.size() > 3) {
            changed = false;
            for (int i = 0; i < corners.size(); i++) {
                int j = (i + 1) % corners.size();
                float[] a = corners.get(i), b = corners.get(j);
                if (Math.hypot(a[0] - b[0], a[1] - b[1]) < tolerance) {
                    a[0] = (a[0] + b[0]) * .5f;
                    a[1] = (a[1] + b[1]) * .5f;
                    corners.remove(j);
                    changed = true;
                    break;
                }
            }
        }
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

    /** Two gentle passes; endpoints stay put so a line keeps its real ends. */
    private static void smooth(float[] pts, int n) {
        float[] x = new float[n], y = new float[n];
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                if (i == 0 || i == n - 1) { x[i] = pts[i*2]; y[i] = pts[i*2+1]; }
                else {
                    x[i] = (pts[i*2-2] + 2 * pts[i*2] + pts[i*2+2]) * .25f;
                    y[i] = (pts[i*2-1] + 2 * pts[i*2+1] + pts[i*2+3]) * .25f;
                }
            }
            for (int i = 0; i < n; i++) { pts[i*2] = x[i]; pts[i*2+1] = y[i]; }
        }
    }

    private static RectF bounds(float[] pts, int n) {
        RectF r = new RectF(pts[0], pts[1], pts[0], pts[1]);
        for (int i = 1; i < n; i++) {
            r.left = Math.min(r.left, pts[i*2]); r.right = Math.max(r.right, pts[i*2]);
            r.top = Math.min(r.top, pts[i*2+1]); r.bottom = Math.max(r.bottom, pts[i*2+1]);
        }
        return r;
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
            if (!out.isEmpty() && close(out.get(out.size()-1), point, epsilon * .5f)) continue;
            out.add(point);
        }
        return out;
    }

    private static boolean close(float[] a, float[] b, float tolerance) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]) < Math.max(3f, tolerance);
    }
}
