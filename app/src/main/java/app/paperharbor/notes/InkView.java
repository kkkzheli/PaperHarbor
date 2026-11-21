package app.paperharbor.notes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Offline page editor. Bind and edit on the UI thread. The host owns the JSONObject and Bitmap;
 * do not mutate ink externally while bound. Bind again to reload ink or switch pages. The callback
 * runs synchronously once after a committed edit/history change; it is responsible for saving.
 *
 * <p>ink is a back-to-front JSON array. Each entry has tool (pen, marker, line or rect), argb
 * (signed Android color), width (positive page units), and points ([[x,y,pressure], ...]). Origin
 * is top left. Pressure ranges from 0 to 1, with finger input at 1. Freehand strokes have at least
 * one point; shapes have two endpoints/opposite corners. Marker uses 38% of the selected alpha.
 * Erasing, selection, zoom and history are not strokes. Unrecognized entries are not rendered but
 * remain in the array across edits. Only the ink key is replaced; other page keys are untouched.
 */
public final class InkView extends View {
    private static final int HISTORY = 40;
    private static final int NONE=0, DRAW=1, ERASE=2, LASSO=3, MOVE=4, NAV=5;
    private static final class Item {
        final Object json;
        final InkStroke stroke;
        Item(Object json, InkStroke stroke) { this.json=json; this.stroke=stroke; }
        Item(InkStroke stroke) { this(stroke.encode(),stroke); }
    }

    private JSONObject page;
    private Bitmap bitmap;
    private Runnable onChanged;
    private int w=1, h=1;
    private List<Item> items = new ArrayList<>();
    // History shares immutable entries (and cached paths); never copies the bitmap or text.
    private final ArrayDeque<List<Item>> past = new ArrayDeque<>(), future = new ArrayDeque<>();
    private final HashSet<Item> selected = new HashSet<>(), erased = new HashSet<>();
    private final RectF group = new RectF(); // sample bounds used to clamp the entire group
    private final RectF groupVisual = new RectF();
    private String tool="pen";
    private float width=2.5f;
    private int argb=0xff22272e;
    private final float dp;
    private final Paint inkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlay = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final InkPaper paper = new InkPaper();
    private final DashPathEffect dash;

    private int mode=NONE, owner=-1, navA=-1, navB=-1;
    private boolean stylus;
    private InkStroke draft;
    private final ArrayList<PointF> loop = new ArrayList<>();
    private final Path loopPath = new Path();
    private float anchorX, anchorY, endX, endY, dragX, dragY, eraseRadius;
    // Camera center in page space. scale, origin and view center are derived, never persisted.
    private float zoom=1, cameraX, cameraY, scale=1, ox, oy, vcx, vcy;
    private float prevMidX, prevMidY, prevSpan;

    public InkView(Context context) {
        super(context);
        dp=getResources().getDisplayMetrics().density;
        dash = new DashPathEffect(new float[]{5*dp, 4*dp}, 0);
    }

    public void bind(JSONObject page, Bitmap background, Runnable onChanged) {
        discardGesture(); intercept(false);
        this.page=page; bitmap=background; this.onChanged=onChanged;
        w=dimension(page,"w"); h=dimension(page,"h");
        items=new ArrayList<>(); selected.clear(); past.clear(); future.clear();
        JSONArray ink = page == null ? null : page.optJSONArray("ink");
        if (ink != null) for (int i=0; i<ink.length(); i++)
            items.add(new Item(ink.opt(i),InkStroke.decode(ink.optJSONObject(i),w,h)));
        zoom=1; cameraX=w*.5f; cameraY=h*.5f;
        invalidate();
    }

    public void tool(String name) {
        if ((!InkStroke.drawable(name) && !"erase".equals(name) && !"lasso".equals(name)) || tool.equals(name)) return;
        discardGesture(); selected.clear(); tool=name; invalidate();
    }
    public void color(int argb) { this.argb=argb; }
    public void width(float pageUnits) {
        if (Float.isFinite(pageUnits) && pageUnits>0) width=Math.min(512f,pageUnits);
    }
    public boolean undo() { return rewind(past,future); }
    public boolean redo() { return rewind(future,past); }
    public boolean canUndo() { return page!=null && !past.isEmpty(); }
    public boolean canRedo() { return page!=null && !future.isEmpty(); }

    private boolean rewind(ArrayDeque<List<Item>> from, ArrayDeque<List<Item>> to) {
        discardGesture(); invalidate();
        if (page==null || from.isEmpty()) return false;
        selected.clear(); to.addLast(items); items=from.removeLast(); publish(); return true;
    }
    private void commit(List<Item> next) {
        past.addLast(items); if (past.size()>HISTORY) past.removeFirst();
        future.clear(); items=next; publish();
    }
    private void publish() {
        JSONArray ink=new JSONArray();
        for (Item item : items) ink.put(item.json);
        try { page.put("ink",ink); } catch (JSONException e) { throw new IllegalStateException(e); }
        invalidate();
        if (onChanged!=null) onChanged.run();
    }

    private static int dimension(JSONObject page, String key) {
        return page==null ? 1 : Math.max(1,page.optInt(key,1));
    }

    /** Draws committed content into [0,w] x [0,h] without fitting or changing the caller's transform. */
    public static void drawPage(Canvas canvas, JSONObject page, Bitmap background) {
        if (page==null) return;
        int w=dimension(page,"w"), h=dimension(page,"h"), save=canvas.save();
        try {
            if (!canvas.clipRect(0,0,w,h)) return;
            new InkPaper().draw(canvas,page,w,h,background);
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            JSONArray ink=page.optJSONArray("ink");
            if (ink!=null) for (int i=0; i<ink.length(); i++) {
                InkStroke s=InkStroke.decode(ink.optJSONObject(i),w,h);
                if (s!=null) s.draw(canvas,paint);
            }
        } finally { canvas.restoreToCount(save); }
    }

    private boolean geometry() {
        int vw=getWidth()-getPaddingLeft()-getPaddingRight(), vh=getHeight()-getPaddingTop()-getPaddingBottom();
        if (vw<=0 || vh<=0 || page==null) return false;
        scale=Math.min(vw/(float)w,vh/(float)h)*zoom;
        vcx=getPaddingLeft()+vw*.5f; vcy=getPaddingTop()+vh*.5f;
        float mx=Math.min(w*.5f,vw*.5f/scale), my=Math.min(h*.5f,vh*.5f/scale);
        cameraX=InkStroke.clamp(cameraX,mx,w-mx); cameraY=InkStroke.clamp(cameraY,my,h-my);
        ox=vcx-cameraX*scale; oy=vcy-cameraY*scale; return true;
    }
    private boolean inside(float sx,float sy) {
        return sx>=getPaddingLeft() && sy>=getPaddingTop() && sx<=getWidth()-getPaddingRight() &&
                sy<=getHeight()-getPaddingBottom() && sx>=ox && sy>=oy && sx<=ox+w*scale && sy<=oy+h*scale;
    }
    private float pageX(float x) { return InkStroke.clamp((x-ox)/scale,0,w); }
    private float pageY(float y) { return InkStroke.clamp((y-oy)/scale,0,h); }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c); c.drawColor(0xffe7e0d3);
        if (!geometry()) return;
        int save=c.save();
        try {
            c.clipRect(getPaddingLeft(),getPaddingTop(),getWidth()-getPaddingRight(),getHeight()-getPaddingBottom());
            c.translate(ox,oy); c.scale(scale,scale);
            if (!c.clipRect(0,0,w,h)) return;
            paper.draw(c,page,w,h,bitmap);
            for (Item item : items) {
                if (item.stroke==null || erased.contains(item)) continue;
                if (mode==MOVE && selected.contains(item)) {
                    c.save(); c.translate(dragX,dragY); item.stroke.draw(c,inkPaint); c.restore();
                } else item.stroke.draw(c,inkPaint);
            }
            if (draft!=null) draft.draw(c,inkPaint);
            drawOverlay(c);
        } finally { c.restoreToCount(save); }
    }
    private void drawOverlay(Canvas c) {
        overlay.setStyle(Paint.Style.STROKE); overlay.setStrokeWidth(1.5f*dp/scale);
        overlay.setColor(0xff19796d);
        if (mode==LASSO) {
            c.drawPath(loopPath,overlay);
            if (loop.size()>1) c.drawLine(endX,endY,loop.get(0).x,loop.get(0).y,overlay);
        } else if (mode==ERASE) {
            c.drawCircle(endX,endY,eraseRadius,overlay);
        } else if (!selected.isEmpty()) {
            // Draw selection UI in view pixels so dashes and tap affordance stay legible at any zoom.
            float pad=4*dp;
            c.save(); c.scale(1/scale,1/scale);
            overlay.setPathEffect(dash); overlay.setStrokeWidth(1.5f*dp);
            c.drawRect((groupVisual.left+dragX)*scale-pad,(groupVisual.top+dragY)*scale-pad,
                    (groupVisual.right+dragX)*scale+pad,(groupVisual.bottom+dragY)*scale+pad,overlay);
            overlay.setPathEffect(null); c.restore();
        }
    }

    private void intercept(boolean disallow) {
        if (getParent()!=null) getParent().requestDisallowInterceptTouchEvent(disallow);
    }
    private void discardGesture() {
        mode=NONE; owner=navA=navB=-1; stylus=false;
        draft=null; erased.clear(); loop.clear(); loopPath.rewind(); dragX=dragY=0;
    }
    @Override protected void onDetachedFromWindow() {
        discardGesture(); intercept(false); super.onDetachedFromWindow();
    }
    private static boolean isStylus(int type) {
        return type==MotionEvent.TOOL_TYPE_STYLUS || type==MotionEvent.TOOL_TYPE_ERASER;
    }
    private static float pressure(boolean stylus, float raw) {
        return stylus && Float.isFinite(raw) ? InkStroke.clamp(raw,0,1) : 1;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action=e.getActionMasked(), index=e.getActionIndex();
        if (action==MotionEvent.ACTION_CANCEL) { discardGesture(); intercept(false); invalidate(); return true; }
        if (!isEnabled() || !geometry()) { discardGesture(); intercept(false); return false; }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                discardGesture(); intercept(true); begin(e,index); break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (stylus && owner!=-1) break; // keep an active pen, ignore other contacts
                if (isStylus(e.getToolType(index))) { discardGesture(); begin(e,index); }
                else if (mode!=NAV) startNavigation(e,-1);
                break;
            case MotionEvent.ACTION_MOVE:
                if (mode==NAV) navigate(e);
                else if (owner!=-1) {
                    int i=e.findPointerIndex(owner);
                    if (i>=0) samples(e,i); else discardGesture();
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                int id=e.getPointerId(index);
                if (id==owner) {
                    if ((e.getFlags() & MotionEvent.FLAG_CANCELED)!=0) discardGesture();
                    else { samples(e,index); finish(); }
                } else if (mode==NAV && (id==navA || id==navB)) {
                    navigate(e); startNavigation(e,index);
                }
                if (action==MotionEvent.ACTION_UP) { discardGesture(); intercept(false); }
                break;
            default: return super.onTouchEvent(e);
        }
        invalidate(); return true;
    }

    private void begin(MotionEvent e, int i) {
        float sx=e.getX(i), sy=e.getY(i);
        if (!inside(sx,sy)) return; // gutter can initiate a pinch, but never an ink stroke
        int type=e.getToolType(i);
        stylus=isStylus(type); owner=e.getPointerId(i);
        String active=type==MotionEvent.TOOL_TYPE_ERASER ? "erase" : tool;
        anchorX=endX=pageX(sx); anchorY=endY=pageY(sy);
        if (InkStroke.drawable(active)) {
            mode=DRAW; draft=new InkStroke(active,argb,width); draft.sample(endX,endY,pressure(stylus,e.getPressure(i)));
        } else if (active.equals("erase")) {
            mode=ERASE; eraseRadius=Math.max(width*.5f,8*dp/scale); erase(endX,endY,endX,endY);
        } else {
            float pad=8*dp/scale;
            if (!selected.isEmpty() && endX>=groupVisual.left-pad && endX<=groupVisual.right+pad &&
                    endY>=groupVisual.top-pad && endY<=groupVisual.bottom+pad) mode=MOVE;
            else { mode=LASSO; loop.add(new PointF(endX,endY)); loopPath.moveTo(endX,endY); }
        }
    }
    private void samples(MotionEvent e, int i) {
        for (int k=0; k<e.getHistorySize(); k++)
            sample(e.getHistoricalX(i,k),e.getHistoricalY(i,k),e.getHistoricalPressure(i,k));
        sample(e.getX(i),e.getY(i),e.getPressure(i));
    }
    private void sample(float sx, float sy, float rawPressure) {
        if (!Float.isFinite(sx) || !Float.isFinite(sy)) return;
        float x=pageX(sx), y=pageY(sy);
        switch (mode) {
            case DRAW: draft.sample(x,y,pressure(stylus,rawPressure)); break;
            case ERASE: erase(endX,endY,x,y); break;
            case LASSO:
                PointF prev=loop.get(loop.size()-1);
                if (Math.hypot(x-prev.x,y-prev.y)*scale>=2*dp) { loop.add(new PointF(x,y)); loopPath.lineTo(x,y); }
                break;
            case MOVE:
                dragX=InkStroke.clamp(x-anchorX,-group.left,w-group.right);
                dragY=InkStroke.clamp(y-anchorY,-group.top,h-group.bottom);
                break;
        }
        endX=x; endY=y;
    }
    private void erase(float x0,float y0,float x1,float y1) {
        for (Item item : items) if (item.stroke!=null && !erased.contains(item) && item.stroke.hits(x0,y0,x1,y1,eraseRadius))
            erased.add(item);
    }
    private void finish() {
        List<Item> next=null;
        if (mode==DRAW) {
            // Also covers a tap with no move events: a point or two coincident shape endpoints.
            next=new ArrayList<>(items); next.add(new Item(draft));
        } else if (mode==ERASE && !erased.isEmpty()) {
            next=new ArrayList<>(items.size()-erased.size());
            for (Item item : items) if (!erased.contains(item)) next.add(item);
            selected.removeAll(erased); updateGroup();
        } else if (mode==MOVE && (dragX!=0 || dragY!=0)) {
            next=new ArrayList<>(items.size()); HashSet<Item> moved=new HashSet<>();
            for (Item item : items) {
                if (selected.contains(item)) { item=new Item(item.stroke.shifted(dragX,dragY,w,h)); moved.add(item); }
                next.add(item);
            }
            selected.clear(); selected.addAll(moved); updateGroup();
        } else if (mode==LASSO) { selectLoop(); updateGroup(); }
        discardGesture();
        if (next!=null) commit(next); // publish only after the gesture state is consistent
    }
    private void updateGroup() {
        boolean first=true;
        for (Item item : selected) {
            RectF b=item.stroke.bounds; float r=item.stroke.width*.5f;
            if (first) { group.set(b); groupVisual.set(b.left-r,b.top-r,b.right+r,b.bottom+r); first=false; }
            else {
                // RectF.union(RectF) drops zero-area bounds (dots and axis-aligned lines).
                group.left=Math.min(group.left,b.left); group.top=Math.min(group.top,b.top);
                group.right=Math.max(group.right,b.right); group.bottom=Math.max(group.bottom,b.bottom);
                groupVisual.union(b.left-r,b.top-r,b.right+r,b.bottom+r);
            }
        }
    }
    private void selectLoop() {
        selected.clear();
        if (loop.size()<3) return;
        PointF a=loop.get(0), b=loop.get(1);
        boolean area=false;
        RectF box=new RectF(a.x,a.y,a.x,a.y);
        for (PointF p : loop) {
            if ((double)(p.x-a.x)*(b.y-a.y)!=(double)(p.y-a.y)*(b.x-a.x)) area=true;
            box.left=Math.min(box.left,p.x); box.right=Math.max(box.right,p.x);
            box.top=Math.min(box.top,p.y); box.bottom=Math.max(box.bottom,p.y);
        }
        if (!area) return;
        for (Item item : items) {
            InkStroke s=item.stroke;
            if (s==null) continue;
            RectF bounds=s.bounds; float r=s.width*.5f;
            if (box.right<bounds.left-r || box.left>bounds.right+r || box.bottom<bounds.top-r || box.top>bounds.bottom+r) continue;
            // A connected stroke meets the polygon if its start lies inside, or it meets an edge.
            boolean in=false, crosses=false; float x=s.x(0), y=s.y(0);
            PointF prev=loop.get(loop.size()-1);
            for (PointF p : loop) {
                if (s.hits(prev.x,prev.y,p.x,p.y,0)) { crosses=true; break; }
                if ((prev.y>y)!=(p.y>y) && x < prev.x + (double)(p.x-prev.x)*(y-prev.y)/(p.y-prev.y)) in=!in;
                prev=p;
            }
            if (in || crosses) selected.add(item);
        }
    }

    private void startNavigation(MotionEvent e, int skip) {
        int a=-1,b=-1;
        for (int i=0; i<e.getPointerCount(); i++) if (i!=skip && !isStylus(e.getToolType(i))) {
            if (a<0) a=i; else { b=i; break; }
        }
        if (b<0) { if (mode==NAV) discardGesture(); return; }
        discardGesture(); // converting a finger edit into navigation never leaves a stray mark
        mode=NAV; navA=e.getPointerId(a); navB=e.getPointerId(b);
        prevMidX=(e.getX(a)+e.getX(b))*.5f; prevMidY=(e.getY(a)+e.getY(b))*.5f;
        prevSpan=(float)Math.hypot(e.getX(a)-e.getX(b),e.getY(a)-e.getY(b));
    }
    private void navigate(MotionEvent e) {
        int a=e.findPointerIndex(navA), b=e.findPointerIndex(navB);
        if (a<0 || b<0) { startNavigation(e,-1); return; }
        float x=(e.getX(a)+e.getX(b))*.5f, y=(e.getY(a)+e.getY(b))*.5f;
        float span=(float)Math.hypot(e.getX(a)-e.getX(b),e.getY(a)-e.getY(b));
        float fx=(prevMidX-ox)/scale, fy=(prevMidY-oy)/scale, oldZoom=zoom;
        if (prevSpan>dp && span>dp) zoom=InkStroke.clamp(zoom*(span/prevSpan),1,8);
        float newScale=scale*(zoom/oldZoom);
        cameraX=fx-(x-vcx)/newScale; cameraY=fy-(y-vcy)/newScale;
        geometry(); prevMidX=x; prevMidY=y; prevSpan=span;
    }
}
