/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation.
 */
package io.github.kkkzheli.paperharbor;

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
 * <p>ink is a back-to-front JSON array. Each entry has tool (pen, brush, marker, line, rect or
 * shape), argb (signed Android color), width (positive page units), and points
 * ([[x,y,pressure], ...]). Shape entries add a {@code shape} sub-type and marker entries may add
 * their own {@code alpha}. Typed blocks live in {@code texts} and pictures in {@code images}; both
 * carry page-unit geometry, and pictures reference an {@code images/img-N.png} file inside the
 * notebook folder. Origin is top left.
 *
 * <p>Pressure ranges from 0 to 1, with finger input at 1; brush samples store their width factor in
 * the same slot. Freehand strokes have at least one point; shapes have two endpoints/corners.
 * Erasing, selection, zoom and history are not strokes. Unrecognized entries are not rendered but
 * remain in the array across edits. Only the ink, texts and images keys are replaced.
 */
public final class InkView extends View {
    private static final int HISTORY = 60;
    private static final int NONE=0, DRAW=1, ERASE=2, LASSO=3, MOVE=4, NAV=5, SCALE=6, ROTATE=7,
        AREA=8, PAN=9, MOVE_TEXT=10, MOVE_IMAGE=11;
    private static final int HANDLE_NONE = -1, HANDLE_INSIDE = 4, HANDLE_ROTATE = 5;

    /** Notified when the selection or the visible zoom changes, for host overlays. */
    public interface Listener {
        void onSelectionChanged(boolean hasSelection, RectF viewRect);
        void onZoomChanged(float zoom);
        /** An object selection replaces ink selection; the host draws its own small bubble. */
        void onObjectChanged(boolean hasObject, boolean isText, RectF viewRect);
        /** The text tool was tapped on empty paper; the host opens the composer. */
        void onTextRequested(float x, float y);
    }

    private static final class Item {
        final Object json;
        final InkStroke stroke;
        Item(Object json, InkStroke stroke) { this.json=json; this.stroke=stroke; }
        Item(InkStroke stroke) { this(stroke.encode(),stroke); }
    }

    private static final class State {
        final List<Item> items;
        final List<PageText> texts;
        final List<PageImage> images;
        State(List<Item> i, List<PageText> t, List<PageImage> g) {
            items = i; texts = t; images = g;
        }
    }

    private JSONObject page;
    private Bitmap bitmap;
    private Runnable onChanged;
    private Listener listener;
    private Store store;
    private String noteId;
    private int w=1, h=1;
    private List<Item> items = new ArrayList<>();
    private List<PageText> texts = new ArrayList<>();
    private List<PageImage> images = new ArrayList<>();
    // History shares immutable entries (and cached paths); never copies the bitmap or text.
    private final ArrayDeque<State> past = new ArrayDeque<>(), future = new ArrayDeque<>();
    private final HashSet<Item> selected = new HashSet<>(), erased = new HashSet<>();
    private final RectF group = new RectF(); // sample bounds used to clamp the entire group
    private final RectF groupVisual = new RectF();
    private String tool="pen", shapeKind="line";
    private float width=2.5f;
    private int argb=0xff22272e;
    private boolean stylusOnly, areaErase, magic;
    private String magicTarget = "auto";
    private float pressure = 1f;
    private float markerAlpha = .38f;
    private final float dp;
    private final Paint inkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlay = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final InkPaper paper = new InkPaper();
    private final DashPathEffect dash;

    private int mode=NONE, owner=-1, navA=-1, navB=-1, handle=HANDLE_NONE;
    private boolean stylus;
    private InkStroke draft;
    private final ArrayList<PointF> loop = new ArrayList<>();
    private final Path loopPath = new Path();
    private float anchorX, anchorY, endX, endY, dragX, dragY, eraseRadius;
    private List<Item> eraseWork;
    private boolean areaTouched;
    // Live selection transform: translate in page units, uniform scale, rotation in radians.
    private float moveX, moveY, scaleK = 1, angle, lastTouchAngle;
    private float startRadius;
    // Camera center in page space. scale, origin and view center are derived, never persisted.
    private float zoom=1, cameraX, cameraY, scale=1, ox, oy, vcx, vcy;
    private float prevMidX, prevMidY, prevSpan;
    private float startCamX, startCamY, startSx, startSy;
    private long lastTapTime;
    private float lastTapX, lastTapY;
    // Object selection and its live drag offset.
    private int selText = -1, selImage = -1;
    private float objDx, objDy;

    public InkView(Context context) {
        super(context);
        dp=getResources().getDisplayMetrics().density;
        dash = new DashPathEffect(new float[]{5*dp, 4*dp}, 0);
    }

    public void setListener(Listener value) { listener=value; }

    /** Pictures are loaded from the notebook folder; call before bind. */
    public void attach(Store store, String noteId) {
        this.store = store;
        this.noteId = noteId;
    }

    public void bind(JSONObject page, Bitmap background, Runnable onChanged) {
        discardGesture(); intercept(false);
        this.page=page; bitmap=background; this.onChanged=onChanged;
        w=dimension(page,"w"); h=dimension(page,"h");
        items=new ArrayList<>(); texts=new ArrayList<>(); images=new ArrayList<>();
        selected.clear(); past.clear(); future.clear(); selText = selImage = -1;
        JSONArray ink = page == null ? null : page.optJSONArray("ink");
        if (ink != null) for (int i=0; i<ink.length(); i++)
            items.add(new Item(ink.opt(i),InkStroke.decode(ink.optJSONObject(i),w,h)));
        JSONArray ts = page == null ? null : page.optJSONArray("texts");
        if (ts != null) for (int i=0; i<ts.length(); i++) {
            PageText t = PageText.decode(ts.optJSONObject(i), w, h);
            if (t != null) texts.add(t);
        }
        JSONArray im = page == null ? null : page.optJSONArray("images");
        if (im != null) for (int i=0; i<im.length(); i++) {
            PageImage g = PageImage.decode(im.optJSONObject(i));
            if (g != null) images.add(g);
        }
        zoom=1; cameraX=w*.5f; cameraY=h*.5f;
        reportSelection();
        reportObjects();
        invalidate();
    }

    public void tool(String name) {
        if (!recognized(name) || tool.equals(name)) return;
        discardGesture(); selected.clear(); tool=name;
        selText = selImage = -1;
        reportSelection(); reportObjects(); invalidate();
    }

    private static boolean recognized(String name) {
        return InkStroke.drawable(name) || "erase".equals(name) || "lasso".equals(name)
            || "magic".equals(name) || "text".equals(name) || "image".equals(name)
            || "roam".equals(name);
    }

    /** The shape drawn by the shape tool. Ignored by every other tool. */
    public void shapeKind(String kind) {
        if (InkStroke.shapeKind(kind)) shapeKind=kind;
    }
    public String shapeKind() { return shapeKind; }
    /** 0 = remove whole strokes, 1 = remove only the swept area. */
    public void areaErase(boolean on) { areaErase=on; }
    /** The shape the magic pen should produce; {@code auto} keeps the free guess. */
    public void magicShape(String kind) { magicTarget = kind == null ? "auto" : kind; }
    public void color(int argb) { this.argb=argb; }
    public void width(float pageUnits) {
        if (Float.isFinite(pageUnits) && pageUnits>0) width=Math.min(512f,pageUnits);
    }
    public void stylusOnly(boolean on) { stylusOnly=on; }
    /** Opacity factor for new highlighter strokes. */
    public void markerAlpha(float value) {
        if (Float.isFinite(value)) markerAlpha = InkStroke.clamp(value, .05f, 1f);
    }
    /** 1 = untouched pressure curve, below 1 reaches full width sooner. */
    public void pressureSensitivity(float value) {
        if (Float.isFinite(value) && value >= .4f && value <= 1.6f) pressure=value;
    }
    public boolean undo() { return rewind(past,future); }
    public boolean redo() { return rewind(future,past); }
    public boolean canUndo() { return page!=null && !past.isEmpty(); }
    public boolean canRedo() { return page!=null && !future.isEmpty(); }
    public float zoomLevel() { return zoom; }
    public void resetZoom() { zoom=1; cameraX=w*.5f; cameraY=h*.5f; geometry(); notifyZoom(); invalidate(); }

    public boolean hasSelection() { return !selected.isEmpty(); }
    public void clearSelection() { if (!selected.isEmpty()) { selected.clear(); reportSelection(); invalidate(); } }

    /** Selects every stroke on the page so it can be moved, scaled, cloned or deleted at once. */
    public void selectAll() {
        selected.clear(); selText = selImage = -1; reportObjects();
        for (Item item : items) if (item.stroke != null) selected.add(item);
        updateGroup();
        reportSelection();
        invalidate();
    }

    /** Copies the selected strokes just below-right of the originals and selects the copies. */
    public void cloneSelection() {
        if (selected.isEmpty()) return;
        float dx = Math.min(14, Math.max(0, w - group.right));
        float dy = Math.min(14, Math.max(0, h - group.bottom));
        List<Item> next = new ArrayList<>(items); HashSet<Item> copies = new HashSet<>();
        for (Item item : items) if (selected.contains(item) && item.stroke != null) {
            Item copy = new Item(item.stroke.shifted(dx, dy, w, h));
            next.add(copy); copies.add(copy);
        }
        selected.clear(); selected.addAll(copies);
        commit(next); updateGroup(); reportSelection(); invalidate();
    }

    public void deleteSelection() {
        if (selected.isEmpty()) return;
        List<Item> next = new ArrayList<>(items.size());
        for (Item item : items) if (!selected.contains(item)) next.add(item);
        selected.clear(); commit(next); reportSelection(); invalidate();
    }

    // ---- typed blocks and pictures ---------------------------------------------------------------

    public int selectedTextIndex() { return selText; }
    public PageText selectedText() {
        return selText >= 0 && selText < texts.size() ? texts.get(selText) : null;
    }
    public boolean hasObjectSelection() { return selText >= 0 || selImage >= 0; }

    /** Adds a block at the tapped point and selects it. */
    public void addText(PageText value) {
        if (value == null) return;
        List<PageText> next = new ArrayList<>(texts);
        next.add(value);
        commitObjects(items, next, images);
        selText = next.size() - 1; selImage = -1;
        selected.clear();
        reportSelection(); reportObjects(); invalidate();
    }

    /** Replaces the selected block, keeping its identity and its place in the stack. */
    public void replaceSelectedText(PageText value) {
        if (value == null || selText < 0 || selText >= texts.size()) return;
        List<PageText> next = new ArrayList<>(texts);
        next.set(selText, value);
        commitObjects(items, next, images);
        reportObjects(); invalidate();
    }

    public void addImage(PageImage value) {
        if (value == null) return;
        List<PageImage> next = new ArrayList<>(images);
        next.add(value);
        commitObjects(items, texts, next);
        selImage = next.size() - 1; selText = -1;
        selected.clear();
        reportSelection(); reportObjects(); invalidate();
    }

    /** Removes whichever object is selected. */
    public void deleteObject() {
        if (selText >= 0 && selText < texts.size()) {
            List<PageText> next = new ArrayList<>(texts);
            next.remove(selText);
            selText = -1;
            commitObjects(items, next, images);
        } else if (selImage >= 0 && selImage < images.size()) {
            List<PageImage> next = new ArrayList<>(images);
            next.remove(selImage);
            selImage = -1;
            commitObjects(items, texts, next);
        } else return;
        reportObjects(); reportSelection(); invalidate();
    }

    /** Copies the selected object a little below-right and selects the copy. */
    public void duplicateObject() {
        float dx = 14, dy = 14;
        if (selText >= 0 && selText < texts.size()) {
            List<PageText> next = new ArrayList<>(texts);
            PageText copy = texts.get(selText).copy();
            copy.move(dx, dy);
            next.add(copy);
            selText = next.size() - 1;
            commitObjects(items, next, images);
        } else if (selImage >= 0 && selImage < images.size()) {
            List<PageImage> next = new ArrayList<>(images);
            PageImage copy = images.get(selImage).copy();
            copy.move(dx, dy);
            next.add(copy);
            selImage = next.size() - 1;
            commitObjects(items, texts, next);
        } else return;
        reportObjects(); invalidate();
    }

    public void clearObjectSelection() {
        if (selText < 0 && selImage < 0) return;
        selText = selImage = -1;
        reportObjects(); invalidate();
    }

    private boolean rewind(ArrayDeque<State> from, ArrayDeque<State> to) {
        discardGesture(); invalidate();
        if (page==null || from.isEmpty()) return false;
        selected.clear(); selText = selImage = -1;
        to.addLast(new State(items, texts, images));
        State state = from.removeLast();
        items = state.items; texts = state.texts; images = state.images;
        publish(); reportSelection(); reportObjects(); return true;
    }
    private void commit(List<Item> next) { commitObjects(next, texts, images); }
    private void commitObjects(List<Item> ink, List<PageText> textList, List<PageImage> pictureList) {
        past.addLast(new State(items, texts, images));
        if (past.size()>HISTORY) past.removeFirst();
        future.clear();
        items = ink; texts = textList; images = pictureList;
        publish();
    }
    private void publish() {
        JSONArray ink=new JSONArray();
        for (Item item : items) ink.put(item.json);
        JSONArray ts=new JSONArray();
        for (PageText t : texts) ts.put(t.encode());
        JSONArray im=new JSONArray();
        for (PageImage g : images) im.put(g.encode());
        try {
            if (page != null) {
                page.put("ink",ink); page.put("texts",ts); page.put("images",im);
            }
        } catch (JSONException e) { throw new IllegalStateException(e); }
        invalidate();
        if (onChanged!=null) onChanged.run();
    }

    private static int dimension(JSONObject page, String key) {
        return page==null ? 1 : Math.max(1,page.optInt(key,1));
    }

    /** Draws committed content into [0,w] x [0,h] without fitting or changing the caller's transform. */
    public static void drawPage(Canvas canvas, JSONObject page, Bitmap background) {
        drawPage(canvas, page, background, null, null);
    }

    /** As above, and also draws placed pictures when the notebook folder is known. */
    public static void drawPage(Canvas canvas, JSONObject page, Bitmap background, Store store, String id) {
        if (page==null) return;
        int w=dimension(page,"w"), h=dimension(page,"h"), save=canvas.save();
        try {
            if (!canvas.clipRect(0,0,w,h)) return;
            new InkPaper().draw(canvas,page,w,h,background);
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            JSONArray im=page.optJSONArray("images");
            if (im!=null) for (int i=0; i<im.length(); i++) {
                PageImage g=PageImage.decode(im.optJSONObject(i));
                if (g!=null) g.draw(canvas, PageImage.load(store, id, g.path));
            }
            JSONArray ink=page.optJSONArray("ink");
            if (ink!=null) for (int i=0; i<ink.length(); i++) {
                InkStroke s=InkStroke.decode(ink.optJSONObject(i),w,h);
                if (s!=null) s.draw(canvas,paint);
            }
            JSONArray ts=page.optJSONArray("texts");
            if (ts!=null) for (int i=0; i<ts.length(); i++) {
                PageText t=PageText.decode(ts.optJSONObject(i),w,h);
                if (t!=null) t.draw(canvas);
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
    private float screenX(float x) { return ox + x*scale; }
    private float screenY(float y) { return oy + y*scale; }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c); c.drawColor(Ui.CANVAS);
        if (!geometry()) return;
        List<Item> live = mode==AREA && eraseWork!=null ? eraseWork : items;
        int save=c.save();
        try {
            c.clipRect(getPaddingLeft(),getPaddingTop(),getWidth()-getPaddingRight(),getHeight()-getPaddingBottom());
            c.translate(ox,oy); c.scale(scale,scale);
            if (!c.clipRect(0,0,w,h)) return;
            paper.draw(c,page,w,h,bitmap);
            for (int i = 0; i < images.size(); i++) {
                PageImage g = images.get(i);
                Bitmap picture = PageImage.load(store, noteId, g.path);
                if (mode==MOVE_IMAGE && i==selImage) {
                    c.save(); c.translate(objDx, objDy); g.draw(c, picture); c.restore();
                } else g.draw(c, picture);
            }
            boolean transforming = mode==MOVE || mode==SCALE || mode==ROTATE;
            for (Item item : live) {
                if (item.stroke==null || erased.contains(item)) continue;
                if (transforming && selected.contains(item)) continue;
                item.stroke.draw(c,inkPaint);
            }
            if (transforming) {
                float cx=(groupVisual.left+groupVisual.right)*.5f, cy=(groupVisual.top+groupVisual.bottom)*.5f;
                c.save();
                c.translate(cx+moveX, cy+moveY);
                c.rotate((float)Math.toDegrees(angle));
                c.scale(scaleK, scaleK);
                c.translate(-cx, -cy);
                for (Item item : items) if (selected.contains(item) && item.stroke!=null)
                    item.stroke.draw(c,inkPaint);
                c.restore();
            }
            for (int i = 0; i < texts.size(); i++) {
                PageText t = texts.get(i);
                if (mode==MOVE_TEXT && i==selText) {
                    c.save(); c.translate(objDx, objDy); t.draw(c); c.restore();
                } else t.draw(c);
            }
            if (draft!=null) draft.draw(c,inkPaint);
            drawOverlayPage(c);
        } finally { c.restoreToCount(save); }
        drawSelectionScreen(c);
    }

    /** Overlay pieces that live in page coordinates. */
    private void drawOverlayPage(Canvas c) {
        overlay.setPathEffect(null);
        overlay.setStyle(Paint.Style.STROKE); overlay.setStrokeWidth(1.5f*dp/scale);
        overlay.setColor(Ui.dark ? 0xff6fc3b0 : 0xff19796d);
        if (mode==LASSO) {
            c.drawPath(loopPath,overlay);
            if (loop.size()>1) c.drawLine(endX,endY,loop.get(0).x,loop.get(0).y,overlay);
        } else if (mode==ERASE || mode==AREA) {
            c.drawCircle(endX,endY,eraseRadius,overlay);
        }
    }

    /** Selection frame, corner scale grips and the rotate grip, drawn in screen pixels. */
    private void drawSelectionScreen(Canvas c) {
        if (selected.isEmpty() && selText < 0 && selImage < 0) return;
        if (!selected.isEmpty() && mode!=LASSO) drawInkFrame(c);
        drawObjectFrame(c);
    }

    private void drawInkFrame(Canvas c) {
        RectF frame = groupVisual;
        if (mode==MOVE || mode==SCALE || mode==ROTATE) {
            float cx=(groupVisual.left+groupVisual.right)*.5f, cy=(groupVisual.top+groupVisual.bottom)*.5f;
            frame = liveBounds(cx, cy);
        }
        float left=screenX(frame.left), top=screenY(frame.top);
        float right=screenX(frame.right), bottom=screenY(frame.bottom);
        overlay.setStyle(Paint.Style.STROKE);
        overlay.setPathEffect(dash);
        overlay.setStrokeWidth(1.4f*dp);
        overlay.setColor(Ui.dark ? 0xff8fd0c2 : 0xff19796d);
        c.drawRect(left-4*dp, top-4*dp, right+4*dp, bottom+4*dp, overlay);
        overlay.setPathEffect(null);

        float r=6*dp;
        for (float[] corner : corners(left, top, right, bottom)) {
            overlay.setStyle(Paint.Style.FILL);
            overlay.setColor(Ui.SURFACE);
            c.drawCircle(corner[0], corner[1], r, overlay);
            overlay.setStyle(Paint.Style.STROKE);
            overlay.setStrokeWidth(1.6f*dp);
            overlay.setColor(Ui.dark ? 0xff8fd0c2 : 0xff19796d);
            c.drawCircle(corner[0], corner[1], r, overlay);
        }
        float[] spin = rotateGrip(left, top, right, 30*dp);
        c.drawLine((left+right)*.5f, top-4*dp, spin[0], spin[1]+r, overlay);
        overlay.setStyle(Paint.Style.FILL);
        overlay.setColor(Ui.SURFACE);
        c.drawCircle(spin[0], spin[1], r+1.5f*dp, overlay);
        overlay.setStyle(Paint.Style.STROKE);
        overlay.setStrokeWidth(1.6f*dp);
        overlay.setColor(Ui.dark ? 0xff8fd0c2 : 0xff19796d);
        c.drawCircle(spin[0], spin[1], r+1.5f*dp, overlay);
        RectF arc = new RectF(spin[0]-4*dp, spin[1]-4*dp, spin[0]+4*dp, spin[1]+4*dp);
        c.drawArc(arc, -60, 280, false, overlay);
    }

    private void drawObjectFrame(Canvas c) {
        RectF box = null;
        if (selText >= 0 && selText < texts.size()) {
            PageText t = texts.get(selText);
            box = new RectF(t.bounds());
            if (mode==MOVE_TEXT) box.offset(objDx, objDy);
        } else if (selImage >= 0 && selImage < images.size()) {
            box = images.get(selImage).bounds();
            if (mode==MOVE_IMAGE) box.offset(objDx, objDy);
        }
        if (box == null) return;
        float left=screenX(box.left), top=screenY(box.top);
        float right=screenX(box.right), bottom=screenY(box.bottom);
        overlay.setStyle(Paint.Style.STROKE);
        overlay.setPathEffect(dash);
        overlay.setStrokeWidth(1.4f*dp);
        overlay.setColor(Ui.dark ? 0xff8fd0c2 : 0xff19796d);
        c.drawRect(left-5*dp, top-5*dp, right+5*dp, bottom+5*dp, overlay);
        overlay.setPathEffect(null);
    }

    /** Bounding box of the selection under the live translate/scale/rotate. */
    private RectF liveBounds(float cx, float cy) {
        double cos=Math.cos(angle), sin=Math.sin(angle);
        RectF out=new RectF(Float.MAX_VALUE,Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE);
        float[] xs={groupVisual.left,groupVisual.right,groupVisual.right,groupVisual.left};
        float[] ys={groupVisual.top,groupVisual.top,groupVisual.bottom,groupVisual.bottom};
        for (int i=0;i<4;i++) {
            double dx=(xs[i]-cx)*scaleK, dy=(ys[i]-cy)*scaleK;
            float px=(float)(cx+moveX+dx*cos-dy*sin), py=(float)(cy+moveY+dx*sin+dy*cos);
            out.left=Math.min(out.left,px); out.right=Math.max(out.right,px);
            out.top=Math.min(out.top,py); out.bottom=Math.max(out.bottom,py);
        }
        return out;
    }

    private static float[][] corners(float l, float t, float r, float b) {
        return new float[][] {{l,t},{r,t},{r,b},{l,b}};
    }
    private static float[] rotateGrip(float l, float t, float r, float offset) {
        return new float[] {(l+r)*.5f, t - offset};
    }

    private void intercept(boolean disallow) {
        if (getParent()!=null) getParent().requestDisallowInterceptTouchEvent(disallow);
    }
    private void discardGesture() {
        mode=NONE; owner=navA=navB=-1; stylus=false; handle=HANDLE_NONE;
        draft=null; erased.clear(); loop.clear(); loopPath.rewind();
        moveX=moveY=0; scaleK=1; angle=0; eraseWork=null; areaTouched=false;
        objDx=objDy=0;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // A rotation or a window resize moves the overlay anchors; tell the host again.
        post(() -> { reportSelection(); reportObjects(); });
    }

    @Override protected void onDetachedFromWindow() {
        discardGesture(); intercept(false); super.onDetachedFromWindow();
    }
    private static boolean isStylus(int type) {
        return type==MotionEvent.TOOL_TYPE_STYLUS || type==MotionEvent.TOOL_TYPE_ERASER;
    }
    private float press(boolean stylusTool, float raw) {
        if (!stylusTool || !Float.isFinite(raw)) return 1;
        float value = InkStroke.clamp(raw, 0, 1);
        return pressure == 1 ? value : (float)Math.pow(value, pressure);
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
                    navigate(e);
                    int remaining = -1;
                    for (int i = 0; i < e.getPointerCount(); i++)
                        if (e.getPointerId(i) != id) { remaining = i; break; }
                    if (remaining >= 0 && "roam".equals(tool)) {
                        // After a pinch, the finger still down keeps moving the page.
                        int pid = e.getPointerId(remaining);
                        float rx = e.getX(remaining), ry = e.getY(remaining);
                        discardGesture();
                        owner = pid; mode = PAN;
                        startCamX = cameraX; startCamY = cameraY; startSx = rx; startSy = ry;
                    } else startNavigation(e,index);
                }
                if (action==MotionEvent.ACTION_UP) { discardGesture(); intercept(false); }
                break;
            default: return super.onTouchEvent(e);
        }
        invalidate(); return true;
    }

    private void begin(MotionEvent e, int i) {
        float sx=e.getX(i), sy=e.getY(i);
        int type=e.getToolType(i);
        stylus=isStylus(type);
        if ("roam".equals(tool)) {
            // One finger drags the page; a second finger adds pinch zoom; a double tap zooms.
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastTapTime < 320 && Math.hypot(sx - lastTapX, sy - lastTapY) < 48 * dp) {
                lastTapTime = 0;
                if (zoom > 1.05f) {
                    zoom = 1;
                    cameraX = w * .5f;
                    cameraY = h * .5f;
                } else {
                    float fx = pageX(sx), fy = pageY(sy);
                    zoom = 2.5f;
                    cameraX = fx;
                    cameraY = fy;
                }
                geometry(); notifyZoom(); invalidate();
                return;
            }
            lastTapTime = now; lastTapX = sx; lastTapY = sy;
            owner=e.getPointerId(i);
            mode=PAN; startCamX=cameraX; startCamY=cameraY; startSx=sx; startSy=sy;
            anchorX=pageX(sx); anchorY=pageY(sy);
            return;
        }
        if ("text".equals(tool)) {
            if (!selected.isEmpty()) { selected.clear(); reportSelection(); }
            int hit = textAt(pageX(sx), pageY(sy));
            if (hit >= 0) {
                selText = hit; selImage = -1;
                owner=e.getPointerId(i);
                mode=MOVE_TEXT; anchorX=pageX(sx); anchorY=pageY(sy);
                reportObjects(); reportSelection();
            } else {
                selText = selImage = -1;
                reportObjects();
                if (inside(sx,sy) && listener!=null) listener.onTextRequested(pageX(sx), pageY(sy));
            }
            return;
        }
        if ("image".equals(tool)) {
            if (!selected.isEmpty()) { selected.clear(); reportSelection(); }
            int hit = imageAt(pageX(sx), pageY(sy));
            selImage = hit; selText = -1;
            if (hit >= 0) {
                owner=e.getPointerId(i);
                mode=MOVE_IMAGE; anchorX=pageX(sx); anchorY=pageY(sy);
                reportObjects();
            } else reportObjects();
            reportSelection();
            return;
        }
        // An existing selection answers first: its grips may sit outside the page.
        if (!selected.isEmpty() && !"erase".equals(tool)) {
            handle = handleAt(sx, sy);
            if (handle!=HANDLE_NONE) {
                owner=e.getPointerId(i);
                anchorX=pageX(sx); anchorY=pageY(sy);
                endX=anchorX; endY=anchorY;
                float cx=(groupVisual.left+groupVisual.right)*.5f, cy=(groupVisual.top+groupVisual.bottom)*.5f;
                startRadius=(float)Math.hypot(anchorX-cx, anchorY-cy);
                lastTouchAngle=(float)Math.atan2(anchorY-cy, anchorX-cx);
                mode = handle==HANDLE_ROTATE ? ROTATE : handle==HANDLE_INSIDE ? MOVE : SCALE;
                moveX=moveY=0; scaleK=1; angle=0;
                return;
            }
            if (!inside(sx,sy)) return;
            selected.clear(); reportSelection();
        }
        if (!inside(sx,sy)) return; // gutter can initiate a pinch, but never an ink stroke
        if (stylusOnly && !stylus) { owner=-1; return; }
        owner=e.getPointerId(i);
        String active=type==MotionEvent.TOOL_TYPE_ERASER ? "erase" : tool;
        anchorX=endX=pageX(sx); anchorY=endY=pageY(sy);
        magic="magic".equals(active);
        if (InkStroke.drawable(active) || magic) {
            mode=DRAW;
            draft = "shape".equals(active)
                ? new InkStroke("shape", shapeKind, argb, width)
                : new InkStroke(magic ? "pen" : active, argb, width);
            if ("marker".equals(draft.tool)) draft.markerAlpha(markerAlpha);
            draft.sample(endX,endY,press(stylus,e.getPressure(i)), e.getEventTime());
        } else if ("erase".equals(active)) {
            mode = areaErase ? AREA : ERASE;
            eraseRadius=Math.max(width*.5f,8*dp/scale);
            if (mode==AREA) { eraseWork=new ArrayList<>(items); areaTouched=false; }
            else erase(endX,endY,endX,endY);
        } else {
            mode=LASSO; loop.add(new PointF(endX,endY)); loopPath.moveTo(endX,endY);
        }
    }

    private int textAt(float x, float y) {
        for (int i = texts.size() - 1; i >= 0; i--) if (texts.get(i).hit(x, y)) return i;
        return -1;
    }

    private int imageAt(float x, float y) {
        for (int i = images.size() - 1; i >= 0; i--) if (images.get(i).hit(x, y)) return i;
        return -1;
    }

    private int handleAt(float sx, float sy) {
        if (selected.isEmpty()) return HANDLE_NONE;
        float left=screenX(groupVisual.left), top=screenY(groupVisual.top);
        float right=screenX(groupVisual.right), bottom=screenY(groupVisual.bottom);
        float slop=18*dp;
        float[] spin=rotateGrip(left, top, right, 30*dp);
        if (Math.hypot(sx-spin[0], sy-spin[1])<=slop) return HANDLE_ROTATE;
        float[][] c=corners(left, top, right, bottom);
        for (int i=0; i<c.length; i++) if (Math.hypot(sx-c[i][0], sy-c[i][1])<=slop) return i;
        if (sx>=left-slop*.4f && sx<=right+slop*.4f && sy>=top-slop*.4f && sy<=bottom+slop*.4f)
            return HANDLE_INSIDE;
        return HANDLE_NONE;
    }

    private void samples(MotionEvent e, int i) {
        for (int k=0; k<e.getHistorySize(); k++)
            sample(e.getHistoricalX(i,k),e.getHistoricalY(i,k),e.getHistoricalPressure(i,k),
                e.getHistoricalEventTime(k));
        sample(e.getX(i),e.getY(i),e.getPressure(i), e.getEventTime());
    }
    private void sample(float sx, float sy, float rawPressure, long time) {
        if (!Float.isFinite(sx) || !Float.isFinite(sy)) return;
        if (mode==PAN) {
            cameraX=startCamX-(sx-startSx)/scale;
            cameraY=startCamY-(sy-startSy)/scale;
            geometry(); invalidate(); return;
        }
        float x=pageX(sx), y=pageY(sy);
        float cx=(groupVisual.left+groupVisual.right)*.5f, cy=(groupVisual.top+groupVisual.bottom)*.5f;
        switch (mode) {
            case DRAW: draft.sample(x,y,press(stylus,rawPressure),time); break;
            case ERASE: erase(endX,endY,x,y); break;
            case AREA: eraseArea(endX,endY,x,y); break;
            case LASSO:
                PointF prev=loop.get(loop.size()-1);
                if (Math.hypot(x-prev.x,y-prev.y)*scale>=2*dp) { loop.add(new PointF(x,y)); loopPath.lineTo(x,y); }
                break;
            case MOVE_TEXT: {
                PageText t = selectedText();
                if (t != null) {
                    RectF b = t.bounds();
                    objDx = InkStroke.clamp(x-anchorX, -b.left, w-b.right);
                    objDy = InkStroke.clamp(y-anchorY, -b.top, h-b.bottom);
                }
                break;
            }
            case MOVE_IMAGE: {
                if (selImage >= 0 && selImage < images.size()) {
                    PageImage g = images.get(selImage);
                    objDx = InkStroke.clamp(x-anchorX, -g.x, w-g.w-g.x);
                    objDy = InkStroke.clamp(y-anchorY, -g.y, h-g.h-g.y);
                }
                break;
            }
            case MOVE:
                moveX=InkStroke.clamp(x-anchorX,-group.left,w-group.right);
                moveY=InkStroke.clamp(y-anchorY,-group.top,h-group.bottom);
                dragX=moveX; dragY=moveY;
                break;
            case SCALE: {
                float now=(float)Math.hypot(x-cx, y-cy);
                float fit=Math.min(w/Math.max(1f,groupVisual.width()), h/Math.max(1f,groupVisual.height()));
                scaleK=InkStroke.clamp(now/Math.max(1e-3f,startRadius), .15f, Math.max(1f,Math.min(6f,fit)));
                break;
            }
            case ROTATE: {
                float now=(float)Math.atan2(y-cy, x-cx);
                float delta=now-lastTouchAngle;
                if (delta>Math.PI) delta-=2*Math.PI;
                if (delta<-Math.PI) delta+=2*Math.PI;
                angle+=delta; lastTouchAngle=now;
                break;
            }
        }
        endX=x; endY=y;
    }
    private void erase(float x0,float y0,float x1,float y1) {
        for (Item item : items) if (item.stroke!=null && !erased.contains(item) && item.stroke.hits(x0,y0,x1,y1,eraseRadius))
            erased.add(item);
    }
    private void eraseArea(float x0,float y0,float x1,float y1) {
        if (eraseWork==null) return;
        List<Item> next=new ArrayList<>(eraseWork.size());
        for (Item item : eraseWork) {
            List<InkStroke> pieces = item.stroke==null ? null : item.stroke.eraseRange(x0,y0,x1,y1,eraseRadius);
            if (pieces==null) { next.add(item); continue; }
            areaTouched=true;
            for (InkStroke piece : pieces) next.add(new Item(piece));
        }
        eraseWork=next;
    }

    private void finish() {
        List<Item> next=null;
        List<PageText> nextTexts=null;
        List<PageImage> nextImages=null;
        if (mode==DRAW) {
            if (magic) {
                ShapeRecognizer.Match match = ShapeRecognizer.recognize(draft, magicTarget);
                if (match != null) {
                    InkStroke shape=new InkStroke("shape", match.kind, argb, width);
                    shape.sampleRaw(match.x0, match.y0, 0);
                    shape.sampleRaw(match.x1, match.y1, 0);
                    next=new ArrayList<>(items); next.add(new Item(shape));
                }
            }
            if (next==null) { draft.taper(); next=new ArrayList<>(items); next.add(new Item(draft)); }
        } else if (mode==ERASE && !erased.isEmpty()) {
            next=new ArrayList<>(items.size()-erased.size());
            for (Item item : items) if (!erased.contains(item)) next.add(item);
            selected.removeAll(erased); updateGroup();
        } else if (mode==AREA && areaTouched) {
            next=eraseWork;
            selected.clear();
        } else if (mode==MOVE && (moveX!=0 || moveY!=0)) {
            next=new ArrayList<>(items.size()); HashSet<Item> moved=new HashSet<>();
            for (Item item : items) {
                if (selected.contains(item) && item.stroke!=null) {
                    Item copy=new Item(item.stroke.shifted(moveX,moveY,w,h)); moved.add(copy); next.add(copy);
                } else next.add(item);
            }
            selected.clear(); selected.addAll(moved);
        } else if ((mode==SCALE && scaleK!=1) || (mode==ROTATE && angle!=0)) {
            float cx=(groupVisual.left+groupVisual.right)*.5f, cy=(groupVisual.top+groupVisual.bottom)*.5f;
            next=new ArrayList<>(items.size()); HashSet<Item> moved=new HashSet<>();
            for (Item item : items) {
                if (selected.contains(item) && item.stroke!=null) {
                    Item copy=new Item(item.stroke.transformed(cx,cy,scaleK,angle)); moved.add(copy); next.add(copy);
                } else next.add(item);
            }
            selected.clear(); selected.addAll(moved);
        } else if (mode==LASSO) { selectLoop(); }
        else if (mode==MOVE_TEXT && (objDx!=0 || objDy!=0) && selText>=0 && selText<texts.size()) {
            nextTexts=new ArrayList<>(texts);
            PageText moved = texts.get(selText).copy();
            moved.move(objDx, objDy);
            nextTexts.set(selText, moved);
        } else if (mode==MOVE_IMAGE && (objDx!=0 || objDy!=0) && selImage>=0 && selImage<images.size()) {
            nextImages=new ArrayList<>(images);
            PageImage moved = images.get(selImage).copy();
            moved.move(objDx, objDy);
            nextImages.set(selImage, moved);
        }
        discardGesture();
        if (next!=null) commit(next); // publish only after the gesture state is consistent
        else if (nextTexts!=null) commitObjects(items, nextTexts, images);
        else if (nextImages!=null) commitObjects(items, texts, nextImages);
        updateGroup();
        reportSelection();
        reportObjects();
    }
    private void updateGroup() {
        boolean first=true;
        for (Item item : selected) {
            if (item.stroke==null) continue;
            RectF b=item.stroke.bounds; float r=item.stroke.width*.9f;
            if (first) { group.set(b); groupVisual.set(b.left-r,b.top-r,b.right+r,b.bottom+r); first=false; }
            else {
                // RectF.union(RectF) drops zero-area bounds (dots and axis-aligned lines).
                group.left=Math.min(group.left,b.left); group.top=Math.min(group.top,b.top);
                group.right=Math.max(group.right,b.right); group.bottom=Math.max(group.bottom,b.bottom);
                groupVisual.union(b.left-r,b.top-r,b.right+r,b.bottom+r);
            }
        }
        if (first) { group.setEmpty(); groupVisual.setEmpty(); selected.clear(); }
    }
    private void selectLoop() {
        selected.clear(); selText = selImage = -1;
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

    private void reportSelection() {
        if (listener==null) return;
        RectF rect=null;
        if (!selected.isEmpty()) {
            rect=new RectF(screenX(groupVisual.left), screenY(groupVisual.top),
                screenX(groupVisual.right), screenY(groupVisual.bottom));
        }
        listener.onSelectionChanged(!selected.isEmpty(), rect);
    }
    private void reportObjects() {
        if (listener==null) return;
        RectF rect=null; boolean isText = selText >= 0;
        if (selText >= 0 && selText < texts.size()) {
            RectF b=texts.get(selText).bounds();
            rect=new RectF(screenX(b.left), screenY(b.top), screenX(b.right), screenY(b.bottom));
        } else if (selImage >= 0 && selImage < images.size()) {
            RectF b=images.get(selImage).bounds();
            rect=new RectF(screenX(b.left), screenY(b.top), screenX(b.right), screenY(b.bottom));
        }
        listener.onObjectChanged(rect != null, isText, rect);
    }
    private void notifyZoom() { if (listener!=null) listener.onZoomChanged(zoom); }

    private void startNavigation(MotionEvent e, int skip) {
        int a=-1,b=-1;
        for (int i=0; i<e.getPointerCount(); i++) if (i!=skip && !isStylus(e.getToolType(i))) {
            if (a<0) a=i; else { b=i; break; }
        }
        if (b<0) { if (mode==NAV) discardGesture(); return; }
        discardGesture(); // converting a finger edit into navigation never leaves a stray mark
        mode=NAV; navA=e.getPointerId(a); navB=e.getPointerId(b);
        prevMidX=(e.getX(a)+e.getX(b))*.5f; prevMidY=(e.getY(a)+e.getY(b))*.5f;
        prevSpan=(float)Math.hypot(e.getX(a)-e.getX(b), e.getY(a)-e.getY(b));
    }
    private void navigate(MotionEvent e) {
        int a=e.findPointerIndex(navA), b=e.findPointerIndex(navB);
        if (a<0 || b<0) { startNavigation(e,-1); return; }
        float x=(e.getX(a)+e.getX(b))*.5f, y=(e.getY(a)+e.getY(b))*.5f;
        float span=(float)Math.hypot(e.getX(a)-e.getX(b), e.getY(a)-e.getY(b));
        float fx=(prevMidX-ox)/scale, fy=(prevMidY-oy)/scale, oldZoom=zoom;
        if (prevSpan>dp && span>dp) zoom=InkStroke.clamp(zoom*(span/prevSpan),1,8);
        float newScale=scale*(zoom/oldZoom);
        cameraX=fx-(x-vcx)/newScale; cameraY=fy-(y-vcy)/newScale;
        geometry(); prevMidX=x; prevMidY=y; prevSpan=span; notifyZoom();
    }
}
