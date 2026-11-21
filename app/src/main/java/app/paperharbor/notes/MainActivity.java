package app.paperharbor.notes;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.*;

public final class MainActivity extends Activity {
  static final int BG = 0xfff6f2e9,
      CARD = 0xfffffdf7,
      TEAL = 0xff164c46,
      DIM = 0xff536960,
      WARM = 0xffefb389;
  static final int PDF = 11, RESTORE = 12, EXPORT = 13, BACKUP = 14, IMAGE = 15;
  private static final ExecutorService disk = Executors.newSingleThreadExecutor();
  private Store store;
  private StudyAudio study;
  private String id, exportId, filter = "", activeTool = "pen";
  private JSONObject doc;
  private int index, hue = 0xff243c39;
  private float strokeWidth = 3;
  private InkView canvas;
  private LinearLayout root, shelves;
  private TextView status;
  private Bitmap background;
  private final List<Meta> meta = new ArrayList<>();
  private int screenVersion;
  private long editNumber;
  private boolean saveFailed;

  private static class Meta {
    String id, title;
    ArrayList<String> text = new ArrayList<>();
  }

  private static class Save {
    final String id, json;
    final long version;

    Save(String a, String b, long v) {
      id = a;
      json = b;
      version = v;
    }
  }

  private final LinkedHashMap<String, Save> pending = new LinkedHashMap<>();
  private boolean draining;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setStatusBarColor(TEAL);
    getWindow().setNavigationBarColor(TEAL);
    store = new Store(this);
    study = new StudyAudio(this);
    if (state != null) exportId = state.getString("exportId");
    String last = state == null ? null : state.getString("notebook");
    if (last != null) open(last, state.getInt("page"));
    else home();
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    b.putString("notebook", id);
    b.putInt("page", index);
    b.putString("exportId", exportId);
  }

  @Override
  protected void onStop() {
    super.onStop();
    study.stop();
  }

  @Override
  public void onBackPressed() {
    if (doc == null) {
      super.onBackPressed();
      return;
    }
    study.stop();
    String key = id, snapshot = doc.toString();
    work(
        "保存并返回书架…",
        () -> {
          store.save(key, new JSONObject(snapshot));
          return true;
        },
        x -> home());
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
    super.onRequestPermissionsResult(code, perms, results);
    if (code == 142)
      study.permissionResult(
          results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED);
  }

  private boolean alive() {
    return !isFinishing() && !isDestroyed();
  }

  private int dp(float n) {
    return Math.round(getResources().getDisplayMetrics().density * n);
  }

  private void pad(View v, int x, int y) {
    v.setPadding(dp(x), dp(y), dp(x), dp(y));
  }

  private GradientDrawable bg(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  private LinearLayout col() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private TextView label(String text, int size, int color) {
    TextView t = new TextView(this);
    t.setText(text);
    t.setTextSize(size);
    t.setTextColor(color);
    return t;
  }

  private void bold(TextView t) {
    t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
  }

  private void flex(LinearLayout host, View child) {
    host.addView(
        child,
        host.getOrientation() == LinearLayout.VERTICAL
            ? new LinearLayout.LayoutParams(-1, 0, 1)
            : new LinearLayout.LayoutParams(0, -2, 1));
  }

  private Button button(String name, int fill, int fg, Runnable action) {
    Button b = new Button(this);
    b.setAllCaps(false);
    b.setText(name);
    b.setTextSize(14);
    b.setTextColor(fg);
    b.setBackground(bg(fill, 12));
    b.setMinHeight(dp(46));
    b.setMinimumHeight(dp(46));
    b.setMinWidth(dp(48));
    b.setMinimumWidth(dp(48));
    pad(b, 12, 3);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private Button add(LinearLayout r, String name, Runnable action) {
    Button b = button(name, CARD, TEAL, action);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.setMargins(0, 0, dp(5), 0);
    r.addView(b, lp);
    return b;
  }

  private void strip(LinearLayout host, LinearLayout r) {
    HorizontalScrollView s = new HorizontalScrollView(this);
    s.setHorizontalScrollBarEnabled(false);
    s.addView(r);
    host.addView(s);
  }

  private void frame() {
    root = col();
    root.setBackgroundColor(BG);
    setContentView(root);
  }

  private void tell(String msg) {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
  }

  private void error(Exception e) {
    if (alive())
      new AlertDialog.Builder(this)
          .setTitle("操作未完成")
          .setMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
          .setPositiveButton("知道了", null)
          .show();
  }

  private <T> void work(String title, Callable<T> op, Consumer<T> ok) {
    ProgressDialog p = new ProgressDialog(this);
    p.setMessage(title);
    p.setCancelable(false);
    p.show();
    disk.execute(
        () -> {
          try {
            T result = op.call();
            runOnUiThread(
                () -> {
                  if (alive()) {
                    p.dismiss();
                    ok.accept(result);
                  }
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (alive()) {
                    p.dismiss();
                    error(e);
                  }
                });
          }
        });
  }

  private static void put(JSONObject o, String k, Object value) {
    try {
      o.put(k, value);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private JSONObject page() {
    return doc.optJSONArray("pages").optJSONObject(index);
  }

  private int count() {
    return doc.optJSONArray("pages").length();
  }

  // Media deletion must not remove the file until its metadata commit has succeeded.
  private void saveStudy() {
    final String key = id, snapshot = doc.toString();
    try {
      disk.submit(
              () -> {
                store.save(key, new JSONObject(snapshot));
                return null;
              })
          .get();
      ++editNumber;
      saveFailed = false;
      if (status != null) status.setText("已存本机");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("保存被中断", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("无法保存笔记", e.getCause());
    }
  }

  private void save() {
    if (doc == null) return;
    final Save s = new Save(id, doc.toString(), ++editNumber);
    if (status != null) status.setText("正在保存…");
    synchronized (pending) {
      pending.put(s.id, s);
      if (draining) return;
      draining = true;
    }
    disk.execute(
        () -> {
          while (true) {
            Save next;
            synchronized (pending) {
              if (pending.isEmpty()) {
                draining = false;
                return;
              }
              String key = pending.keySet().iterator().next();
              next = pending.remove(key);
            }
            try {
              store.save(next.id, new JSONObject(next.json));
              runOnUiThread(
                  () -> {
                    if (alive() && next.id.equals(id) && next.version == editNumber) {
                      saveFailed = false;
                      if (status != null) status.setText("已存本机");
                    }
                  });
            } catch (Exception e) {
              runOnUiThread(
                  () -> {
                    if (alive()) {
                      if (next.id.equals(id)) {
                        saveFailed = true;
                        if (status != null) status.setText("保存失败");
                      }
                      error(e);
                    }
                  });
            }
          }
        });
  }

  private void home() {
    study.stop();
    doc = null;
    id = null;
    canvas = null;
    background = null;
    status = null;
    final int current = ++screenVersion;
    frame();
    LinearLayout header = col();
    header.setBackgroundColor(TEAL);
    pad(header, 22, 16);
    root.addView(header);
    TextView kicker = label("PAPERHARBOR   /   本机工作区", 12, 0xffd6ebe0);
    kicker.setLetterSpacing(.1f);
    header.addView(kicker);
    TextView title = label("纸港笔记", 32, 0xffffffff);
    bold(title);
    header.addView(title);
    LinearLayout actions = row();
    pad(actions, 16, 8);
    actions.addView(button("＋ 新建笔记", TEAL, -1, this::newBook));
    add(actions, "导入文件", this::importMenu);
    root.addView(actions);
    EditText search = new EditText(this);
    search.setTextSize(15);
    search.setSingleLine(true);
    search.setHint("搜索标题、页内文字或识别文本");
    pad(search, 18, 4);
    search.setText(filter);
    root.addView(search);
    root.setFocusableInTouchMode(true);
    root.requestFocus();
    ScrollView scroll = new ScrollView(this);
    shelves = col();
    pad(shelves, 16, 8);
    scroll.addView(shelves);
    flex(root, scroll);
    TextView footer = label("只存在此设备 · 卸载前请导出本地备份", 12, DIM);
    pad(footer, 18, 8);
    root.addView(footer);
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int a, int c, int t) {}

          public void afterTextChanged(Editable s) {}

          public void onTextChanged(CharSequence s, int a, int b, int c) {
            filter = s.toString();
            drawShelf();
          }
        });
    shelves.addView(label("正在读取笔记…", 16, DIM));
    disk.execute(
        () -> {
          try {
            List<Meta> list = new ArrayList<>();
            for (String key : store.ids()) {
              JSONObject d = store.load(key);
              Meta m = new Meta();
              m.id = key;
              m.title = d.optString("title");
              JSONArray ps = d.getJSONArray("pages");
              for (int j = 0; j < ps.length(); j++) {
                JSONObject x = ps.getJSONObject(j);
                m.text.add(x.optString("text") + "\n" + x.optString("ocr"));
              }
              list.add(m);
            }
            runOnUiThread(
                () -> {
                  if (alive() && current == screenVersion) {
                    meta.clear();
                    meta.addAll(list);
                    drawShelf();
                  }
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (alive() && current == screenVersion) error(e);
                });
          }
        });
  }

  private void drawShelf() {
    if (doc != null || shelves == null) return;
    shelves.removeAllViews();
    String q = filter.trim().toLowerCase(Locale.ROOT);
    int visible = 0;
    for (Meta m : meta) {
      int matched = -1;
      if (!m.title.toLowerCase(Locale.ROOT).contains(q)) {
        for (int i = 0; i < m.text.size(); i++)
          if (m.text.get(i).toLowerCase(Locale.ROOT).contains(q)) {
            matched = i;
            break;
          }
        if (matched < 0) continue;
      }
      visible++;
      final int target = Math.max(0, matched);
      LinearLayout card = col();
      card.setBackground(bg(CARD, 16));
      pad(card, 18, 13);
      TextView t = label(m.title, 19, TEAL);
      bold(t);
      card.addView(t);
      card.addView(
          label(
              m.text.size() + " 页    ·    " + (matched < 0 ? "本地笔记" : "匹配第 " + (target + 1) + " 页"),
              13,
              DIM));
      card.setOnClickListener(v -> open(m.id, target));
      card.setFocusable(true);
      card.setContentDescription(m.title + "，打开笔记");
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.bottomMargin = dp(8);
      shelves.addView(card, lp);
    }
    if (visible == 0) {
      TextView t = label(q.isEmpty() ? "书架还是空的\n\n新建一本笔记，或导入 PDF 开始批注。" : "没有找到匹配的本地笔记", 17, DIM);
      pad(t, 8, 26);
      shelves.addView(t);
    }
  }

  private EditText field(String text, String hint, boolean multiline) {
    EditText e = new EditText(this);
    e.setFilters(new InputFilter[] {new InputFilter.LengthFilter(multiline ? 32768 : 512)});
    e.setText(text);
    e.setHint(hint);
    e.setSingleLine(!multiline);
    if (multiline) {
      e.setGravity(Gravity.TOP);
      e.setMinLines(3);
      e.setMaxLines(9);
    }
    return e;
  }

  private void input(String title, String initial, boolean multi, Consumer<String> ok) {
    EditText e = field(initial, title, multi);
    LinearLayout l = col();
    pad(l, 20, 4);
    l.addView(e);
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setView(l)
        .setPositiveButton("保存", (d, w) -> ok.accept(e.getText().toString()))
        .setNegativeButton("取消", null)
        .show();
  }

  private void newBook() {
    LinearLayout l = col();
    pad(l, 20, 6);
    EditText name = field("", "笔记名称", false);
    l.addView(name);
    Spinner s = new Spinner(this);
    s.setAdapter(
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"横线纸", "方格纸", "白纸"}));
    l.addView(s);
    new AlertDialog.Builder(this)
        .setTitle("新建笔记")
        .setView(l)
        .setPositiveButton(
            "创建",
            (d, w) -> {
              String t = name.getText().toString().trim(),
                  p = new String[] {"ruled", "grid", "plain"}[s.getSelectedItemPosition()];
              work("创建笔记…", () -> store.create(t.isEmpty() ? "未命名笔记" : t, p), k -> open(k, 0));
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void importMenu() {
    new AlertDialog.Builder(this)
        .setTitle("从本地导入")
        .setItems(
            new String[] {"PDF 文档", "图片（作为单页笔记）", "纸港备份 .phnote"},
            (d, w) -> pick(new int[] {PDF, IMAGE, RESTORE}[w]))
        .show();
  }

  private void open(String key, int at) {
    work(
        "打开笔记…",
        () -> store.load(key),
        d -> {
          ++screenVersion;
          doc = d;
          id = key;
          saveFailed = false;
          index = Math.max(0, Math.min(at, count() - 1));
          editor();
        });
  }

  private void editor() {
    frame();
    LinearLayout top = row();
    top.setBackgroundColor(TEAL);
    pad(top, 8, 5);
    root.addView(top);
    top.addView(button("书架", TEAL, -1, this::onBackPressed));
    LinearLayout heading = col();
    TextView title = label(doc.optString("title"), 18, -1);
    title.setSingleLine(true);
    title.setEllipsize(TextUtils.TruncateAt.END);
    bold(title);
    heading.addView(title);
    status = label(saveFailed ? "保存失败" : "本地笔记", 11, 0xffd5e6d7);
    heading.addView(status);
    flex(top, heading);
    top.addView(button("更多", TEAL, -1, this::menu));
    LinearLayout tools = row();
    pad(tools, 6, 4);
    strip(root, tools);
    String[] vals = {"pen", "marker", "erase", "lasso", "line", "rect"},
        labels = {"笔", "荧光笔", "橡皮", "套索", "直线", "矩形"};
    for (int i = 0; i < vals.length; i++) {
      final String key = vals[i];
      Button b =
          add(
              tools,
              labels[i],
              () -> {
                activeTool = key;
                canvas.tool(key);
                styleTools(tools);
              });
      b.setTag(key);
    }
    styleTools(tools);
    add(tools, "笔色", this::colors);
    add(tools, "粗细", this::widths);
    add(
        tools,
        "撤销",
        () -> {
          if (!canvas.undo()) tell("没有可撤销的笔迹");
        });
    add(
        tools,
        "重做",
        () -> {
          if (!canvas.redo()) tell("没有可重做的笔迹");
        });
    canvas = new InkView(this);
    canvas.setContentDescription("笔记画布");
    flex(root, canvas);
    LinearLayout bottom = row();
    bottom.setGravity(Gravity.CENTER);
    pad(bottom, 4, 5);
    add(bottom, "上一页", () -> go(index - 1));
    Button pg =
        add(
            bottom,
            (index + 1) + " / " + count() + (page().optBoolean("bookmark") ? " ★" : ""),
            this::pageList);
    pg.setContentDescription("页面列表，第 " + (index + 1) + " 页");
    add(bottom, "下一页", () -> go(index + 1));
    add(bottom, "＋ 页", this::addPage);
    root.addView(bottom);
    JSONObject target = page();
    InkView view = canvas;
    String key = id;
    background = null;
    view.tool(activeTool);
    view.color(hue);
    view.width(strokeWidth);
    if (target.optInt("pdf", -1) >= 0) {
      work(
          "载入 PDF 页面…",
          () -> store.background(key, target, 1800),
          bmp -> {
            if (canvas == view) {
              background = bmp;
              view.bind(target, bmp, this::save);
            }
          });
    } else view.bind(target, null, this::save);
  }

  private void styleTools(LinearLayout tools) {
    for (int i = 0; i < tools.getChildCount(); i++) {
      View v = tools.getChildAt(i);
      if (v instanceof Button && v.getTag() != null) {
        boolean active = activeTool.equals(v.getTag());
        v.setBackground(bg(active ? TEAL : CARD, 12));
        ((Button) v).setTextColor(active ? -1 : TEAL);
        v.setSelected(active);
      }
    }
  }

  private void go(int n) {
    if (n >= 0 && n < count() && n != index) {
      index = n;
      editor();
    }
  }

  private void colors() {
    int[] colors = {0xff243c39, 0xff1958aa, 0xff9d3833, 0xff377853, 0xffecb821, 0xffa24f94};
    new AlertDialog.Builder(this)
        .setTitle("选择笔色")
        .setItems(
            new String[] {"墨黑", "海蓝", "砖红", "松绿", "金黄", "紫色"},
            (d, i) -> {
              hue = colors[i];
              canvas.color(hue);
            })
        .show();
  }

  private void widths() {
    new AlertDialog.Builder(this)
        .setTitle("笔迹宽度")
        .setItems(
            new String[] {"细 · 1.5", "常规 · 3", "粗 · 6", "特粗 · 12"},
            (d, i) -> {
              strokeWidth = new float[] {1.5f, 3, 6, 12}[i];
              canvas.width(strokeWidth);
            })
        .show();
  }

  private void addPage() {
    if (count() >= 256) {
      tell("每本最多 256 页");
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("在当前页后插入")
        .setItems(
            new String[] {"横线纸", "方格纸", "白纸"},
            (d, k) -> {
              try {
                JSONObject p = Store.page(612, 792, new String[] {"ruled", "grid", "plain"}[k], -1);
                JSONArray ps = doc.getJSONArray("pages"), out = new JSONArray();
                for (int j = 0; j < ps.length(); j++) {
                  out.put(ps.get(j));
                  if (j == index) out.put(p);
                }
                put(doc, "pages", out);
                index++;
                save();
                editor();
              } catch (Exception e) {
                error(e);
              }
            })
        .show();
  }

  private void pageList() {
    String[] names = new String[count()];
    for (int i = 0; i < count(); i++) {
      JSONObject p = doc.optJSONArray("pages").optJSONObject(i);
      names[i] =
          "第 "
              + (i + 1)
              + " 页"
              + (p.optBoolean("bookmark") ? "   ★" : "")
              + (p.optInt("pdf", -1) >= 0 ? "   · PDF" : "");
    }
    new AlertDialog.Builder(this)
        .setTitle("页面与书签")
        .setItems(names, (d, k) -> go(k))
        .setNeutralButton("整理当前页", (d, w) -> organizePage())
        .show();
  }

  private void organizePage() {
    new AlertDialog.Builder(this)
        .setTitle("整理第 " + (index + 1) + " 页")
        .setItems(
            new String[] {"复制此页", "前移一页", "后移一页", "删除此页"},
            (d, k) -> {
              if (k == 3) {
                if (count() == 1) {
                  tell("请至少保留一页");
                  return;
                }
                new AlertDialog.Builder(this)
                    .setTitle("删除当前页及其批注？")
                    .setMessage("此操作不能撤销。其他页面不受影响。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除页", (x, w) -> reorder(3))
                    .show();
              } else reorder(k);
            })
        .show();
  }

  private void reorder(int op) {
    try {
      int n = count();
      if (op == 1 && index == 0 || op == 2 && index == n - 1) {
        tell("已经到达边界");
        return;
      }
      ArrayList<JSONObject> ps = new ArrayList<>();
      for (int i = 0; i < n; i++) ps.add(doc.getJSONArray("pages").getJSONObject(i));
      switch (op) {
        case 0:
          if (n >= 256) {
            tell("每本最多 256 页");
            return;
          }
          ps.add(index + 1, new JSONObject(page().toString()));
          index++;
          break;
        case 1:
          Collections.swap(ps, index, index - 1);
          index--;
          break;
        case 2:
          Collections.swap(ps, index, index + 1);
          index++;
          break;
        case 3:
          ps.remove(index);
          index = Math.min(index, ps.size() - 1);
          break;
      }
      JSONArray result = new JSONArray();
      for (JSONObject p : ps) result.put(p);
      put(doc, "pages", result);
      save();
      editor();
    } catch (Exception e) {
      error(e);
    }
  }

  private void menu() {
    new AlertDialog.Builder(this)
        .setTitle("笔记工具")
        .setItems(
            new String[] {
              "本页文字",
              "识别本页印刷文字（离线 OCR）",
              "查看本页识别文本",
              "添加 / 移除书签",
              "学习闪卡",
              "录音本",
              "重命名",
              "导出 PDF（扁平化）",
              "备份可编辑笔记 .phnote",
              "删除这本笔记"
            },
            (d, k) -> {
              switch (k) {
                case 0:
                  input(
                      "本页文字",
                      page().optString("text"),
                      true,
                      t -> {
                        put(page(), "text", t);
                        save();
                        canvas.invalidate();
                      });
                  break;
                case 1:
                  recognize();
                  break;
                case 2:
                  input(
                      "识别文本（用于本地搜索）",
                      page().optString("ocr"),
                      true,
                      t -> {
                        put(page(), "ocr", t);
                        save();
                      });
                  break;
                case 3:
                  put(page(), "bookmark", !page().optBoolean("bookmark"));
                  save();
                  editor();
                  break;
                case 4:
                  study.cards(doc, this::saveStudy);
                  break;
                case 5:
                  try {
                    study.audio(doc, store.folder(id), this::saveStudy);
                  } catch (Exception e) {
                    error(e);
                  }
                  break;
                case 6:
                  input(
                      "重命名笔记",
                      doc.optString("title"),
                      false,
                      t -> {
                        if (t.trim().isEmpty()) {
                          tell("名称不能为空");
                          return;
                        }
                        put(doc, "title", t.trim());
                        save();
                        editor();
                      });
                  break;
                case 7:
                  pick(EXPORT);
                  break;
                case 8:
                  pick(BACKUP);
                  break;
                case 9:
                  confirmDelete();
              }
            })
        .show();
  }

  private void recognize() {
    final JSONObject target = page();
    final Bitmap bg = background;
    final String snapshot = target.toString();
    work(
        "在本机识别文字…",
        () -> {
          JSONObject p = new JSONObject(snapshot);
          int w = p.getInt("w"), h = p.getInt("h");
          float s = 1800f / Math.max(w, h);
          Bitmap image =
              Bitmap.createBitmap(
                  Math.max(1, Math.round(w * s)),
                  Math.max(1, Math.round(h * s)),
                  Bitmap.Config.ARGB_8888);
          try {
            Canvas c = new Canvas(image);
            c.scale((float) image.getWidth() / w, (float) image.getHeight() / h);
            InkView.drawPage(c, p, bg);
            return OfflineOcr.recognize(getApplicationContext(), image);
          } finally {
            image.recycle();
          }
        },
        t -> {
          if (t.trim().isEmpty()) {
            tell("未识别到印刷文字，已保留原记录");
            return;
          }
          input(
              "核对识别文本后保存",
              t,
              true,
              v -> {
                put(target, "ocr", v);
                save();
              });
        });
  }

  private void confirmDelete() {
    new AlertDialog.Builder(this)
        .setTitle("删除这本笔记？")
        .setMessage("本机笔迹和录音将一并删除，无法撤销。建议先导出 .phnote 备份。")
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "删除",
            (d, w) -> {
              String key = id;
              work(
                  "删除笔记…",
                  () -> {
                    store.delete(key);
                    return true;
                  },
                  x -> home());
            })
        .show();
  }

  private void pick(int op) {
    boolean write = op == EXPORT || op == BACKUP;
    Intent i = new Intent(write ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
    i.setType(
        op == PDF || op == EXPORT
            ? "application/pdf"
            : op == IMAGE ? "image/*" : op == RESTORE ? "*/*" : "application/octet-stream");
    if (write) {
      exportId = id;
      String base = doc.optString("title", "笔记").replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_");
      i.putExtra(Intent.EXTRA_TITLE, base + (op == EXPORT ? ".pdf" : ".phnote"));
    }
    try {
      startActivityForResult(i, op);
    } catch (ActivityNotFoundException e) {
      error(e);
    }
  }

  private String displayName(Uri u, String fallback) {
    try (Cursor c =
        getContentResolver()
            .query(u, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst() && c.getString(0) != null)
        return c.getString(0).replaceFirst("(?i)\\.(pdf|jpg|jpeg|png|webp|gif|bmp)$", "");
    } catch (RuntimeException ignored) {
    }
    return fallback;
  }

  @Override
  protected void onActivityResult(int op, int result, Intent data) {
    super.onActivityResult(op, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    if (op == PDF || op == RESTORE || op == IMAGE) {
      work(
          "导入本地文件…",
          () -> {
            String title = displayName(uri, "导入笔记");
            if (op == IMAGE) return ImagePage.importFile(this, store, uri, title);
            try (InputStream in = getContentResolver().openInputStream(uri)) {
              if (in == null) throw new IOException("无法读取文件");
              return op == PDF ? store.importPdf(in, title) : store.restore(in);
            }
          },
          k -> open(k, 0));
    } else if (op == EXPORT || op == BACKUP) {
      final String key = exportId;
      final String snapshot = doc != null && Objects.equals(id, key) ? doc.toString() : null;
      work(
          "写入本地文件…",
          () -> {
            if (key == null) throw new IOException("请重新选择要导出的笔记");
            JSONObject dataDoc = snapshot == null ? store.load(key) : new JSONObject(snapshot);
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
              if (out == null) throw new IOException("无法写入目标文件");
              if (op == EXPORT) store.exportPdf(key, dataDoc, out);
              else store.backup(key, dataDoc, out);
            }
            return true;
          },
          x -> tell(op == EXPORT ? "已导出 PDF" : "已备份可编辑笔记"));
    }
  }
}
