package app.paperharbor.notes;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;

/** Page-space geometry shared by input, hit testing, history and rendering. */
final class InkStroke {
    final String tool;
    final int argb;
    final float width;
    final RectF bounds = new RectF(); // sample coordinates; brush radii are additional
    private final Path path = new Path();
    private float[] xyz = new float[24];
    private int count;

    InkStroke(String tool, int argb, float width) {
        this.tool = tool;
        this.argb = argb;
        this.width = width;
    }

    static boolean drawable(String tool) {
        return "pen".equals(tool) || "marker".equals(tool) || "line".equals(tool) || "rect".equals(tool);
    }

    boolean shape() { return tool.equals("line") || tool.equals("rect"); }
    float x(int i) { return xyz[3*i]; }
    float y(int i) { return xyz[3*i + 1]; }
    private float pressure(int i) { return xyz[3*i + 2]; }
    private float radius(int i) {
        return width * .5f * (tool.equals("pen") ? .35f + .65f * pressure(i) : 1f);
    }

    // Only drafts are mutated. An InkStroke in a committed page/history is immutable.
    void sample(float x, float y, float p) {
        if (shape() && count == 2) count = 1; // replace preview endpoint
        if (!shape() && count > 0 && x(count-1) == x && y(count-1) == y) return;
        if (count * 3 == xyz.length) xyz = Arrays.copyOf(xyz, xyz.length * 2);
        int i = count++;
        xyz[i*3] = x; xyz[i*3+1] = y; xyz[i*3+2] = p;
        if (i == 0 || shape()) bounds.set(x(0), y(0), x(0), y(0));
        bounds.left = Math.min(bounds.left, x); bounds.top = Math.min(bounds.top, y);
        bounds.right = Math.max(bounds.right, x); bounds.bottom = Math.max(bounds.bottom, y);
        if (shape()) return;
        if (tool.equals("marker")) {
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            return;
        }
        // A single winding fill per pen stroke: overlapping corners don't darken translucent ink.
        float r = radius(i);
        if (i > 0) {
            float ax = x(i-1), ay = y(i-1), ar = radius(i-1);
            double len = Math.hypot(x - ax, y - ay);
            float nx = (float)(-(y-ay)/len), ny = (float)((x-ax)/len);
            path.moveTo(ax + nx*ar, ay + ny*ar); path.lineTo(ax - nx*ar, ay - ny*ar);
            path.lineTo(x - nx*r, y - ny*r); path.lineTo(x + nx*r, y + ny*r); path.close();
        }
        path.addCircle(x, y, r, Path.Direction.CW);
    }

    void draw(Canvas c, Paint paint) {
        if (count == 0) return;
        paint.setColor(argb);
        paint.setStyle(Paint.Style.FILL);
        if (tool.equals("pen")) { c.drawPath(path, paint); return; }
        if (tool.equals("marker")) paint.setAlpha(Math.round(Color.alpha(argb) * .38f));
        if (count == 1 || (shape() && x(0) == x(1) && y(0) == y(1))) {
            c.drawCircle(x(0), y(0), width * .5f, paint); return;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        if (tool.equals("marker")) c.drawPath(path, paint);
        else if (tool.equals("rect") && x(0) != x(1) && y(0) != y(1)) c.drawRect(bounds, paint);
        else c.drawLine(x(0), y(0), x(1), y(1), paint);
    }

    InkStroke shifted(float dx, float dy, float w, float h) {
        InkStroke s = new InkStroke(tool, argb, width);
        for (int i=0; i<count; i++) s.sample(clamp(x(i)+dx, 0, w), clamp(y(i)+dy, 0, h), pressure(i));
        return s;
    }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(v, hi)); }

    JSONObject encode() {
        try {
            JSONArray pts = new JSONArray();
            for (int i=0; i<count; i++) pts.put(new JSONArray().put(x(i)).put(y(i)).put(pressure(i)));
            return new JSONObject().put("tool", tool).put("argb", argb).put("width", (double)width).put("points", pts);
        } catch (JSONException e) { throw new IllegalStateException("Non-finite ink sample", e); }
    }

    static InkStroke decode(JSONObject obj, float w, float h) {
        if (obj == null) return null;
        String tool = obj.optString("tool");
        float width = (float)obj.optDouble("width");
        JSONArray pts = obj.optJSONArray("points");
        if (!drawable(tool) || !Float.isFinite(width) || width <= 0 || pts == null || pts.length() == 0) return null;
        InkStroke s = new InkStroke(tool, obj.optInt("argb", Color.BLACK), width);
        if (s.shape() && pts.length() != 2) return null;
        for (int i=0; i<pts.length(); i++) {
            JSONArray a = pts.optJSONArray(i);
            if (a == null) return null;
            float x = (float)a.optDouble(0), y = (float)a.optDouble(1), p = (float)a.optDouble(2, 1);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(p)) return null;
            s.sample(clamp(x, 0, w), clamp(y, 0, h), clamp(p, 0, 1));
        }
        return s;
    }

    /** Does any part of this rendered stroke meet the swept disk AB? Includes point/dot contacts. */
    boolean hits(float ax, float ay, float bx, float by, float er) {
        float pad = width*.5f + er;
        if (count == 0 || Math.min(ax,bx) > bounds.right+pad || Math.max(ax,bx) < bounds.left-pad ||
                Math.min(ay,by) > bounds.bottom+pad || Math.max(ay,by) < bounds.top-pad) return false;
        if (count == 1) return nearPoint(x(0),y(0),ax,ay,bx,by,radius(0)+er);
        if (tool.equals("rect")) {
            float l=bounds.left, r=bounds.right, t=bounds.top, b=bounds.bottom;
            return nearSegments(ax,ay,bx,by,l,t,r,t,pad) || nearSegments(ax,ay,bx,by,r,t,r,b,pad) ||
                    nearSegments(ax,ay,bx,by,r,b,l,b,pad) || nearSegments(ax,ay,bx,by,l,b,l,t,pad);
        }
        for (int i=1; i<count; i++) {
            float cx=x(i-1), cy=y(i-1), dx=x(i), dy=y(i), r0=radius(i-1), r1=radius(i);
            if (!nearSegments(ax,ay,bx,by,cx,cy,dx,dy,Math.max(r0,r1)+er)) continue;
            if (r0 == r1 || nearPoint(cx,cy,ax,ay,bx,by,r0+er) || nearPoint(dx,dy,ax,ay,bx,by,r1+er)) return true;
            // Unequal radii: test the sides of the actual tapered quadrilateral, not a fat capsule.
            double len=Math.hypot(dx-cx,dy-cy), ux=(dx-cx)/len, uy=(dy-cy)/len;
            double along=(ax-cx)*ux+(ay-cy)*uy, perp=(ay-cy)*ux-(ax-cx)*uy;
            if (along>=0 && along<=len && Math.abs(perp)<=r0+(r1-r0)*(along/len)) return true;
            if (nearSegments(ax,ay,bx,by,cx-uy*r0,cy+ux*r0,dx-uy*r1,dy+ux*r1,er) ||
                    nearSegments(ax,ay,bx,by,cx+uy*r0,cy-ux*r0,dx+uy*r1,dy-ux*r1,er)) return true;
        }
        return false;
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
