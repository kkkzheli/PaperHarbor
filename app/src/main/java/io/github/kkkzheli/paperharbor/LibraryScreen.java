package io.github.kkkzheli.paperharbor;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The local shelf: cover grid, search, import and per-note actions. */
final class LibraryScreen extends FrameLayout {
  interface Host {
    void open(String id, int page);
    void importPdf();
    void importImage();
    void restoreBackup();
    void importDocx();
    void exportPdf(String id);
    void backup(String id);
    void settings();
  }

  private static final class Meta {
    String id, title;
    int pages;
    long modified;
    JSONObject cover;
    String coverStyle = "spine";
    int coverColor = CoverArt.PALETTE[0];
    final StringBuilder search = new StringBuilder();
  }

  private final Activity a;
  private final Store store;
  private final Prefs prefs;
  private final Host host;
  private final ExecutorService worker = Executors.newFixedThreadPool(2, r -> {
    Thread t = new Thread(r, "library-covers");
    t.setDaemon(true);
    return t;
  });
  private final List<Meta> all = new ArrayList<>(), shown = new ArrayList<>();
  private GridView grid;
  private EditText searchField;
  private LinearLayout empty;
  private ImageView emptyIcon;
  private TextView emptyTitle, emptyHint;
  private ShelfAdapter adapter;
  private boolean loaded;

  LibraryScreen(Activity activity, Store store, Prefs prefs, Host host) {
    super(activity);
    this.a = activity;
    this.store = store;
    this.prefs = prefs;
    this.host = host;
    setBackgroundColor(Ui.BG);
    build();
    load();
  }

  private void build() {
    LinearLayout body = Ui.column(a);
    body.setClipToPadding(false);
    addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    LinearLayout header = Ui.row(a);
    header.setPadding(Ui.dp(a, 22), Ui.dp(a, 22), Ui.dp(a, 14), Ui.dp(a, 4));
    ImageView mark = new ImageView(a);
    mark.setImageResource(R.drawable.ic_notebook);
    mark.setColorFilter(Ui.INK);
    mark.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 26), Ui.dp(a, 26)));
    header.addView(mark);
    LinearLayout titles = Ui.column(a);
    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    tlp.leftMargin = Ui.dp(a, 10);
    titles.setLayoutParams(tlp);
    TextView title = Ui.serifTitle(a, "纸港", 28, Ui.INK);
    title.setSingleLine(true);
    title.setEllipsize(TextUtils.TruncateAt.END);
    titles.addView(title);
    TextView subtitle = Ui.label(a, "离线笔记 · 仅此设备", 10, Ui.MUTED);
    subtitle.setPadding(1, Ui.dp(a, 2), 0, 0);
    titles.addView(subtitle);
    header.addView(titles);
    header.addView(Ui.icon(a, R.drawable.ic_sort, "排序", 42, Ui.INK, this::showSortSheet));
    header.addView(Ui.icon(a, R.drawable.ic_import, "导入文件", 42, Ui.INK, this::showImportSheet));
    header.addView(Ui.icon(a, R.drawable.ic_settings, "设置", 42, Ui.INK, () -> host.settings()));
    body.addView(header);

    FrameLayout searchWrap = new FrameLayout(a);
    GradientDrawable searchBg = Ui.round(a, Ui.SURFACE_SOFT, 16);
    searchWrap.setBackground(searchBg);
    LinearLayout.LayoutParams swlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 46));
    swlp.setMargins(Ui.dp(a, 20), Ui.dp(a, 12), Ui.dp(a, 20), Ui.dp(a, 6));
    searchWrap.setLayoutParams(swlp);
    ImageView searchIcon = new ImageView(a);
    searchIcon.setImageResource(R.drawable.ic_search);
    searchIcon.setColorFilter(Ui.MUTED);
    FrameLayout.LayoutParams silp = new FrameLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 18));
    silp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
    silp.leftMargin = Ui.dp(a, 15);
    searchWrap.addView(searchIcon, silp);
    searchField = new EditText(a);
    searchField.setBackground(null);
    searchField.setSingleLine(true);
    searchField.setTextSize(14);
    searchField.setTextColor(Ui.INK);
    searchField.setHintTextColor(0xff9b9486);
    searchField.setHint(Str.of("搜索标题、页内文字或识别文本"));
    searchField.setPadding(Ui.dp(a, 44), 0, Ui.dp(a, 16), 0);
    searchField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    searchField.setVerticalScrollBarEnabled(false);
    FrameLayout.LayoutParams fllp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    searchWrap.addView(searchField, fllp);
    searchField.addTextChangedListener(new TextWatcher() {
      public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
      public void afterTextChanged(Editable s) { }
      public void onTextChanged(CharSequence s, int st, int b, int c) { filter(s.toString()); }
    });
    body.addView(searchWrap);

    FrameLayout shelf = new FrameLayout(a);
    LinearLayout.LayoutParams shelfLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
    shelf.setLayoutParams(shelfLp);
    body.addView(shelf);

    grid = Ui.grid(a, 150, 2, 4);
    grid.setNumColumns(2);
    grid.setHorizontalSpacing(Ui.dp(a, 14));
    grid.setVerticalSpacing(Ui.dp(a, 22));
    grid.setPadding(Ui.dp(a, 18), Ui.dp(a, 10), Ui.dp(a, 18), Ui.dp(a, 110));
    grid.setClipToPadding(false);
    grid.setVerticalScrollBarEnabled(false);
    grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
    adapter = new ShelfAdapter();
    grid.setAdapter(adapter);
    grid.setOnItemClickListener((p, v, pos, id) -> {
      Meta m = shown.get(pos);
      host.open(m.id, 0);
    });
    grid.setOnItemLongClickListener((p, v, pos, id) -> {
      showNoteSheet(shown.get(pos));
      return true;
    });
    shelf.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    empty = Ui.column(a);
    empty.setGravity(Gravity.CENTER);
    empty.setPadding(Ui.dp(a, 30), 0, Ui.dp(a, 30), Ui.dp(a, 80));
    emptyIcon = new ImageView(a);
    emptyIcon.setImageResource(R.drawable.ic_notebook);
    emptyIcon.setColorFilter(0xffc5bba7);
    emptyIcon.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 56)));
    empty.addView(emptyIcon);
    emptyTitle = Ui.serifTitle(a, "还没有笔记", 20, Ui.INK);
    emptyTitle.setGravity(Gravity.CENTER);
    emptyTitle.setPadding(0, Ui.dp(a, 16), 0, Ui.dp(a, 6));
    empty.addView(emptyTitle);
    emptyHint = Ui.text(a, "点右下角开始书写，或从右上角导入 PDF", 13, Ui.MUTED);
    emptyHint.setGravity(Gravity.CENTER);
    empty.addView(emptyHint);
    shelf.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    ImageButton fab = Ui.icon(a, R.drawable.ic_plus, "新建笔记", 58, Ui.WHITE, this::showNewSheet);
    fab.setBackground(Ui.ripple(Ui.ACCENT, 30, a));
    fab.setElevation(Ui.dp(a, 8));
    FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(Ui.dp(a, 58), Ui.dp(a, 58));
    fabLp.gravity = Gravity.END | Gravity.BOTTOM;
    fabLp.setMargins(0, 0, Ui.dp(a, 22), Ui.dp(a, 24));
    addView(fab, fabLp);
  }

  private void load() {
    final int generation = ++loadGeneration;
    worker.execute(() -> {
      List<Meta> result = new ArrayList<>();
      try {
        for (String id : store.ids()) {
          try {
            JSONObject doc = store.load(id);
            Meta m = new Meta();
            m.id = id;
            m.title = doc.optString("title", "未命名笔记");
            JSONArray pages = doc.getJSONArray("pages");
            m.pages = pages.length();
            m.cover = pages.optJSONObject(0);
            JSONObject design = doc.optJSONObject("cover");
            m.coverStyle = design == null ? "spine" : design.optString("style", "spine");
            m.coverColor = design == null ? Ui.coverColor(m.title)
                : design.optInt("color", Ui.coverColor(m.title));
            m.search.append(m.title.toLowerCase(Locale.ROOT)).append('\n');
            for (int i = 0; i < pages.length(); i++) {
              JSONObject p = pages.optJSONObject(i);
              if (p == null) continue;
              m.search.append(p.optString("text", "").toLowerCase(Locale.ROOT)).append('\n')
                  .append(p.optString("ocr", "").toLowerCase(Locale.ROOT)).append('\n');
            }
            try {
              File folder = store.folder(id);
              m.modified = new File(folder, "note.json").lastModified();
            } catch (Exception ignored) { }
            result.add(m);
          } catch (Exception ignored) {
            // One unreadable note must not hide the rest of the shelf.
          }
        }
      } catch (Exception ignored) { }
      final List<Meta> ready = result;
      a.runOnUiThread(() -> {
        if (generation != loadGeneration) return;
        loaded = true;
        all.clear();
        all.addAll(ready);
        filter(searchField.getText().toString());
      });
    });
  }

  private int loadGeneration;

  void refresh() {
    if (loaded) load();
  }

  void dispose() {
    worker.shutdownNow();
    grid.setAdapter(null);
  }

  private void filter(String query) {
    String q = query.trim().toLowerCase(Locale.ROOT);
    shown.clear();
    for (Meta m : all) if (q.isEmpty() || m.search.toString().contains(q)) shown.add(m);
    sort();
    adapter.notifyDataSetChanged();
    boolean none = shown.isEmpty();
    empty.setVisibility(none ? VISIBLE : GONE);
    grid.setVisibility(none ? GONE : VISIBLE);
    if (none) {
      if (!loaded) {
        emptyIcon.setVisibility(GONE);
        emptyTitle.setText(Str.of("正在读取笔记…"));
        emptyHint.setText("");
      } else if (!q.isEmpty()) {
        emptyIcon.setVisibility(VISIBLE);
        emptyIcon.setImageResource(R.drawable.ic_search);
        emptyTitle.setText(Str.of("没有找到匹配的笔记"));
        emptyHint.setText(Str.of("试试标题、页内文字或识别文本"));
      } else {
        emptyIcon.setVisibility(VISIBLE);
        emptyIcon.setImageResource(R.drawable.ic_notebook);
        emptyTitle.setText(Str.of("还没有笔记"));
        emptyHint.setText(Str.of("点右下角开始书写，或从右上角导入 PDF"));
      }
    }
  }

  private final class ShelfAdapter extends BaseAdapter {
    @Override public int getCount() { return shown.size(); }
    @Override public Object getItem(int position) { return shown.get(position); }
    @Override public long getItemId(int position) { return shown.get(position).id.hashCode(); }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
      Meta m = shown.get(position);
      LinearLayout card = (LinearLayout) convertView;
      if (card == null) {
        card = Ui.column(a);
        FrameLayout frame = new AspectFrame(a, .74f);
        frame.setClipToOutline(true);
        frame.setOutlineProvider(new ViewOutlineProvider() {
          @Override public void getOutline(View v, android.graphics.Outline outline) {
            outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(a, 16));
          }
        });
        CoverArt cover = new CoverArt(a);
        frame.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView badge = Ui.text(a, "", 10, Ui.INK);
        badge.setBackground(Ui.round(a, 0xe6fffdf6, 9));
        badge.setPadding(Ui.dp(a, 7), Ui.dp(a, 3), Ui.dp(a, 7), Ui.dp(a, 3));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.END | Gravity.BOTTOM;
        blp.setMargins(0, 0, Ui.dp(a, 8), Ui.dp(a, 8));
        frame.addView(badge, blp);
        card.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout info = Ui.column(a);
        info.setPadding(Ui.dp(a, 3), Ui.dp(a, 8), Ui.dp(a, 3), 0);
        TextView title = Ui.text(a, "", 15, Ui.INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title);
        TextView meta = Ui.text(a, "", 11.5f, Ui.MUTED);
        meta.setPadding(0, Ui.dp(a, 2), 0, 0);
        info.addView(meta);
        card.addView(info);
        card.setTag(new Holder(cover, badge, title, meta));
      }
      Holder h = (Holder) card.getTag();
      h.title.setText(m.title);
      String date = m.modified > 0 ? Str.date(m.modified) : "";
      h.meta.setText(Str.noteMeta(m.pages, date));
      h.badge.setText(Str.cardBadge(m.pages));
      h.cover.setCover(m.coverStyle, m.coverColor);
      h.cover.setTitle(m.title);
      h.cover.setTag(m.id);
      h.cover.setPage(null);
      final String id = m.id;
      final JSONObject cover = m.cover;
      worker.execute(() -> {
        try {
          android.graphics.Bitmap bitmap = Thumbnails.render(a, store, id, cover, Ui.dp(a, 210));
          a.runOnUiThread(() -> {
            if (id.equals(h.cover.getTag())) h.cover.setPage(bitmap);
          });
        } catch (Exception ignored) { }
      });
      card.setContentDescription(Str.openNote(m.title));
      return card;
    }
  }

  private static final class Holder {
    final CoverArt cover;
    final TextView badge, title, meta;
    Holder(CoverArt c, TextView b, TextView t, TextView m) {
      cover = c; badge = b; title = t; meta = m;
    }
  }

  private void sort() {
    int mode = prefs.sort();
    if (mode == 1) java.util.Collections.sort(shown, (x, y) -> x.title.compareToIgnoreCase(y.title));
    else if (mode == 2) java.util.Collections.sort(shown, (x, y) -> Integer.compare(y.pages, x.pages));
    else java.util.Collections.sort(shown, (x, y) -> Long.compare(y.modified, x.modified));
  }

  private void showSortSheet() {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 14), 0, Ui.dp(a, 14), Ui.dp(a, 18));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, "书架排序", closer(ref)));
    String[] names = {"最近修改", "标题 A→Z", "页数多→少"};
    int[] icons = {R.drawable.ic_sort, R.drawable.ic_rename, R.drawable.ic_pages};
    int current = prefs.sort();
    LinearLayout rows = Ui.column(a);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.topMargin = Ui.dp(a, 6);
    for (int i = 0; i < names.length; i++) {
      final int mode = i;
      LinearLayout row = Ui.row(a);
      row.setBackground(Ui.ripple(new GradientDrawable(), 14, a));
      row.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8));
      row.setClickable(true);
      row.setFocusable(true);
      ImageView icon = new ImageView(a);
      icon.setImageResource(icons[i]);
      icon.setColorFilter(Ui.INK);
      int box = Ui.dp(a, 38);
      icon.setLayoutParams(new LinearLayout.LayoutParams(box, box));
      icon.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8));
      icon.setBackground(Ui.round(a, Ui.SURFACE_SOFT, 12));
      row.addView(icon);
      TextView label = Ui.text(a, names[i], 15, Ui.INK);
      LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
      tlp.leftMargin = Ui.dp(a, 12);
      row.addView(label, tlp);
      ImageView check = new ImageView(a);
      check.setImageResource(R.drawable.ic_check);
      check.setColorFilter(mode == current ? Ui.ACCENT : Ui.LINE);
      check.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 18)));
      row.addView(check);
      row.setContentDescription(names[i]);
      row.setOnClickListener(v -> {
        prefs.setSort(mode);
        filter(searchField.getText().toString());
        ref[0].dismiss();
      });
      rows.addView(row, new LinearLayout.LayoutParams(lp));
    }
    content.addView(rows);
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  private static Runnable closer(Dialog[] ref) {
    return () -> {
      if (ref[0] != null) ref[0].dismiss();
    };
  }

  private void showNewSheet() {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), Ui.dp(a, 22));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, "新建笔记", closer(ref)));

    final String[] style = {prefs.coverStyle()};
    final int[] coverColor = {prefs.coverColor()};
    final String[] paper = {prefs.paper()};
    final int[] tint = {prefs.paperTint()};

    LinearLayout top = Ui.row(a);
    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    tlp.topMargin = Ui.dp(a, 6);
    content.addView(top, tlp);
    final CoverArt preview = new CoverArt(a);
    preview.setCover(style[0], coverColor[0]);
    preview.setTitle(Str.of("未命名笔记"));
    LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(a, 84), Ui.dp(a, 112));
    plp.rightMargin = Ui.dp(a, 14);
    top.addView(preview, plp);

    EditText name = new EditText(a);
    name.setHint(Str.of("笔记名称（可留空）"));
    name.setHintTextColor(0xff9b9486);
    name.setTextColor(Ui.INK);
    name.setTextSize(16);
    name.setSingleLine(true);
    name.setBackground(Ui.round(a, Ui.SURFACE_SOFT, 14));
    name.setPadding(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16), Ui.dp(a, 12));
    LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    nlp.gravity = Gravity.CENTER_VERTICAL;
    top.addView(name, nlp);
    name.addTextChangedListener(new TextWatcher() {
      public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
      public void onTextChanged(CharSequence s, int st, int b, int c) {
        String value = s.toString().trim();
        preview.setTitle(value.isEmpty() ? Str.of("未命名笔记") : value);
      }
      public void afterTextChanged(Editable s) { }
    });

    content.addView(Ui.sectionLabel(a, "封面样式"));
    LinearLayout styles = Ui.row(a);
    final LinearLayout[] styleTiles = new LinearLayout[CoverArt.STYLES.length];
    for (int i = 0; i < CoverArt.STYLES.length; i++) {
      final String key = CoverArt.STYLES[i];
      LinearLayout cell = Ui.column(a);
      cell.setGravity(Gravity.CENTER);
      CoverArt sample = new CoverArt(a);
      sample.setCover(key, coverColor[0]);
      cell.addView(sample, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 54)));
      LinearLayout tile = Ui.choiceTile(a, cell, CoverArt.STYLE_NAMES[i], null);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
      if (i > 0) lp.leftMargin = Ui.dp(a, 6);
      tile.setLayoutParams(lp);
      final int index = i;
      tile.setOnClickListener(v -> {
        style[0] = key;
        for (int j = 0; j < styleTiles.length; j++) Ui.setChosen(styleTiles[j], j == index);
        preview.setCover(key, coverColor[0]);
      });
      styleTiles[i] = tile;
      styles.addView(tile);
    }
    for (int i = 0; i < styleTiles.length; i++)
      Ui.setChosen(styleTiles[i], CoverArt.STYLES[i].equals(style[0]));
    content.addView(styles);

    LinearLayout colours = Ui.row(a);
    colours.setGravity(Gravity.CENTER);
    colours.setPadding(0, Ui.dp(a, 10), 0, 0);
    content.addView(colours);
    for (int i = 0; i < CoverArt.PALETTE.length; i++) {
      final int value = CoverArt.PALETTE[i];
      Ui.ColorChip chip = new Ui.ColorChip(a);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 30), Ui.dp(a, 30));
      if (i > 0) lp.leftMargin = Ui.dp(a, 8);
      chip.setLayoutParams(lp);
      chip.setColor(value);
      chip.setSelectedChip(value == coverColor[0]);
      chip.setContentDescription(Str.of("封面颜色"));
      chip.setOnClickListener(v -> {
        coverColor[0] = value;
        for (int j = 0; j < colours.getChildCount(); j++)
          ((Ui.ColorChip) colours.getChildAt(j)).setSelectedChip(colours.getChildAt(j) == chip);
        preview.setCover(style[0], value);
      });
      colours.addView(chip);
    }

    content.addView(Ui.sectionLabel(a, "纸张"));
    LinearLayout papers = Ui.row(a);
    final LibraryScreen.TemplatePreview[] previews = new LibraryScreen.TemplatePreview[InkPaper.PATTERNS.length];
    for (int i = 0; i < InkPaper.PATTERNS.length; i++) {
      final String key = InkPaper.PATTERNS[i];
      LinearLayout cell = Ui.column(a);
      cell.setGravity(Gravity.CENTER);
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
      if (i > 0) clp.leftMargin = Ui.dp(a, 6);
      cell.setLayoutParams(clp);
      LibraryScreen.TemplatePreview previewPaper = new LibraryScreen.TemplatePreview(a, key);
      previewPaper.setTint(tint[0]);
      previewPaper.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 78)));
      previewPaper.setOnClickListener(v -> {
        paper[0] = key;
        for (int j = 0; j < previews.length; j++) previews[j].setChosen(InkPaper.PATTERNS[j].equals(key));
      });
      previews[i] = previewPaper;
      cell.addView(previewPaper);
      TextView label = Ui.text(a, InkPaper.PATTERN_NAMES[i], 10, Ui.MUTED);
      label.setGravity(Gravity.CENTER);
      label.setPadding(0, Ui.dp(a, 4), 0, 0);
      cell.addView(label);
      papers.addView(cell);
    }
    for (int i = 0; i < previews.length; i++) previews[i].setChosen(InkPaper.PATTERNS[i].equals(paper[0]));
    content.addView(papers);

    LinearLayout tints = Ui.row(a);
    tints.setGravity(Gravity.CENTER);
    tints.setPadding(0, Ui.dp(a, 10), 0, 0);
    content.addView(tints);
    for (int i = 0; i < InkPaper.TINTS.length; i++) {
      final int value = InkPaper.TINTS[i];
      Ui.ColorChip chip = new Ui.ColorChip(a);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 30), Ui.dp(a, 30));
      if (i > 0) lp.leftMargin = Ui.dp(a, 8);
      chip.setLayoutParams(lp);
      chip.setColor(value);
      chip.setSelectedChip(value == tint[0]);
      chip.setContentDescription(Str.of("纸张颜色"));
      chip.setOnClickListener(v -> {
        tint[0] = value;
        for (int j = 0; j < tints.getChildCount(); j++)
          ((Ui.ColorChip) tints.getChildAt(j)).setSelectedChip(tints.getChildAt(j) == chip);
        for (LibraryScreen.TemplatePreview p : previews) p.setTint(value);
      });
      tints.addView(chip);
    }

    LinearLayout footer = Ui.row(a);
    footer.setGravity(Gravity.END);
    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    flp.topMargin = Ui.dp(a, 18);
    View create = Ui.pillButton(a, R.drawable.ic_check, "创建", Ui.ACCENT, Ui.WHITE, () -> {
      String title = name.getText().toString().trim();
      if (title.isEmpty()) title = Str.of("未命名笔记");
      prefs.setCoverStyle(style[0]);
      prefs.setCoverColor(coverColor[0]);
      prefs.setPaper(paper[0]);
      prefs.setPaperTint(tint[0]);
      if (ref[0] != null) ref[0].dismiss();
      createNote(title, paper[0], tint[0], style[0], coverColor[0]);
    });
    footer.addView(create);
    content.addView(footer, flp);
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  private void createNote(String title, String paper, int tint, String coverStyle, int coverColor) {
    final String finalTitle = title;
    worker.execute(() -> {
      try {
        String id = store.create(finalTitle, paper, tint, coverStyle, coverColor);
        a.runOnUiThread(() -> host.open(id, 0));
      } catch (Exception e) {
        a.runOnUiThread(() -> showError("无法创建笔记", e));
      }
    });
  }

  private void showImportSheet() {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), Ui.dp(a, 24));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, "导入到书架", closer(ref)));
    LinearLayout row = Ui.row(a);
    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    rlp.topMargin = Ui.dp(a, 10);
    content.addView(row, rlp);
    String[][] items = {{"PDF", "PDF 文档"}, {"图片", "图片或照片"},
        {"备份", "纸港备份"}, {"Word", "Word 文档"}};
    int[] icons = {R.drawable.ic_pdf, R.drawable.ic_image, R.drawable.ic_backup, R.drawable.ic_word};
    Runnable[] actions = {
        () -> { ref[0].dismiss(); host.importPdf(); },
        () -> { ref[0].dismiss(); host.importImage(); },
        () -> { ref[0].dismiss(); host.restoreBackup(); },
        () -> { ref[0].dismiss(); host.importDocx(); }
    };
    for (int i = 0; i < items.length; i++) {
      View tile = Ui.iconTile(a, icons[i], items[i][1], false, actions[i]);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
      if (i > 0) lp.leftMargin = Ui.dp(a, 8);
      row.addView(tile, lp);
    }
    Dialog d = Ui.sheet(a, content, false);
    ref[0] = d;
    d.show();
  }

  private void showNoteSheet(Meta m) {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 14), 0, Ui.dp(a, 14), Ui.dp(a, 18));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, m.title, closer(ref)));
    LinearLayout rows = Ui.column(a);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.topMargin = Ui.dp(a, 6);
    rows.addView(Ui.iconRow(a, R.drawable.ic_rename, "重命名", false,
        () -> { ref[0].dismiss(); showRename(m); }), new LinearLayout.LayoutParams(lp));
    rows.addView(Ui.iconRow(a, R.drawable.ic_cover, "封面", false,
        () -> { ref[0].dismiss(); loadForCover(m); }), new LinearLayout.LayoutParams(lp));
    rows.addView(Ui.iconRow(a, R.drawable.ic_backup, "分享备份", false,
        () -> { ref[0].dismiss(); shareBackup(m); }), new LinearLayout.LayoutParams(lp));
    rows.addView(Ui.iconRow(a, R.drawable.ic_export, "导出 PDF", false,
        () -> { ref[0].dismiss(); host.exportPdf(m.id); }), new LinearLayout.LayoutParams(lp));
    rows.addView(Ui.iconRow(a, R.drawable.ic_backup, "备份可编辑笔记 .phnote", false,
        () -> { ref[0].dismiss(); host.backup(m.id); }), new LinearLayout.LayoutParams(lp));
    rows.addView(Ui.iconRow(a, R.drawable.ic_trash, "删除笔记", true,
        () -> { ref[0].dismiss(); confirmDelete(m); }), new LinearLayout.LayoutParams(lp));
    content.addView(rows);
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  private void showRename(Meta m) {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), Ui.dp(a, 20));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, "重命名", closer(ref)));
    EditText name = new EditText(a);
    name.setText(m.title);
    name.setTextSize(16);
    name.setTextColor(Ui.INK);
    name.setSingleLine(true);
    name.setBackground(Ui.round(a, Ui.SURFACE_SOFT, 14));
    name.setPadding(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16), Ui.dp(a, 12));
    LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    nlp.topMargin = Ui.dp(a, 12);
    content.addView(name, nlp);
    LinearLayout footer = Ui.row(a);
    footer.setGravity(Gravity.END);
    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    flp.topMargin = Ui.dp(a, 18);
    footer.addView(Ui.pillButton(a, R.drawable.ic_check, "保存", Ui.INK, Ui.WHITE, () -> {
      String title = name.getText().toString().trim();
      if (title.isEmpty()) { name.setError(Str.of("名称不能为空")); return; }
      if (ref[0] != null) ref[0].dismiss();
      worker.execute(() -> {
        try {
          JSONObject doc = store.load(m.id);
          doc.put("title", title);
          store.save(m.id, doc);
          a.runOnUiThread(this::load);
        } catch (Exception e) {
          a.runOnUiThread(() -> showError("重命名失败", e));
        }
      });
    }));
    content.addView(footer, flp);
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  /** Packs the note and hands the .phnote to another app through the system share sheet. */
  private void shareBackup(final Meta m) {
    worker.execute(() -> {
      try {
        File dir = ShareProvider.folder(a);
        File[] old = dir.listFiles();
        if (old != null) for (File file : old) if (file.isFile()) file.delete();
        final File target = Share.file(a, Share.stem(m.title) + ".phnote");
        JSONObject doc = store.load(m.id);
        try (java.io.OutputStream out = new java.io.FileOutputStream(target)) {
          store.backup(m.id, doc, out);
        }
        a.runOnUiThread(() -> Share.send(a, target, Share.PHNOTE, m.title));
      } catch (final Exception e) {
        a.runOnUiThread(() -> showError("分享失败", e));
      }
    });
  }

  private void loadForCover(final Meta m) {
    worker.execute(() -> {
      try {
        final JSONObject doc = store.load(m.id);
        a.runOnUiThread(() -> showCoverEditor(m, doc));
      } catch (final Exception e) {
        a.runOnUiThread(() -> showError("无法读取笔记", e));
      }
    });
  }

  private void showCoverEditor(Meta m, JSONObject doc) {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), Ui.dp(a, 22));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, "封面", closer(ref)));
    JSONObject cover = doc.optJSONObject("cover");
    final String[] style = {cover == null ? m.coverStyle : cover.optString("style", m.coverStyle)};
    final int[] color = {cover == null ? m.coverColor : cover.optInt("color", m.coverColor)};

    final CoverArt preview = new CoverArt(a);
    preview.setCover(style[0], color[0]);
    preview.setTitle(m.title);
    LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(a, 96), Ui.dp(a, 128));
    plp.gravity = Gravity.CENTER_HORIZONTAL;
    plp.topMargin = Ui.dp(a, 8);
    content.addView(preview, plp);

    content.addView(Ui.sectionLabel(a, "封面样式"));
    LinearLayout styles = Ui.row(a);
    final LinearLayout[] styleTiles = new LinearLayout[CoverArt.STYLES.length];
    for (int i = 0; i < CoverArt.STYLES.length; i++) {
      final String key = CoverArt.STYLES[i];
      LinearLayout cell = Ui.column(a);
      cell.setGravity(Gravity.CENTER);
      CoverArt sample = new CoverArt(a);
      sample.setCover(key, color[0]);
      cell.addView(sample, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 58)));
      LinearLayout tile = Ui.choiceTile(a, cell, CoverArt.STYLE_NAMES[i], null);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
      if (i > 0) lp.leftMargin = Ui.dp(a, 6);
      tile.setLayoutParams(lp);
      final int index = i;
      tile.setOnClickListener(v -> {
        style[0] = key;
        for (int j = 0; j < styleTiles.length; j++) Ui.setChosen(styleTiles[j], j == index);
        preview.setCover(key, color[0]);
      });
      styleTiles[i] = tile;
      styles.addView(tile);
    }
    for (int i = 0; i < styleTiles.length; i++)
      Ui.setChosen(styleTiles[i], CoverArt.STYLES[i].equals(style[0]));
    content.addView(styles);

    content.addView(Ui.sectionLabel(a, "封面颜色"));
    LinearLayout colours = Ui.row(a);
    colours.setGravity(Gravity.CENTER);
    for (int i = 0; i < CoverArt.PALETTE.length; i++) {
      final int value = CoverArt.PALETTE[i];
      Ui.ColorChip chip = new Ui.ColorChip(a);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 32), Ui.dp(a, 32));
      if (i > 0) lp.leftMargin = Ui.dp(a, 8);
      chip.setLayoutParams(lp);
      chip.setColor(value);
      chip.setSelectedChip(value == color[0]);
      chip.setContentDescription(Str.of("封面颜色"));
      chip.setOnClickListener(v -> {
        color[0] = value;
        for (int j = 0; j < colours.getChildCount(); j++)
          ((Ui.ColorChip) colours.getChildAt(j)).setSelectedChip(colours.getChildAt(j) == chip);
        preview.setCover(style[0], value);
      });
      colours.addView(chip);
    }
    content.addView(colours);

    LinearLayout footer = Ui.row(a);
    footer.setGravity(Gravity.END);
    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    flp.topMargin = Ui.dp(a, 18);
    footer.addView(Ui.pillButton(a, R.drawable.ic_check, "保存", Ui.INK, Ui.ON_INK, () -> {
      try {
        doc.put("cover", new JSONObject().put("style", style[0]).put("color", color[0]));
      } catch (Exception ignored) { }
      ref[0].dismiss();
      worker.execute(() -> {
        try {
          store.save(m.id, doc);
          a.runOnUiThread(this::load);
        } catch (final Exception e) {
          a.runOnUiThread(() -> showError("保存失败", e));
        }
      });
    }));
    content.addView(footer, flp);
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  private void confirmDelete(Meta m) {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 22), 0, Ui.dp(a, 22), Ui.dp(a, 22));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, "删除笔记", closer(ref)));
    TextView msg = Ui.text(a, Str.deleteNoteBody(m.title), 14, Ui.MUTED);
    msg.setPadding(0, Ui.dp(a, 10), 0, 0);
    content.addView(msg);
    LinearLayout footer = Ui.row(a);
    footer.setGravity(Gravity.END);
    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    flp.topMargin = Ui.dp(a, 20);
    footer.addView(Ui.pillButton(a, 0, "取消", Ui.SURFACE_SOFT, Ui.INK, () -> ref[0].dismiss()));
    View del = Ui.pillButton(a, R.drawable.ic_trash, "删除", Ui.DANGER, Ui.WHITE, () -> {
      ref[0].dismiss();
      worker.execute(() -> {
        try {
          store.delete(m.id);
          a.runOnUiThread(this::load);
        } catch (Exception e) {
          a.runOnUiThread(() -> showError("删除失败", e));
        }
      });
    });
    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    dlp.leftMargin = Ui.dp(a, 10);
    footer.addView(del, dlp);
    content.addView(footer, flp);
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  private void showError(String title, Exception e) {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 22), 0, Ui.dp(a, 22), Ui.dp(a, 22));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, title, closer(ref)));
    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    TextView msg = Ui.text(a, message, 14, Ui.MUTED);
    msg.setPadding(0, Ui.dp(a, 10), 0, 0);
    content.addView(msg);
    LinearLayout footer = Ui.row(a);
    footer.setGravity(Gravity.END);
    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    flp.topMargin = Ui.dp(a, 18);
    footer.addView(Ui.pillButton(a, 0, "知道了", Ui.INK, Ui.WHITE, () -> ref[0].dismiss()));
    content.addView(footer, flp);
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  private static final class AspectFrame extends FrameLayout {
    private final float ratio;
    AspectFrame(Context c, float ratio) {
      super(c);
      this.ratio = ratio;
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
      int width = MeasureSpec.getSize(widthSpec);
      if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED || width <= 0) {
        // A child with no intrinsic size (a drawn cover) would otherwise collapse to zero width.
        width = Math.max(1, getResources().getDisplayMetrics().widthPixels / 3);
      }
      int height = Math.round(width / ratio);
      super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
          MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
  }

  /** Miniature paper swatch used by the template picker. */
  static final class TemplatePreview extends View {
    private String paper;
    private int tint = Ui.PAPER;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final android.graphics.Path clipPath = new android.graphics.Path();
    private boolean selected;

    TemplatePreview(Context c, String paper) {
      super(c);
      this.paper = paper;
      setClickable(true);
      setFocusable(true);
      setContentDescription(Str.of("ruled".equals(paper) ? "横线纸" : "grid".equals(paper) ? "方格纸" : "白纸"));
    }

    void setChosen(boolean on) { selected = on; invalidate(); }
    void setTint(int value) { tint = value; invalidate(); }
    void setPaper(String value) { paper = value; invalidate(); }

    @Override protected void onDraw(Canvas c) {
      float d = getResources().getDisplayMetrics().density;
      rect.set(d, d, getWidth() - d, getHeight() - d);
      fill.setColor(tint);
      c.drawRoundRect(rect, 10 * d, 10 * d, fill);
      int save = c.save();
      clipPath.rewind();
      clipPath.addRoundRect(rect, 10 * d, 10 * d, android.graphics.Path.Direction.CW);
      c.clipPath(clipPath);
      line.setColor(InkPaper.darkTint(tint) ? 0x40ffffff : 0xffe2dccf);
      line.setStrokeWidth(.8f * d);
      if ("ruled".equals(paper)) {
        for (float y = rect.top + 18 * d; y < rect.bottom; y += 12 * d) c.drawLine(rect.left, y, rect.right, y, line);
      } else if ("grid".equals(paper)) {
        for (float y = rect.top + 12 * d; y < rect.bottom; y += 12 * d) c.drawLine(rect.left, y, rect.right, y, line);
        for (float x = rect.left + 12 * d; x < rect.right; x += 12 * d) c.drawLine(x, rect.top, x, rect.bottom, line);
      } else if ("dot".equals(paper)) {
        fill.setColor(InkPaper.darkTint(tint) ? 0x66ffffff : 0xffd5cec0);
        for (float y = rect.top + 10 * d; y < rect.bottom; y += 12 * d)
          for (float x = rect.left + 10 * d; x < rect.right; x += 12 * d) c.drawCircle(x, y, .9f * d, fill);
      } else if ("cornell".equals(paper)) {
        for (float y = rect.top + 16 * d; y < rect.bottom; y += 12 * d) c.drawLine(rect.left, y, rect.right, y, line);
        line.setStrokeWidth(1.3f * d);
        c.drawLine(rect.left, rect.top + 26 * d, rect.right, rect.top + 26 * d, line);
        c.drawLine(rect.left + 26 * d, rect.top + 26 * d, rect.left + 26 * d, rect.bottom, line);
      }
      c.restoreToCount(save);
      line.setStyle(Paint.Style.STROKE);
      line.setStrokeWidth(selected ? 2.2f * d : .8f * d);
      line.setColor(selected ? Ui.ACCENT : Ui.LINE);
      c.drawRoundRect(rect, 10 * d, 10 * d, line);
      line.setStyle(Paint.Style.FILL);
    }
  }
}
