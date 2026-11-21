/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation, not affiliated with Goodnotes.
 */
package io.github.kkkzheli.paperharbor;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen editor: canvas, icon tool rail, tool choosers, palette, page grid. */
final class EditorScreen extends FrameLayout {
  interface Host {
    void exit();
    void deleted();
    void exportPdf(String id, JSONObject snapshot);
    void exportImage(String id, JSONObject snapshot, int page);
    void backup(String id, JSONObject snapshot);
  }

  private static final String[] TOOL_KEYS =
      {"pen", "brush", "marker", "erase", "lasso", "shape", "magic"};

  private final Activity a;
  private final Store store;
  private final Prefs prefs;
  private final Host host;
  private final StudyAudio study;
  private final String id;
  private final JSONObject doc;
  private final ExecutorService worker = Executors.newFixedThreadPool(2, r -> {
    Thread t = new Thread(r, "editor");
    t.setDaemon(true);
    return t;
  });
  private final ExecutorService saver = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "editor-save");
    t.setDaemon(true);
    return t;
  });
  private final Handler ui = new Handler(Looper.getMainLooper());

  private FrameLayout stage;
  private InkView ink;
  private Bitmap background;
  private int index;
  private String tool;  private int color;
  private long changeSeq, savedSeq;
  private String pendingJson;
  private boolean draining;
  private boolean saveFailed;
  private View loadingOverlay;
  private TextView titleView, statusView;
  private ImageButton bookmarkButton;
  private ImageButton undoButton, redoButton;
  private ImageButton penButton, markerButton, eraserButton, lassoButton, shapeButton, magicButton;
  private ColorChipButton colorButton;
  private LinearLayout selectionBubble;
  private View zoomChip;

  EditorScreen(Activity activity, Store store, Prefs prefs, StudyAudio study, String id, JSONObject doc, int page, Host host) {
    super(activity);
    this.a = activity;
    this.store = store;
    this.prefs = prefs;
    this.study = study;
    this.id = id;
    this.doc = doc;
    this.index = Math.max(0, Math.min(page, doc.optJSONArray("pages").length() - 1));
    this.host = host;
    this.tool = normalizeTool(prefs.tool());
    this.color = prefs.color();
    setBackgroundColor(Ui.CANVAS);
    build();
    loadPage(true);
  }

  private String normalizeTool(String value) {
    switch (value == null ? "" : value) {
      case "brush": case "marker": case "erase": case "lasso": case "shape": case "magic":
        return value;
      case "line": case "rect":
        return "shape";
      default:
        return "pen";
    }
  }

  private int pageCount() { return doc.optJSONArray("pages").length(); }

  private JSONObject page() { return doc.optJSONArray("pages").optJSONObject(index); }

  private void build() {
    LinearLayout column = Ui.column(a);
    addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    LinearLayout bar = Ui.row(a);
    bar.setBackgroundColor(Ui.SURFACE);
    bar.setPadding(Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6), Ui.dp(a, 8));
    bar.addView(Ui.icon(a, R.drawable.ic_back, "返回书架", 44, Ui.INK, this::requestExit));
    LinearLayout titles = Ui.column(a);
    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    tlp.leftMargin = Ui.dp(a, 4);
    titles.setLayoutParams(tlp);
    titleView = Ui.text(a, doc.optString("title"), 16, Ui.INK);
    titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    titleView.setSingleLine(true);
    titleView.setEllipsize(TextUtils.TruncateAt.END);
    titleView.setContentDescription("笔记标题");
    titleView.setOnClickListener(v -> showRenameSheet());
    titles.addView(titleView);
    statusView = Ui.text(a, "本地笔记", 10.5f, Ui.MUTED);
    statusView.setPadding(0, Ui.dp(a, 1), 0, 0);
    titles.addView(statusView);
    bar.addView(titles);
    bookmarkButton = Ui.icon(a, R.drawable.ic_bookmark, "书签", 44, Ui.INK, this::toggleBookmark);
    bar.addView(bookmarkButton);
    bar.addView(Ui.icon(a, R.drawable.ic_more, "更多工具", 44, Ui.INK, this::showMoreSheet));
    column.addView(bar);

    View rule = new View(a);
    rule.setBackgroundColor(Ui.LINE);
    column.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(a, .7f))));

    stage = new FrameLayout(a);
    stage.setBackgroundColor(Ui.CANVAS);
    column.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    ink = new InkView(a);
    ink.setContentDescription("笔记画布");
    ink.setListener(new InkView.Listener() {
      @Override public void onSelectionChanged(boolean hasSelection, RectF rect) {
        updateSelectionBubble(hasSelection, rect);
      }
      @Override public void onZoomChanged(float zoom) { updateZoomChip(zoom); }
    });
    stage.addView(ink, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    selectionBubble = Ui.row(a);
    selectionBubble.setBackground(Ui.round(a, Ui.INK, 24));
    selectionBubble.setElevation(Ui.dp(a, 10));
    selectionBubble.setPadding(Ui.dp(a, 2), Ui.dp(a, 2), Ui.dp(a, 2), Ui.dp(a, 2));
    selectionBubble.addView(Ui.icon(a, R.drawable.ic_duplicate, "克隆选区", 40, Ui.ON_INK, () -> {
      ink.cloneSelection();
      changed();
      updateHistory();
    }));
    selectionBubble.addView(Ui.icon(a, R.drawable.ic_trash, "删除选区", 40, Ui.ON_INK, () -> {
      ink.deleteSelection();
      changed();
      updateHistory();
    }));
    FrameLayout.LayoutParams bubbleLp = new FrameLayout.LayoutParams(Ui.dp(a, 96), Ui.dp(a, 44));
    selectionBubble.setLayoutParams(bubbleLp);
    selectionBubble.setVisibility(GONE);
    stage.addView(selectionBubble);

    zoomChip = Ui.icon(a, R.drawable.ic_fit, "适配页面", 40, Ui.INK, () -> {
      ink.resetZoom();
      updateZoomChip(1);
    });
    zoomChip.setBackground(Ui.ripple(Ui.SURFACE, 22, a));
    zoomChip.setElevation(Ui.dp(a, 4));
    FrameLayout.LayoutParams zoomLp = new FrameLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40));
    zoomLp.gravity = Gravity.END | Gravity.TOP;
    zoomLp.setMargins(0, Ui.dp(a, 10), Ui.dp(a, 10), 0);
    zoomChip.setVisibility(GONE);
    stage.addView(zoomChip, zoomLp);

    loadingOverlay = Ui.column(a);
    ((LinearLayout) loadingOverlay).setGravity(Gravity.CENTER);
    loadingOverlay.setBackgroundColor(Ui.dark ? 0xd915130f : 0xd9f3eee4);
    ProgressBar barProgress = new ProgressBar(a);
    ((LinearLayout) loadingOverlay).addView(barProgress);
    TextView loadingText = Ui.text(a, "正在准备页面…", 12.5f, Ui.MUTED);
    loadingText.setPadding(0, Ui.dp(a, 10), 0, 0);
    ((LinearLayout) loadingOverlay).addView(loadingText);
    stage.addView(loadingOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    LinearLayout controls = Ui.column(a);
    controls.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), Ui.dp(a, 12));
    FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    clp.gravity = Gravity.BOTTOM;
    addView(controls, clp);

    LinearLayout nav = Ui.row(a);
    nav.setBackground(Ui.round(a, Ui.SURFACE, 24));
    nav.setElevation(Ui.dp(a, 6));
    nav.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
    undoButton = Ui.icon(a, R.drawable.ic_undo, "撤销", 44, Ui.INK, () -> { if (ink.undo()) changed(); });
    redoButton = Ui.icon(a, R.drawable.ic_redo, "重做", 44, Ui.INK, () -> { if (ink.redo()) changed(); });
    nav.addView(undoButton);
    nav.addView(redoButton);
    View gap = new View(a);
    nav.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
    ImageButton prev = Ui.icon(a, R.drawable.ic_back, "上一页", 40, Ui.INK, () -> go(index - 1));
    ImageButton next = Ui.icon(a, R.drawable.ic_back, "下一页", 40, Ui.INK, () -> go(index + 1));
    next.setRotation(180);
    TextView pageLabel = Ui.text(a, "", 13, Ui.INK);
    pageLabel.setTypeface(Typeface.MONOSPACE);
    pageLabel.setGravity(Gravity.CENTER);
    pageLabel.setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 10), 0);
    pageLabel.setOnClickListener(v -> showPagesSheet());
    pageLabel.setContentDescription("页面列表");
    nav.addView(prev);
    nav.addView(pageLabel);
    nav.addView(next);
    pageLabelHolder = pageLabel;
    controls.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    HorizontalScrollView scroll = new HorizontalScrollView(a);
    scroll.setHorizontalScrollBarEnabled(false);
    scroll.setFillViewport(true);
    LinearLayout tools = Ui.row(a);
    tools.setBackground(Ui.round(a, Ui.SURFACE, 24));
    tools.setElevation(Ui.dp(a, 6));
    tools.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
    scroll.addView(tools);

    penButton = Ui.icon(a, R.drawable.ic_pen, "画笔", 42, Ui.INK, this::onPenButton);
    markerButton = Ui.icon(a, R.drawable.ic_marker, "荧光笔", 42, Ui.INK, () -> selectTool("marker"));
    eraserButton = Ui.icon(a, R.drawable.ic_eraser, "橡皮擦", 42, Ui.INK, this::onEraserButton);
    lassoButton = Ui.icon(a, R.drawable.ic_lasso, "套索", 42, Ui.INK, () -> selectTool("lasso"));
    shapeButton = Ui.icon(a, R.drawable.ic_shapes, "图形", 42, Ui.INK, this::showShapeSheet);
    magicButton = Ui.icon(a, R.drawable.ic_magic, "魔法笔", 42, Ui.INK, this::onMagicButton);
    tools.addView(penButton);
    tools.addView(markerButton);
    tools.addView(eraserButton);
    tools.addView(lassoButton);
    tools.addView(shapeButton);
    tools.addView(magicButton);
    tools.addView(Ui.verticalDivider(a));
    colorButton = new ColorChipButton(a);
    LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(Ui.dp(a, 42), Ui.dp(a, 42));
    colorButton.setLayoutParams(chipLp);
    colorButton.setContentDescription("颜色");
    colorButton.setOnClickListener(v -> showColorSheet());
    tools.addView(colorButton);
    ImageButton widthButton = Ui.icon(a, R.drawable.ic_width, "笔尖粗细", 42, Ui.INK, this::onWidthButton);
    tools.addView(widthButton);
    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    slp.topMargin = Ui.dp(a, 8);
    controls.addView(scroll, slp);

    applyToolStyles();
    updatePageLabel();
    updateBookmark();
    updateHistory();
    controls.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
      final int bottom = (b - t) + Ui.dp(a, 4);
      if (ink != null && ink.getPaddingBottom() != bottom) {
        controls.post(() -> {
          if (ink != null && ink.getPaddingBottom() != bottom) {
            ink.setPadding(0, ink.getPaddingTop(), 0, bottom);
          }
        });
      }
    });
  }

  private TextView pageLabelHolder;

  // ---- tool routing ---------------------------------------------------------------------------

  private void onPenButton() {
    if ("pen".equals(tool) || "brush".equals(tool)) showPenSheet();
    else selectTool(prefs.softPen() ? "brush" : "pen");
  }

  private void onEraserButton() {
    if ("erase".equals(tool)) showEraserSheet();
    else selectTool("erase");
  }

  private void onMagicButton() {
    if ("magic".equals(tool)) showMagicSheet();
    else selectTool("magic");
  }

  private void onWidthButton() {
    if ("pen".equals(tool) || "brush".equals(tool)) showPenSheet();
    else showWidthSheet();
  }

  // ---- page lifecycle -------------------------------------------------------------------------

  private int pageGen;

  private void loadPage(boolean animate) {
    if (page() == null) return;
    final int generation = ++pageGen;
    final JSONObject target = page();
    final String snapshot = target.toString();
    final boolean pdf = target.optInt("pdf", -1) >= 0;
    loadingOverlay.setVisibility(VISIBLE);
    ink.setEnabled(false);
    worker.execute(() -> {
      Bitmap bitmap = null;
      Exception failure = null;
      try {
        JSONObject copy = new JSONObject(snapshot);
        if (pdf) bitmap = store.background(id, copy, 2000);
      } catch (Exception e) {
        failure = e;
      }
      final Bitmap ready = bitmap;
      final Exception error = failure;
      ui.post(() -> {
        if (generation != pageGen) {
          if (ready != null) ready.recycle();
          return;
        }
        loadingOverlay.setVisibility(GONE);
        if (error != null) {
          showError("无法载入页面", error);
          ink.setEnabled(true);
          return;
        }
        Bitmap old = background;
        background = ready;
        ink.bind(target, ready, this::onInkChanged);
        ink.setEnabled(true);
        applyInkSettings();
        if (old != null && old != ready && !old.isRecycled()) old.recycle();
        updatePageLabel();
        updateBookmark();
        updateHistory();
        updateZoomChip(ink.zoomLevel());
      });
    });
  }

  private void applyInkSettings() {
    ink.tool(tool);
    ink.shapeKind(prefs.shape());
    ink.color(color);
    ink.width(widthForTool());
    ink.areaErase(prefs.eraseMode() == 1);
    ink.stylusOnly(prefs.stylusOnly());
    ink.pressureSensitivity(prefs.pressure());
  }

  private void go(int next) {
    if (next < 0 || next >= pageCount() || next == index) return;
    study.stop();
    index = next;
    loadPage(true);
  }

  private float widthForTool() {
    return prefs.width(tool);
  }

  private void selectTool(String key) {
    tool = key;
    prefs.setTool(key);
    if ("pen".equals(key) || "brush".equals(key)) prefs.setSoftPen("brush".equals(key));
    ink.tool(key);
    ink.width(widthForTool());
    applyToolStyles();
  }

  private void applyToolStyles() {
    Ui.activate(penButton, "pen".equals(tool) || "brush".equals(tool));
    penButton.setImageResource("brush".equals(tool) ? R.drawable.ic_brush : R.drawable.ic_pen);
    Ui.activate(markerButton, "marker".equals(tool));
    Ui.activate(eraserButton, "erase".equals(tool));
    Ui.activate(lassoButton, "lasso".equals(tool));
    Ui.activate(shapeButton, "shape".equals(tool));
    Ui.activate(magicButton, "magic".equals(tool));
    if (colorButton != null) colorButton.setColor(color);
  }

  private void updatePageLabel() {
    if (pageLabelHolder != null) {
      pageLabelHolder.setText((index + 1) + " / " + pageCount());
      pageLabelHolder.setContentDescription(Str.pageFooter(index + 1, pageCount()));
    }
  }

  private void updateBookmark() {
    if (bookmarkButton == null || page() == null) return;
    boolean on = page().optBoolean("bookmark");
    bookmarkButton.setImageResource(on ? R.drawable.ic_bookmark_on : R.drawable.ic_bookmark);
    bookmarkButton.setColorFilter(on ? Ui.ACCENT : Ui.INK);
    bookmarkButton.setContentDescription(on ? "移除书签" : "添加书签");
  }

  private void toggleBookmark() {
    JSONObject p = page();
    if (p == null) return;
    try {
      p.put("bookmark", !p.optBoolean("bookmark"));
      changed();
      updateBookmark();
    } catch (JSONException ignored) { }
  }

  private void updateHistory() {
    if (undoButton == null) return;
    undoButton.setAlpha(ink != null && ink.canUndo() ? 1f : .35f);
    redoButton.setAlpha(ink != null && ink.canRedo() ? 1f : .35f);
  }

  // ---- selection overlay ----------------------------------------------------------------------

  private void updateSelectionBubble(boolean hasSelection, RectF rect) {
    if (selectionBubble == null) return;
    if (!hasSelection || rect == null || stage.getWidth() <= 0) {
      selectionBubble.setVisibility(GONE);
      return;
    }
    int w = Ui.dp(a, 96), h = Ui.dp(a, 44), edge = Ui.dp(a, 8);
    int x = Math.round(rect.centerX() - w / 2f);
    // Clear the rotate grip that sits 30dp above the frame.
    int y = Math.round(rect.top - h - Ui.dp(a, 46));
    x = Math.max(edge, Math.min(x, Math.max(edge, stage.getWidth() - w - edge)));
    int maxY = Math.max(edge, stage.getHeight() - h - edge);
    if (y < edge) y = Math.min(maxY, Math.round(rect.bottom + Ui.dp(a, 12)));
    y = Math.max(edge, Math.min(y, maxY));
    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) selectionBubble.getLayoutParams();
    lp.leftMargin = x;
    lp.topMargin = y;
    selectionBubble.setLayoutParams(lp);
    selectionBubble.setAlpha(1f);
    selectionBubble.setVisibility(VISIBLE);
  }

  private void updateZoomChip(float zoom) {
    if (zoomChip != null) zoomChip.setVisibility(zoom > 1.02f ? VISIBLE : GONE);
  }

  // ---- saving ---------------------------------------------------------------------------------

  private void onInkChanged() {
    changed();
    updateHistory();
  }

  private void changed() {
    if (doc == null) return;
    pendingJson = doc.toString();
    changeSeq++;
    setStatus("保存中…");
    if (!draining) {
      draining = true;
      saver.execute(this::drainSaves);
    }
  }

  private void drainSaves() {
    while (true) {
      final String snapshot;
      synchronized (this) {
        snapshot = pendingJson;
        pendingJson = null;
        if (snapshot == null) {
          draining = false;
          return;
        }
      }
      final long seq = changeSeq;
      try {
        store.save(id, new JSONObject(snapshot));
        ui.post(() -> {
          savedSeq = Math.max(savedSeq, seq);
          saveFailed = false;
          if (savedSeq >= changeSeq) setStatus("已存本机");
        });
      } catch (final Exception e) {
        ui.post(() -> {
          saveFailed = true;
          setStatus("保存失败");
          showError("无法保存笔记", e);
        });
      }
    }
  }

  private void setStatus(String value) {
    if (statusView != null) statusView.setText(Str.of(value));
  }

  void requestExit() {
    study.stop();
    try {
      saver.submit(() -> {
        store.save(id, new JSONObject(doc.toString()));
        return null;
      }).get();
      host.exit();
    } catch (Exception e) {
      showError("无法保存，先不要离开", e);
    }
  }

  void onHostStop() {
    study.stop();
  }

  String noteId() { return id; }

  int pageIndex() { return index; }

  void dispose() {
    study.stop();
    worker.shutdownNow();
    saver.shutdownNow();
    if (background != null) {
      background.recycle();
      background = null;
    }
  }

  void onPermissionResult(boolean granted) {
    study.permissionResult(granted);
  }

  // ---- colour sheet ---------------------------------------------------------------------------

  private void showColorSheet() {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), Ui.dp(a, 22));
    final Dialog[] ref = new Dialog[1];
    LinearLayout colorHead = Ui.row(a);
    colorHead.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 6));
    colorHead.addView(Ui.sheetTitle(a, "调色盘"));
    colorHead.addView(Ui.icon(a, R.drawable.ic_close, "关闭", 40, Ui.MUTED, () -> ref[0].dismiss()));
    content.addView(colorHead);

    int wheelSize = Math.min(Math.min(Ui.screenWidth(a) - Ui.dp(a, 76), Ui.dp(a, 296)), (int) (Ui.screenHeight(a) * .40f));
    ColorWheelView wheel = new ColorWheelView(a);
    LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(wheelSize, wheelSize);
    wlp.gravity = Gravity.CENTER_HORIZONTAL;
    wlp.topMargin = Ui.dp(a, 8);
    content.addView(wheel, wlp);

    BrightnessSliderView brightness = new BrightnessSliderView(a);
    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(wheelSize, Ui.dp(a, 30));
    blp.gravity = Gravity.CENTER_HORIZONTAL;
    blp.topMargin = Ui.dp(a, 14);
    content.addView(brightness, blp);

    LinearLayout current = Ui.row(a);
    current.setGravity(Gravity.CENTER);
    Ui.ColorChip preview = new Ui.ColorChip(a);
    preview.setColor(color);
    LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(a, 30), Ui.dp(a, 30));
    current.addView(preview, plp);
    TextView previewLabel = Ui.text(a, "", 12, Ui.MUTED);
    previewLabel.setPadding(Ui.dp(a, 10), 0, 0, 0);
    current.addView(previewLabel);
    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    clp.topMargin = Ui.dp(a, 12);
    content.addView(current, clp);

    TextView slotsLabel = Ui.label(a, "色位 · 长按保存当前颜色", 10, Ui.MUTED);
    slotsLabel.setPadding(0, Ui.dp(a, 18), 0, Ui.dp(a, 8));
    content.addView(slotsLabel);
    int[] slots = prefs.slots();
    LinearLayout slotRow = Ui.row(a);
    slotRow.setGravity(Gravity.CENTER);
    for (int i = 0; i < slots.length; i++) {
      final int slotIndex = i;
      Ui.ColorChip chip = new Ui.ColorChip(a);
      chip.setColor(slots[i]);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 32), Ui.dp(a, 32));
      if (i > 0) lp.leftMargin = Ui.dp(a, 6);
      chip.setLayoutParams(lp);
      chip.setContentDescription(Str.slot(i + 1));
      chip.setOnClickListener(v -> applyColor(chip.color()));
      chip.setOnLongClickListener(v -> {
        prefs.setSlot(slotIndex, color);
        chip.setColor(color);
        toast(Str.savedToSlot(slotIndex + 1));
        return true;
      });
      slotRow.addView(chip);
    }
    content.addView(slotRow);

    int[] recents = prefs.recents();
    if (recents.length > 0) {
      TextView recentLabel = Ui.label(a, "最近使用", 10, Ui.MUTED);
      recentLabel.setPadding(0, Ui.dp(a, 16), 0, Ui.dp(a, 8));
      content.addView(recentLabel);
      LinearLayout recentRow = Ui.row(a);
      recentRow.setGravity(Gravity.CENTER);
      for (int i = 0; i < recents.length; i++) {
        Ui.ColorChip chip = new Ui.ColorChip(a);
        chip.setColor(recents[i]);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 30), Ui.dp(a, 30));
        if (i > 0) lp.leftMargin = Ui.dp(a, 8);
        chip.setLayoutParams(lp);
        chip.setContentDescription(Str.recentColor(i + 1));
        chip.setOnClickListener(v -> applyColor(chip.color()));
        recentRow.addView(chip);
      }
      content.addView(recentRow);
    }

    final int[] last = {color};
    wheel.setColor(color);
    float[] hsv = new float[3];
    Color.colorToHSV(color, hsv);
    brightness.setHueSaturation(hsv[0], hsv[1]);
    brightness.setBrightness(hsv[2]);
    updateColorPreview(preview, previewLabel, color);

    wheel.setListener(argb -> {
      Color.colorToHSV(argb, hsv);
      brightness.setHueSaturation(hsv[0], hsv[1]);
      last[0] = argb;
      applyColor(argb);
      updateColorPreview(preview, previewLabel, argb);
    });
    brightness.setListener(value -> {
      int argb = wheel.getColor();
      Color.colorToHSV(argb, hsv);
      int next = Color.HSVToColor(new float[] {hsv[0], hsv[1], value});
      wheel.setColor(next);
      last[0] = next;
      applyColor(next);
      updateColorPreview(preview, previewLabel, next);
    });

    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.setOnDismissListener(x -> {
      prefs.pushRecent(last[0]);
      prefs.setColor(color);
    });
    d.show();
  }

  private void updateColorPreview(Ui.ColorChip chip, TextView label, int argb) {
    chip.setColor(argb);
    label.setText(String.format(Locale.ROOT, "#%06X", 0xffffff & argb));
  }

  private void applyColor(int argb) {
    color = argb;
    prefs.setColor(argb);
    if (ink != null) ink.color(argb);
    if (colorButton != null) colorButton.setColor(argb);
  }

  private void toast(String message) {
    android.widget.Toast.makeText(a, Str.of(message), android.widget.Toast.LENGTH_SHORT).show();
  }

  // ---- pen sheet ------------------------------------------------------------------------------

  private void showPenSheet() {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), Ui.dp(a, 24));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, "画笔", () -> ref[0].dismiss()));

    LinearLayout chooser = Ui.row(a);
    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    clp.topMargin = Ui.dp(a, 6);
    content.addView(chooser, clp);
    final String[] kind = {"brush".equals(tool) ? "brush" : "pen"};
    final LinearLayout[] tiles = new LinearLayout[2];
    final String[] keys = {"pen", "brush"};
    final String[] names = {"硬笔", "软笔"};
    final String[] notes = {"均匀压感", "毛笔粗细"};
    for (int i = 0; i < 2; i++) {
      final String key = keys[i];
      StrokePreviewView sample = new StrokePreviewView(a);
      sample.setStroke(color, prefs.width(key), key);
      LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 54));
      sample.setLayoutParams(slp);
      LinearLayout tile = Ui.choiceTile(a, sample, names[i], notes[i]);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
      if (i > 0) lp.leftMargin = Ui.dp(a, 10);
      tile.setLayoutParams(lp);
      tile.setOnClickListener(v -> {
        kind[0] = key;
        for (int j = 0; j < keys.length; j++) Ui.setChosen(tiles[j], keys[j].equals(kind[0]));
        tool = key;
        prefs.setTool(key);
        prefs.setSoftPen("brush".equals(key));
        ink.tool(key);
        ink.width(widthForTool());
        applyToolStyles();
      });
      tiles[i] = tile;
      chooser.addView(tile);
    }
    for (int i = 0; i < keys.length; i++) Ui.setChosen(tiles[i], keys[i].equals(kind[0]));

    StrokePreviewView preview = new StrokePreviewView(a);
    LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 92));
    plp.topMargin = Ui.dp(a, 12);
    content.addView(preview, plp);

    TextView value = Ui.text(a, "", 12, Ui.MUTED);
    value.setGravity(Gravity.CENTER);
    value.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 2));
    content.addView(value);

    float min = .8f, max = 28f;
    final float[] presets = {1.5f, 3, 7, 16};
    SeekBar bar = new SeekBar(a);
    bar.setMax(1000);
    bar.setProgressDrawable(Ui.sliderTrack(a, Ui.LINE, Ui.INK));
    bar.setSplitTrack(false);
    bar.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    blp.topMargin = Ui.dp(a, 6);
    content.addView(bar, blp);

    LinearLayout sizes = Ui.row(a);
    sizes.setGravity(Gravity.CENTER);
    sizes.setPadding(0, Ui.dp(a, 14), 0, 0);
    SizeDot[] dots = new SizeDot[presets.length];
    for (int i = 0; i < presets.length; i++) {
      final float target = presets[i];
      SizeDot dot = new SizeDot(a);
      LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 56));
      if (i > 0) dlp.leftMargin = Ui.dp(a, 6);
      dot.setLayoutParams(dlp);
      dot.setColor(Ui.INK);
      dot.setValue(target, presets[presets.length - 1]);
      dot.setContentDescription(Str.size(trim(target)));
      dot.setOnClickListener(v -> {
        bar.setProgress(toProgress(target, min, max));
        applyPenWidth(kind[0], target, preview, value, dots);
      });
      dots[i] = dot;
      sizes.addView(dot);
    }
    content.addView(sizes);

    float initial = prefs.width(kind[0]);
    initial = Math.max(min, Math.min(max, initial));
    bar.setProgress(toProgress(initial, min, max));
    preview.setStroke(color, initial, kind[0]);
    value.setText(Str.penLabel(trim(initial), "brush".equals(kind[0])));
    for (int i = 0; i < presets.length; i++) dots[i].setChosen(Math.abs(presets[i] - initial) < .51f);

    bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        float w = fromProgress(progress, min, max);
        applyPenWidth(kind[0], w, preview, value, dots);
      }
      @Override public void onStartTrackingTouch(SeekBar seekBar) { }
      @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    });

    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  private void applyPenWidth(String key, float width, StrokePreviewView preview, TextView value, SizeDot[] dots) {
    prefs.setWidth(key, width);
    if (key.equals(tool) && ink != null) ink.width(width);
    preview.setStroke(color, width, key);
    value.setText(Str.penLabel(trim(width), "brush".equals(key)));
    float[] presets = {1.5f, 3, 7, 16};
    for (int i = 0; i < dots.length; i++) dots[i].setChosen(Math.abs(presets[i] - width) < .51f);
  }

  // ---- eraser sheet ---------------------------------------------------------------------------

  private void showEraserSheet() {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), Ui.dp(a, 24));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, "橡皮擦", () -> ref[0].dismiss()));

    LinearLayout chooser = Ui.row(a);
    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    clp.topMargin = Ui.dp(a, 6);
    content.addView(chooser, clp);
    LinearLayout[] tiles = new LinearLayout[2];
    final int[] modes = {0, 1};
    String[] names = {"按笔迹擦除", "范围擦除"};
    int[] icons = {R.drawable.ic_eraser, R.drawable.ic_eraser_area};
    final int[] chosen = {prefs.eraseMode()};
    final TextView[] modeLabel = new TextView[1];
    for (int i = 0; i < 2; i++) {
      ImageView glyph = new ImageView(a);
      glyph.setImageResource(icons[i]);
      glyph.setColorFilter(Ui.INK);
      glyph.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 54)));
      glyph.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
      LinearLayout tile = Ui.choiceTile(a, glyph, names[i], i == 0 ? "整条删除" : "只擦碰到的地方");
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
      if (i > 0) lp.leftMargin = Ui.dp(a, 10);
      tile.setLayoutParams(lp);
      final int mode = modes[i];
      tile.setOnClickListener(v -> {
        chosen[0] = mode;
        prefs.setEraseMode(mode);
        ink.areaErase(mode == 1);
        for (int j = 0; j < 2; j++) Ui.setChosen(tiles[j], modes[j] == chosen[0]);
        if (modeLabel[0] != null) modeLabel[0].setText(Str.radius(trim(prefs.width("erase")), chosen[0]));
      });
      tiles[i] = tile;
      chooser.addView(tile);
    }
    for (int i = 0; i < 2; i++) Ui.setChosen(tiles[i], modes[i] == chosen[0]);

    StrokePreviewView preview = new StrokePreviewView(a);
    LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 92));
    plp.topMargin = Ui.dp(a, 12);
    content.addView(preview, plp);

    TextView value = Ui.text(a, "", 12, Ui.MUTED);
    value.setGravity(Gravity.CENTER);
    value.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 2));
    modeLabel[0] = value;
    content.addView(value);

    float min = 8, max = 140;
    float[] presets = {16, 36, 64, 100};
    SeekBar bar = new SeekBar(a);
    bar.setMax(1000);
    bar.setProgressDrawable(Ui.sliderTrack(a, Ui.LINE, Ui.INK));
    bar.setSplitTrack(false);
    bar.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    blp.topMargin = Ui.dp(a, 6);
    content.addView(bar, blp);

    LinearLayout sizes = Ui.row(a);
    sizes.setGravity(Gravity.CENTER);
    sizes.setPadding(0, Ui.dp(a, 14), 0, 0);
    SizeDot[] dots = new SizeDot[presets.length];
    for (int i = 0; i < presets.length; i++) {
      final float target = presets[i];
      SizeDot dot = new SizeDot(a);
      LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 56));
      if (i > 0) dlp.leftMargin = Ui.dp(a, 6);
      dot.setLayoutParams(dlp);
      dot.setColor(Ui.MUTED);
      dot.setValue(target, presets[presets.length - 1]);
      dot.setContentDescription(Str.eraserSize(trim(target)));
      dot.setOnClickListener(v -> {
        bar.setProgress(toProgress(target, min, max));
        applyEraseWidth(target, preview, value, dots, presets);
      });
      dots[i] = dot;
      sizes.addView(dot);
    }
    content.addView(sizes);

    float initial = Math.max(min, Math.min(max, prefs.width("erase")));
    bar.setProgress(toProgress(initial, min, max));
    preview.setStroke(0xff17322c, initial, "erase");
    value.setText(Str.radius(trim(initial), chosen[0]));
    for (int i = 0; i < presets.length; i++) dots[i].setChosen(Math.abs(presets[i] - initial) < .51f);
    bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        applyEraseWidth(fromProgress(progress, min, max), preview, value, dots, presets);
      }
      @Override public void onStartTrackingTouch(SeekBar seekBar) { }
      @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    });

    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  private void applyEraseWidth(float width, StrokePreviewView preview, TextView value, SizeDot[] dots, float[] presets) {
    prefs.setWidth("erase", width);
    if (ink != null) ink.width(width);
    preview.setStroke(0xff17322c, width, "erase");
    value.setText(Str.radius(trim(width), prefs.eraseMode()));
    for (int i = 0; i < dots.length; i++) dots[i].setChosen(Math.abs(presets[i] - width) < .51f);
  }

  // ---- shape sheet ----------------------------------------------------------------------------

  private void showShapeSheet() {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 18), 0, Ui.dp(a, 18), Ui.dp(a, 24));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, "图形", () -> ref[0].dismiss()));

    final String[] kinds = InkStroke.SHAPES;
    final String[] names = {"直线", "箭头", "矩形", "圆", "椭圆", "三角形", "菱形", "五边形", "五角星", "六边形"};
    final int[] icons = {R.drawable.ic_line, R.drawable.ic_arrow, R.drawable.ic_rect,
        R.drawable.ic_circle, R.drawable.ic_ellipse, R.drawable.ic_triangle,
        R.drawable.ic_diamond, R.drawable.ic_pentagon, R.drawable.ic_star, R.drawable.ic_hexagon};
    LinearLayout rows = Ui.column(a);
    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    rlp.topMargin = Ui.dp(a, 8);
    content.addView(rows, rlp);
    final String current = prefs.shape();
    for (int row = 0; row < 2; row++) {
      LinearLayout line = Ui.row(a);
      LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      if (row > 0) llp.topMargin = Ui.dp(a, 6);
      for (int col = 0; col < 5; col++) {
        final int i = row * 5 + col;
        final String kind = kinds[i];
        View tile = Ui.iconTile(a, icons[i], names[i], false, () -> {
          prefs.setShape(kind);
          selectTool("shape");
          ink.shapeKind(kind);
          ref[0].dismiss();
        });
        tile.setSelected(kind.equals(current));
        if (kind.equals(current)) {
          tile.setBackground(Ui.ripple(Ui.ACCENT_SOFT, 18, a));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (col > 0) lp.leftMargin = Ui.dp(a, 2);
        line.addView(tile, lp);
      }
      rows.addView(line, llp);
    }
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  private void showMagicSheet() {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 22), 0, Ui.dp(a, 22), Ui.dp(a, 24));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, "魔法笔", () -> ref[0].dismiss()));
    TextView hint = Ui.text(a,
        "随手画个大概：直的变直线，圆的变圆，方框变矩形，三角形也会自动规整。认不出来时保留你的原笔迹。",
        13.5f, Ui.MUTED);
    hint.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 14));
    hint.setLineSpacing(Ui.dp(a, 3), 1f);
    content.addView(hint);
    LinearLayout samples = Ui.row(a);
    int[] icons = {R.drawable.ic_line, R.drawable.ic_circle, R.drawable.ic_rect, R.drawable.ic_magic};
    String[] names = {"直线", "圆", "矩形", "自由"};
    for (int i = 0; i < icons.length; i++) {
      LinearLayout cell = Ui.column(a);
      cell.setGravity(Gravity.CENTER);
      ImageView glyph = new ImageView(a);
      glyph.setImageResource(icons[i]);
      glyph.setColorFilter(Ui.INK);
      glyph.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44)));
      glyph.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8));
      glyph.setBackground(Ui.round(a, Ui.SURFACE_SOFT, 14));
      cell.addView(glyph);
      TextView name = Ui.text(a, names[i], 10.5f, Ui.MUTED);
      name.setPadding(0, Ui.dp(a, 6), 0, 0);
      cell.addView(name);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
      samples.addView(cell, lp);
    }
    content.addView(samples);
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  // ---- width sheet ----------------------------------------------------------------------------

  private void showWidthSheet() {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), Ui.dp(a, 22));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, "笔尖粗细", () -> ref[0].dismiss()));

    StrokePreviewView preview = new StrokePreviewView(a);
    LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 96));
    plp.topMargin = Ui.dp(a, 8);
    content.addView(preview, plp);

    TextView value = Ui.text(a, "", 12, Ui.MUTED);
    value.setGravity(Gravity.CENTER);
    value.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 2));
    content.addView(value);

    float min = "marker".equals(tool) ? 4 : .8f;
    float max = "marker".equals(tool) ? 48 : 28;
    float[] presets = "marker".equals(tool) ? new float[] {6, 12, 24, 40} : new float[] {1.5f, 3, 7, 16};
    SeekBar bar = new SeekBar(a);
    bar.setMax(1000);
    bar.setProgressDrawable(Ui.sliderTrack(a, Ui.LINE, Ui.INK));
    bar.setSplitTrack(false);
    bar.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    blp.topMargin = Ui.dp(a, 6);
    content.addView(bar, blp);

    LinearLayout sizes = Ui.row(a);
    sizes.setGravity(Gravity.CENTER);
    sizes.setPadding(0, Ui.dp(a, 16), 0, 0);
    SizeDot[] dots = new SizeDot[presets.length];
    for (int i = 0; i < presets.length; i++) {
      final float target = presets[i];
      SizeDot dot = new SizeDot(a);
      LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 56));
      if (i > 0) dlp.leftMargin = Ui.dp(a, 6);
      dot.setLayoutParams(dlp);
      dot.setColor(Ui.INK);
      dot.setValue(target, presets[presets.length - 1]);
      dot.setContentDescription(Str.size(trim(target)));
      dot.setOnClickListener(v -> {
        bar.setProgress(toProgress(target, min, max));
        applyWidth(target, preview, value, presets);
      });
      dots[i] = dot;
      sizes.addView(dot);
    }
    content.addView(sizes);

    float initial = Math.max(min, Math.min(max, widthForTool()));
    bar.setProgress(toProgress(initial, min, max));
    preview.setStroke(color, initial, tool);
    value.setText(trim(initial) + "  ·  " + Str.of(toolName()));
    for (int i = 0; i < presets.length; i++) dots[i].setChosen(Math.abs(presets[i] - initial) < .51f);

    bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        applyWidth(fromProgress(progress, min, max), preview, value, presets);
      }
      @Override public void onStartTrackingTouch(SeekBar seekBar) { }
      @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    });

    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  private String toolName() {
    switch (tool) {
      case "brush": return "软笔";
      case "marker": return "荧光笔";
      case "erase": return "橡皮擦";
      case "shape": return "图形";
      case "magic": return "魔法笔";
      default: return "硬笔";
    }
  }

  private int toProgress(float value, float min, float max) {
    return Math.round((value - min) / (max - min) * 1000);
  }

  private float fromProgress(int progress, float min, float max) {
    return min + (max - min) * progress / 1000f;
  }

  private void applyWidth(float width, StrokePreviewView preview, TextView value, float[] presets) {
    prefs.setWidth(tool, width);
    if (ink != null) ink.width(width);
    preview.setStroke(color, width, tool);
    value.setText(trim(width) + "  ·  " + Str.of(toolName()));
  }

  private static String trim(float value) {
    return value >= 10 ? String.valueOf(Math.round(value)) : String.format(Locale.ROOT, "%.1f", value);
  }

  // ---- page sheet -----------------------------------------------------------------------------

  private void showPagesSheet() {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), Ui.dp(a, 20));
    Dialog[] ref = new Dialog[1];
    LinearLayout head = Ui.row(a);
    head.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 6));
    head.addView(Ui.sheetTitle(a, "页面"));
    head.addView(Ui.icon(a, R.drawable.ic_plus, "添加页面", 40, Ui.INK, () -> {
      ref[0].dismiss();
      showTemplateSheet();
    }));
    head.addView(Ui.icon(a, R.drawable.ic_close, "关闭", 40, Ui.MUTED, () -> ref[0].dismiss()));
    content.addView(head);

    GridView grid = Ui.grid(a, 104, 3, 6);
    grid.setNumColumns(3);
    grid.setHorizontalSpacing(Ui.dp(a, 10));
    grid.setVerticalSpacing(Ui.dp(a, 12));
    grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
    grid.setVerticalScrollBarEnabled(false);
    int rows = Math.max(1, (pageCount() + 2) / 3);
    int cellHeight = Ui.dp(a, 150);
    int desired = rows * cellHeight + Ui.dp(a, 12) * Math.max(0, rows - 1);
    grid.setLayoutParams(new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        Math.min(desired, (int) (Ui.screenHeight(a) * .55f))));
    PagesAdapter adapter = new PagesAdapter();
    grid.setAdapter(adapter);
    grid.setOnItemClickListener((parent, view, position, pid) -> {
      ref[0].dismiss();
      go(position);
    });
    grid.setOnItemLongClickListener((parent, view, position, pid) -> {
      ref[0].dismiss();
      showPageActions(position);
      return true;
    });
    content.addView(grid);
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  private void showTemplateSheet() {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), Ui.dp(a, 22));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, "添加页面", () -> ref[0].dismiss()));
    String[] papers = {"ruled", "grid", "plain"};
    String[] names = {"横线", "方格", "空白"};
    LinearLayout row = Ui.row(a);
    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    rlp.topMargin = Ui.dp(a, 10);
    content.addView(row, rlp);
    for (int i = 0; i < papers.length; i++) {
      final String paper = papers[i];
      LinearLayout cell = Ui.column(a);
      cell.setGravity(Gravity.CENTER);
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
      if (i > 0) clp.leftMargin = Ui.dp(a, 10);
      cell.setLayoutParams(clp);
      LibraryScreen.TemplatePreview preview = new LibraryScreen.TemplatePreview(a, paper);
      preview.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 96)));
      preview.setOnClickListener(v -> {
        ref[0].dismiss();
        addPage(paper);
      });
      cell.addView(preview);
      TextView name = Ui.text(a, names[i], 11.5f, Ui.MUTED);
      name.setGravity(Gravity.CENTER);
      name.setPadding(0, Ui.dp(a, 6), 0, 0);
      cell.addView(name);
      row.addView(cell);
    }
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  private void addPage(String paper) {
    try {
      JSONArray pages = doc.getJSONArray("pages");
      if (pages.length() >= 256) { toast("每本最多 256 页"); return; }
      JSONObject p = Store.page(612, 792, paper, -1);
      JSONArray next = new JSONArray();
      for (int i = 0; i < pages.length(); i++) {
        next.put(pages.get(i));
        if (i == index) next.put(p);
      }
      doc.put("pages", next);
      index++;
      changed();
      loadPage(true);
    } catch (Exception e) {
      showError("无法添加页面", e);
    }
  }

  private void showPageActions(int target) {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 14), 0, Ui.dp(a, 14), Ui.dp(a, 18));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, Str.pageOf(target + 1), null));
    LinearLayout rows = Ui.column(a);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.topMargin = Ui.dp(a, 6);
    rows.addView(Ui.iconRow(a, R.drawable.ic_pages, "复制此页", false, () -> {
      ref[0].dismiss();
      duplicatePage(target);
    }), new LinearLayout.LayoutParams(lp));
    rows.addView(Ui.iconRow(a, R.drawable.ic_back, "前移一页", false, () -> {
      ref[0].dismiss();
      movePage(target, target - 1);
    }), new LinearLayout.LayoutParams(lp));
    rows.addView(Ui.iconRow(a, R.drawable.ic_back, "后移一页", false, () -> {
      ref[0].dismiss();
      movePage(target, target + 1);
    }), new LinearLayout.LayoutParams(lp));
    rows.addView(Ui.iconRow(a, R.drawable.ic_trash, "删除此页", true, () -> {
      ref[0].dismiss();
      deletePage(target);
    }), new LinearLayout.LayoutParams(lp));
    content.addView(rows);
    Dialog d = Ui.sheet(a, content, false);
    ref[0] = d;
    d.show();
  }

  private void duplicatePage(int target) {
    try {
      JSONArray pages = doc.getJSONArray("pages");
      if (pages.length() >= 256) { toast("每本最多 256 页"); return; }
      JSONArray next = new JSONArray();
      for (int i = 0; i < pages.length(); i++) {
        next.put(pages.get(i));
        if (i == target) next.put(new JSONObject(pages.getJSONObject(i).toString()));
      }
      doc.put("pages", next);
      if (target <= index) index++;
      changed();
      loadPage(true);
    } catch (Exception e) {
      showError("无法复制页面", e);
    }
  }

  private void movePage(int from, int to) {
    if (to < 0 || to >= pageCount()) return;
    try {
      JSONArray pages = doc.getJSONArray("pages");
      List<JSONObject> list = new ArrayList<>();
      for (int i = 0; i < pages.length(); i++) list.add(pages.getJSONObject(i));
      JSONObject moved = list.remove(from);
      list.add(to, moved);
      JSONArray next = new JSONArray();
      for (JSONObject p : list) next.put(p);
      doc.put("pages", next);
      if (index == from) index = to;
      else if (from < index && to >= index) index--;
      else if (from > index && to <= index) index++;
      changed();
      loadPage(true);
    } catch (Exception e) {
      showError("无法移动页面", e);
    }
  }

  private void deletePage(int target) {
    if (pageCount() <= 1) { toast("至少保留一页"); return; }
    try {
      JSONArray pages = doc.getJSONArray("pages");
      JSONArray next = new JSONArray();
      for (int i = 0; i < pages.length(); i++) if (i != target) next.put(pages.get(i));
      doc.put("pages", next);
      index = Math.min(index, next.length() - 1);
      changed();
      loadPage(true);
    } catch (Exception e) {
      showError("无法删除页面", e);
    }
  }

  private final class PagesAdapter extends BaseAdapter {
    @Override public int getCount() { return pageCount(); }
    @Override public Object getItem(int position) { return position; }
    @Override public long getItemId(int position) { return position; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
      LinearLayout cell = (LinearLayout) convertView;
      if (cell == null) {
        cell = Ui.column(a);
        FrameLayout cover = new AspectFrame(a, .76f);
        cover.setClipToOutline(true);
        cover.setOutlineProvider(new ViewOutlineProviderCompat(a, 12));
        cover.setBackgroundColor(Ui.SURFACE);
        cover.setForeground(Ui.roundStroke(a, Color.TRANSPARENT, Ui.LINE, 12, .8f));
        ImageView image = new ImageView(a);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cover.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView placeholder = new ImageView(a);
        placeholder.setImageResource(R.drawable.ic_notebook);
        placeholder.setColorFilter(Ui.SHADOW);
        FrameLayout.LayoutParams php = new FrameLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22));
        php.gravity = Gravity.CENTER;
        cover.addView(placeholder, php);
        TextView number = Ui.text(a, "", 10.5f, Ui.ON_INK);
        number.setBackground(Ui.round(a, 0xcc17322c, 8));
        number.setPadding(Ui.dp(a, 6), Ui.dp(a, 2), Ui.dp(a, 6), Ui.dp(a, 2));
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.gravity = Gravity.START | Gravity.BOTTOM;
        nlp.setMargins(Ui.dp(a, 6), 0, 0, Ui.dp(a, 6));
        cover.addView(number, nlp);
        View bookmark = new View(a);
        bookmark.setBackground(Ui.circle(Ui.ACCENT));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(Ui.dp(a, 7), Ui.dp(a, 7));
        blp.gravity = Gravity.END | Gravity.TOP;
        blp.setMargins(0, Ui.dp(a, 7), Ui.dp(a, 7), 0);
        cover.addView(bookmark, blp);
        cell.addView(cover, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.setTag(new PageHolder(image, placeholder, number, bookmark, cover));
      }
      PageHolder h = (PageHolder) cell.getTag();
      JSONObject p = doc.optJSONArray("pages").optJSONObject(position);
      h.number.setText(String.valueOf(position + 1));
      h.bookmark.setVisibility(p != null && p.optBoolean("bookmark") ? VISIBLE : GONE);
      boolean current = position == index;
      h.cover.setForeground(Ui.roundStroke(a, Color.TRANSPARENT, current ? Ui.ACCENT : Ui.LINE, 12, current ? 2 : .8f));
      h.image.setTag(position);
      h.image.setImageDrawable(null);
      h.placeholder.setVisibility(VISIBLE);
      final int pageIndex = position;
      final JSONObject snapshot = p;
      worker.execute(() -> {
        try {
          Bitmap bitmap = Thumbnails.render(a, store, id, snapshot, Ui.dp(a, 220));
          ui.post(() -> {
            if (Integer.valueOf(pageIndex).equals(h.image.getTag())) {
              h.image.setImageBitmap(bitmap);
              h.placeholder.setVisibility(GONE);
            }
          });
        } catch (Exception ignored) { }
      });
      cell.setContentDescription(Str.pageOf(position + 1));
      return cell;
    }
  }

  private static final class PageHolder {
    final ImageView image, placeholder;
    final TextView number;
    final View bookmark;
    final FrameLayout cover;
    PageHolder(ImageView i, ImageView p, TextView n, View b, FrameLayout c) {
      image = i; placeholder = p; number = n; bookmark = b; cover = c;
    }
  }

  private static final class AspectFrame extends FrameLayout {
    private final float ratio;
    AspectFrame(android.content.Context c, float ratio) {
      super(c);
      this.ratio = ratio;
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
      int width = MeasureSpec.getSize(widthSpec);
      int height = Math.round(width / ratio);
      super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
  }

  private static final class ViewOutlineProviderCompat extends android.view.ViewOutlineProvider {
    private final float radius;
    ViewOutlineProviderCompat(android.content.Context c, float radiusDp) {
      radius = Ui.dp(c, radiusDp);
    }
    @Override public void getOutline(View v, android.graphics.Outline outline) {
      outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
    }
  }

  // ---- more sheet -----------------------------------------------------------------------------

  private void showMoreSheet() {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), Ui.dp(a, 22));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, "更多", () -> ref[0].dismiss()));
    LinearLayout row1 = Ui.row(a);
    LinearLayout row2 = Ui.row(a);
    LinearLayout row3 = Ui.row(a);
    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    rlp.topMargin = Ui.dp(a, 10);
    content.addView(row1, rlp);
    content.addView(row2, new LinearLayout.LayoutParams(rlp));
    content.addView(row3, new LinearLayout.LayoutParams(rlp));
    String[] labels = {"页内文字", "识别文字", "闪卡", "录音",
        "导出 PDF", "导出图片", "备份", "重命名",
        "删除", "全选笔迹", "页面排序", "书签页"};
    int[] icons = {R.drawable.ic_text, R.drawable.ic_scan, R.drawable.ic_cards, R.drawable.ic_mic,
        R.drawable.ic_export, R.drawable.ic_image, R.drawable.ic_backup, R.drawable.ic_rename,
        R.drawable.ic_trash, R.drawable.ic_lasso, R.drawable.ic_pages, R.drawable.ic_bookmark_on};
    Runnable[] actions = {
        () -> { ref[0].dismiss(); showTextSheet(); },
        () -> { ref[0].dismiss(); showOcrSheet(); },
        () -> { ref[0].dismiss(); study.cards(doc, this::saveStudyNow); },
        () -> { ref[0].dismiss(); try { study.audio(doc, store.folder(id), this::saveStudyNow); } catch (Exception e) { showError("无法打开录音", e); } },
        () -> { ref[0].dismiss(); host.exportPdf(id, snapshot()); },
        () -> { ref[0].dismiss(); host.exportImage(id, snapshot(), index); },
        () -> { ref[0].dismiss(); host.backup(id, snapshot()); },
        () -> { ref[0].dismiss(); showRenameSheet(); },
        () -> { ref[0].dismiss(); confirmDeleteNote(); },
        () -> { ref[0].dismiss(); selectTool("lasso"); ink.selectAll(); changed(); updateHistory(); },
        () -> { ref[0].dismiss(); showPagesSheet(); },
        () -> { ref[0].dismiss(); go(nextBookmark()); }
    };
    for (int i = 0; i < labels.length; i++) {
      boolean danger = labels[i].equals("删除");
      View tile = Ui.iconTile(a, icons[i], labels[i], danger, actions[i]);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
      if (i % 4 != 0) lp.leftMargin = Ui.dp(a, 6);
      (i < 4 ? row1 : i < 8 ? row2 : row3).addView(tile, lp);
    }
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  private int nextBookmark() {
    int count = pageCount();
    for (int step = 1; step < count; step++) {
      int candidate = (index + step) % count;
      JSONObject p = doc.optJSONArray("pages").optJSONObject(candidate);
      if (p != null && p.optBoolean("bookmark")) return candidate;
    }
    toast("还没有书签页");
    return index;
  }

  private JSONObject snapshot() {
    try {
      return new JSONObject(doc.toString());
    } catch (JSONException e) {
      throw new IllegalStateException("无法读取笔记", e);
    }
  }

  private void saveStudyNow() {
    try {
      saver.submit(() -> { store.save(id, new JSONObject(doc.toString())); return null; }).get();
      ui.post(() -> setStatus("已存本机"));
    } catch (Exception e) {
      throw new IllegalStateException("无法保存笔记", e);
    }
  }

  private void showTextSheet() {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), Ui.dp(a, 22));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, "页内文字", () -> ref[0].dismiss()));
    EditText text = new EditText(a);
    text.setText(page().optString("text", ""));
    text.setHint(Str.of("输入后随笔记保存，并可被书架搜索"));
    text.setHintTextColor(Ui.HINT);
    text.setTextColor(Ui.INK);
    text.setTextSize(15);
    text.setGravity(Gravity.TOP);
    text.setMinLines(4);
    text.setMaxLines(12);
    text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    text.setFilters(new InputFilter[] {new InputFilter.LengthFilter(32768)});
    text.setBackground(Ui.round(a, Ui.SURFACE_SOFT, 14));
    text.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    tlp.topMargin = Ui.dp(a, 10);
    content.addView(text, tlp);
    LinearLayout footer = Ui.row(a);
    footer.setGravity(Gravity.END);
    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    flp.topMargin = Ui.dp(a, 18);
    footer.addView(Ui.pillButton(a, R.drawable.ic_check, "保存", Ui.INK, Ui.ON_INK, () -> {
      try {
        page().put("text", text.getText().toString());
        changed();
        ref[0].dismiss();
        if (ink != null) ink.invalidate();
      } catch (JSONException ignored) { }
    }));
    content.addView(footer, flp);
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  private void showOcrSheet() {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), Ui.dp(a, 22));
    Dialog[] ref = new Dialog[1];
    LinearLayout head = Ui.row(a);
    head.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 6));
    head.addView(Ui.sheetTitle(a, "识别印刷文字"));
    ImageButton scan = Ui.icon(a, R.drawable.ic_scan, "识别本页", 40, Ui.INK, null);
    head.addView(scan);
    head.addView(Ui.icon(a, R.drawable.ic_close, "关闭", 40, Ui.MUTED, () -> ref[0].dismiss()));
    content.addView(head);
    TextView hint = Ui.text(a, "在本机识别横排印刷的简中与英文；连笔手写不保证识别。结果只保存在本机。", 12, Ui.MUTED);
    hint.setPadding(0, Ui.dp(a, 2), 0, Ui.dp(a, 10));
    content.addView(hint);
    EditText text = new EditText(a);
    text.setText(page().optString("ocr", ""));
    text.setHint(Str.of("识别结果会显示在这里"));
    text.setHintTextColor(Ui.HINT);
    text.setTextColor(Ui.INK);
    text.setTextSize(14);
    text.setGravity(Gravity.TOP);
    text.setMinLines(5);
    text.setMaxLines(12);
    text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    text.setFilters(new InputFilter[] {new InputFilter.LengthFilter(32768)});
    text.setBackground(Ui.round(a, Ui.SURFACE_SOFT, 14));
    text.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
    content.addView(text);
    ProgressBar progress = new ProgressBar(a);
    progress.setVisibility(GONE);
    LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(a, 30), Ui.dp(a, 30));
    plp.gravity = Gravity.CENTER_HORIZONTAL;
    plp.topMargin = Ui.dp(a, 8);
    content.addView(progress, plp);
    LinearLayout footer = Ui.row(a);
    footer.setGravity(Gravity.END);
    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    flp.topMargin = Ui.dp(a, 14);
    footer.addView(Ui.pillButton(a, 0, "取消", Ui.SURFACE_SOFT, Ui.INK, () -> ref[0].dismiss()));
    View save = Ui.pillButton(a, R.drawable.ic_check, "保存", Ui.INK, Ui.ON_INK, () -> {
      try {
        page().put("ocr", text.getText().toString());
        changed();
        ref[0].dismiss();
      } catch (JSONException ignored) { }
    });
    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    slp.leftMargin = Ui.dp(a, 10);
    footer.addView(save, slp);
    content.addView(footer, flp);

    scan.setOnClickListener(v -> {
      scan.setEnabled(false);
      progress.setVisibility(VISIBLE);
      scan.setAlpha(.4f);
      final JSONObject target = page();
      final String snapshot = target.toString();
      worker.execute(() -> {
        try {
          int w = target.getInt("w"), h = target.getInt("h");
          float scale = 1800f / Math.max(w, h);
          Bitmap image = Bitmap.createBitmap(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), Bitmap.Config.ARGB_8888);
          String recognized;
          try {
            Canvas canvas = new Canvas(image);
            canvas.scale((float) image.getWidth() / w, (float) image.getHeight() / h);
            InkView.drawPage(canvas, new JSONObject(snapshot), background);
            recognized = OfflineOcr.recognize(a.getApplicationContext(), image);
          } finally {
            image.recycle();
          }
          final String result = recognized;
          ui.post(() -> {
            progress.setVisibility(GONE);
            scan.setEnabled(true);
            scan.setAlpha(1f);
            text.setText(result == null ? "" : result.trim());
            if (result == null || result.trim().isEmpty()) toast("未识别到印刷文字");
          });
        } catch (final Exception e) {
          ui.post(() -> {
            progress.setVisibility(GONE);
            scan.setEnabled(true);
            scan.setAlpha(1f);
            showError("识别未完成", e);
          });
        }
      });
    });
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  private void showRenameSheet() {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), Ui.dp(a, 22));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, "重命名", () -> ref[0].dismiss()));
    EditText name = new EditText(a);
    name.setText(doc.optString("title"));
    name.setSingleLine(true);
    name.setTextSize(16);
    name.setTextColor(Ui.INK);
    name.setHintTextColor(Ui.HINT);
    name.setBackground(Ui.round(a, Ui.SURFACE_SOFT, 14));
    name.setPadding(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16), Ui.dp(a, 12));
    LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    nlp.topMargin = Ui.dp(a, 12);
    content.addView(name, nlp);
    LinearLayout footer = Ui.row(a);
    footer.setGravity(Gravity.END);
    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    flp.topMargin = Ui.dp(a, 18);
    footer.addView(Ui.pillButton(a, R.drawable.ic_check, "保存", Ui.INK, Ui.ON_INK, () -> {
      String value = name.getText().toString().trim();
      if (value.isEmpty()) { name.setError("名称不能为空"); return; }
      try {
        doc.put("title", value);
        titleView.setText(value);
        changed();
        ref[0].dismiss();
      } catch (JSONException ignored) { }
    }));
    content.addView(footer, flp);
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  private void confirmDeleteNote() {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 22), 0, Ui.dp(a, 22), Ui.dp(a, 22));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, "删除这本笔记", () -> ref[0].dismiss()));
    TextView message = Ui.text(a, "笔迹、文字和录音会从本机删除，无法撤销。建议先导出 .phnote 备份。", 14, Ui.MUTED);
    message.setPadding(0, Ui.dp(a, 10), 0, 0);
    content.addView(message);
    LinearLayout footer = Ui.row(a);
    footer.setGravity(Gravity.END);
    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    flp.topMargin = Ui.dp(a, 20);
    footer.addView(Ui.pillButton(a, 0, "取消", Ui.SURFACE_SOFT, Ui.INK, () -> ref[0].dismiss()));
    View delete = Ui.pillButton(a, R.drawable.ic_trash, "删除", Ui.DANGER, Ui.WHITE, () -> {
      ref[0].dismiss();
      worker.execute(() -> {
        try {
          store.delete(id);
          ui.post(() -> host.deleted());
        } catch (Exception e) {
          ui.post(() -> showError("删除失败", e));
        }
      });
    });
    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    dlp.leftMargin = Ui.dp(a, 10);
    footer.addView(delete, dlp);
    content.addView(footer, flp);
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  private void showError(String title, Exception e) {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 22), 0, Ui.dp(a, 22), Ui.dp(a, 22));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, title, () -> ref[0].dismiss()));
    TextView message = Ui.text(a, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 14, Ui.MUTED);
    message.setPadding(0, Ui.dp(a, 10), 0, 0);
    content.addView(message);
    LinearLayout footer = Ui.row(a);
    footer.setGravity(Gravity.END);
    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    flp.topMargin = Ui.dp(a, 18);
    footer.addView(Ui.pillButton(a, 0, "知道了", Ui.INK, Ui.ON_INK, () -> ref[0].dismiss()));
    content.addView(footer, flp);
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  /** Circular current-colour control in the tool rail. */
  private static final class ColorChipButton extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int color;

    ColorChipButton(android.content.Context c) {
      super(c);
      setClickable(true);
      setFocusable(true);
      setBackground(Ui.ripple(new GradientDrawable(), 23, c));
    }

    void setColor(int argb) { color = argb; invalidate(); }

    @Override protected void onDraw(Canvas c) {
      float d = getResources().getDisplayMetrics().density;
      float pad = 12 * d;
      rect.set(pad, pad, getWidth() - pad, getHeight() - pad);
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(color);
      c.drawOval(rect, paint);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(1.2f * d);
      paint.setColor(Ui.dark ? 0xff5a5346 : 0xffc9bfab);
      rect.set(pad - 2 * d, pad - 2 * d, getWidth() - pad + 2 * d, getHeight() - pad + 2 * d);
      c.drawOval(rect, paint);
    }
  }

  /** A dot whose size previews a stroke width. */
  private static final class SizeDot extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color = Ui.INK;
    private float value = 3, scale = 3;
    private boolean selected;

    SizeDot(android.content.Context c) {
      super(c);
      setClickable(true);
      setFocusable(true);
    }

    void setColor(int argb) { color = argb; invalidate(); }
    void setValue(float value, float scale) { this.value = value; this.scale = scale; invalidate(); }
    void setChosen(boolean on) { selected = on; invalidate(); }

    @Override protected void onDraw(Canvas c) {
      float d = getResources().getDisplayMetrics().density;
      float maxR = Math.min(getWidth(), getHeight()) * .32f;
      float r = Math.max(3 * d, Math.min(maxR, value / Math.max(1f, scale) * maxR));
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(color);
      c.drawCircle(getWidth() / 2f, getHeight() / 2f, r, paint);
      if (selected) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f * d);
        paint.setColor(Ui.ACCENT);
        c.drawCircle(getWidth() / 2f, getHeight() / 2f, maxR + 2 * d, paint);
      }
    }
  }
}
