package app.paperharbor.notes;

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
    void exportPdf(String id);
    void backup(String id);
  }

  private static final class Meta {
    String id, title;
    int pages;
    long modified;
    JSONObject cover;
    final StringBuilder search = new StringBuilder();
  }

  private final Activity a;
  private final Store store;
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

  LibraryScreen(Activity activity, Store store, Host host) {
    super(activity);
    this.a = activity;
    this.store = store;
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
    TextView title = Ui.serifTitle(a, "纸港", 30, Ui.INK);
    titles.addView(title);
    TextView subtitle = Ui.label(a, "离线笔记 · 仅此设备", 10, Ui.MUTED);
    subtitle.setPadding(1, Ui.dp(a, 2), 0, 0);
    titles.addView(subtitle);
    header.addView(titles);
    header.addView(Ui.icon(a, R.drawable.ic_import, "导入文件", 42, Ui.INK, this::showImportSheet));
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
    searchField.setHint("搜索标题、页内文字或识别文本");
    searchField.setPadding(Ui.dp(a, 44), 0, Ui.dp(a, 16), 0);
    searchField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
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

    grid = new GridView(a);
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
    adapter.notifyDataSetChanged();
    boolean none = shown.isEmpty();
    empty.setVisibility(none ? VISIBLE : GONE);
    grid.setVisibility(none ? GONE : VISIBLE);
    if (none) {
      if (!loaded) {
        emptyIcon.setVisibility(GONE);
        emptyTitle.setText("正在读取笔记…");
        emptyHint.setText("");
      } else if (!q.isEmpty()) {
        emptyIcon.setVisibility(VISIBLE);
        emptyIcon.setImageResource(R.drawable.ic_search);
        emptyTitle.setText("没有找到匹配的笔记");
        emptyHint.setText("试试标题、页内文字或识别文本");
      } else {
        emptyIcon.setVisibility(VISIBLE);
        emptyIcon.setImageResource(R.drawable.ic_notebook);
        emptyTitle.setText("还没有笔记");
        emptyHint.setText("点右下角开始书写，或从右上角导入 PDF");
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
        FrameLayout cover = new AspectFrame(a, .74f);
        cover.setClipToOutline(true);
        cover.setOutlineProvider(new ViewOutlineProvider() {
          @Override public void getOutline(View v, android.graphics.Outline outline) {
            outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(a, 16));
          }
        });
        cover.setBackgroundColor(Ui.SURFACE);
        cover.setForeground(Ui.roundStroke(a, Color.TRANSPARENT, Ui.LINE, 16, .8f));
        ImageView image = new ImageView(a);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cover.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView placeholder = new ImageView(a);
        placeholder.setImageResource(R.drawable.ic_notebook);
        placeholder.setColorFilter(0xffd9d0bf);
        FrameLayout.LayoutParams php = new FrameLayout.LayoutParams(Ui.dp(a, 34), Ui.dp(a, 34));
        php.gravity = Gravity.CENTER;
        cover.addView(placeholder, php);
        View spine = new View(a);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(Ui.dp(a, 5), ViewGroup.LayoutParams.MATCH_PARENT);
        spine.setLayoutParams(slp);
        cover.addView(spine);
        TextView badge = Ui.text(a, "", 10, Ui.INK);
        badge.setBackground(Ui.round(a, 0xe6fffdf6, 9));
        badge.setPadding(Ui.dp(a, 7), Ui.dp(a, 3), Ui.dp(a, 7), Ui.dp(a, 3));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.END | Gravity.BOTTOM;
        blp.setMargins(0, 0, Ui.dp(a, 8), Ui.dp(a, 8));
        cover.addView(badge, blp);
        card.addView(cover, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
        card.setTag(new Holder(cover, image, placeholder, spine, badge, title, meta));
      }
      Holder h = (Holder) card.getTag();
      h.title.setText(m.title);
      String date = m.modified > 0
          ? new SimpleDateFormat("M月d日", Locale.CHINA).format(new Date(m.modified))
          : "";
      h.meta.setText(m.pages + " 页" + (date.isEmpty() ? "" : " · " + date));
      h.badge.setText(m.pages + " 页");
      h.spine.setBackgroundColor(Ui.coverColor(m.title));
      h.image.setImageDrawable(null);
      h.image.setTag(m.id);
      h.placeholder.setVisibility(VISIBLE);
      final String id = m.id;
      final JSONObject cover = m.cover;
      worker.execute(() -> {
        try {
          android.graphics.Bitmap bitmap = Thumbnails.render(a, store, id, cover, Ui.dp(a, 210));
          a.runOnUiThread(() -> {
            if (id.equals(h.image.getTag())) {
              h.image.setImageBitmap(bitmap);
              h.placeholder.setVisibility(GONE);
            }
          });
        } catch (Exception ignored) { }
      });
      card.setContentDescription("打开笔记：" + m.title);
      return card;
    }
  }

  private static final class Holder {
    final FrameLayout cover;
    final ImageView image, placeholder;
    final View spine;
    final TextView badge, title, meta;
    Holder(FrameLayout c, ImageView i, ImageView p, View s, TextView b, TextView t, TextView m) {
      cover = c; image = i; placeholder = p; spine = s; badge = b; title = t; meta = m;
    }
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
    EditText name = new EditText(a);
    name.setHint("笔记名称（可留空）");
    name.setHintTextColor(0xff9b9486);
    name.setTextColor(Ui.INK);
    name.setTextSize(16);
    name.setSingleLine(true);
    name.setBackground(Ui.round(a, Ui.SURFACE_SOFT, 14));
    name.setPadding(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16), Ui.dp(a, 12));
    LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    nlp.topMargin = Ui.dp(a, 12);
    content.addView(name, nlp);
    TextView label = Ui.label(a, "纸张", 11, Ui.MUTED);
    label.setPadding(0, Ui.dp(a, 18), 0, Ui.dp(a, 8));
    content.addView(label);
    String[] papers = {"ruled", "grid", "plain"};
    String[] paperNames = {"横线", "方格", "空白"};
    LinearLayout row = Ui.row(a);
    final int[] selected = {0};
    List<TemplatePreview> previews = new ArrayList<>();
    for (int i = 0; i < papers.length; i++) {
      final int index = i;
      LinearLayout cell = Ui.column(a);
      cell.setGravity(Gravity.CENTER);
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
      if (i > 0) clp.leftMargin = Ui.dp(a, 10);
      cell.setLayoutParams(clp);
      TemplatePreview preview = new TemplatePreview(a, papers[i]);
      LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 96));
      preview.setLayoutParams(plp);
      preview.setOnClickListener(v -> {
        selected[0] = index;
        for (int j = 0; j < previews.size(); j++) previews.get(j).setChosen(j == index);
      });
      cell.addView(preview);
      TextView nameTv = Ui.text(a, paperNames[i], 11.5f, Ui.MUTED);
      nameTv.setGravity(Gravity.CENTER);
      nameTv.setPadding(0, Ui.dp(a, 6), 0, 0);
      cell.addView(nameTv);
      row.addView(cell);
      previews.add(preview);
    }
    previews.get(0).setChosen(true);
    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    rlp.topMargin = Ui.dp(a, 4);
    content.addView(row, rlp);
    LinearLayout footer = Ui.row(a);
    footer.setGravity(Gravity.END);
    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    flp.topMargin = Ui.dp(a, 22);
    View create = Ui.pillButton(a, R.drawable.ic_check, "创建", Ui.ACCENT, Ui.WHITE, () -> {
      String title = name.getText().toString().trim();
      if (title.isEmpty()) title = "未命名笔记";
      final String paper = papers[selected[0]];
      final String finalTitle = title;
      if (ref[0] != null) ref[0].dismiss();
      createNote(finalTitle, paper);
    });
    footer.addView(create);
    content.addView(footer, flp);
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  private void createNote(String title, String paper) {
    final String finalTitle = title;
    worker.execute(() -> {
      try {
        String id = store.create(finalTitle, paper);
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
    String[][] items = {{"PDF", "PDF 文档"}, {"图片", "图片或照片"}, {"备份", "纸港备份"}};
    int[] icons = {R.drawable.ic_pdf, R.drawable.ic_image, R.drawable.ic_backup};
    Runnable[] actions = {
        () -> { ref[0].dismiss(); host.importPdf(); },
        () -> { ref[0].dismiss(); host.importImage(); },
        () -> { ref[0].dismiss(); host.restoreBackup(); }
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
    rows.addView(Ui.iconRow(a, R.drawable.ic_export, "导出 PDF", false,
        () -> { ref[0].dismiss(); host.exportPdf(m.id); }), new LinearLayout.LayoutParams(lp));
    rows.addView(Ui.iconRow(a, R.drawable.ic_backup, "备份可编辑笔记 .phnote", false,
        () -> { ref[0].dismiss(); host.backup(m.id); }), new LinearLayout.LayoutParams(lp));
    rows.addView(Ui.iconRow(a, R.drawable.ic_trash, "删除笔记", true,
        () -> { ref[0].dismiss(); confirmDelete(m); }), new LinearLayout.LayoutParams(lp));
    content.addView(rows);
    Dialog d = Ui.sheet(a, content, false);
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
      if (title.isEmpty()) { name.setError("名称不能为空"); return; }
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

  private void confirmDelete(Meta m) {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 22), 0, Ui.dp(a, 22), Ui.dp(a, 22));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, "删除笔记", closer(ref)));
    TextView msg = Ui.text(a, "「" + m.title + "」的笔迹、文字和录音会从本机删除，无法撤销。建议先备份 .phnote。", 14, Ui.MUTED);
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
      int height = Math.round(width / ratio);
      super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
  }

  /** Miniature paper swatch used by the template picker. */
  static final class TemplatePreview extends View {
    private final String paper;
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
      setContentDescription("ruled".equals(paper) ? "横线纸" : "grid".equals(paper) ? "方格纸" : "白纸");
    }

    void setChosen(boolean on) { selected = on; invalidate(); }

    @Override protected void onDraw(Canvas c) {
      float d = getResources().getDisplayMetrics().density;
      rect.set(d, d, getWidth() - d, getHeight() - d);
      fill.setColor(Ui.SURFACE);
      c.drawRoundRect(rect, 10 * d, 10 * d, fill);
      int save = c.save();
      clipPath.rewind();
      clipPath.addRoundRect(rect, 10 * d, 10 * d, android.graphics.Path.Direction.CW);
      c.clipPath(clipPath);
      line.setColor(0xffe2dccf);
      line.setStrokeWidth(.8f * d);
      if ("ruled".equals(paper)) {
        for (float y = rect.top + 18 * d; y < rect.bottom; y += 12 * d) c.drawLine(rect.left, y, rect.right, y, line);
      } else if ("grid".equals(paper)) {
        for (float y = rect.top + 12 * d; y < rect.bottom; y += 12 * d) c.drawLine(rect.left, y, rect.right, y, line);
        for (float x = rect.left + 12 * d; x < rect.right; x += 12 * d) c.drawLine(x, rect.top, x, rect.bottom, line);
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
