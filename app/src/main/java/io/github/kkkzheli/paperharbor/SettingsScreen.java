/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation, not affiliated with Goodnotes.
 */
package io.github.kkkzheli.paperharbor;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local settings: appearance, writing behaviour, default paper, storage and attribution. */
final class SettingsScreen extends android.widget.FrameLayout {
  static final String AUTHOR_URL = "https://github.com/kkkzheli";
  static final String REPO_URL = "https://github.com/kkkzheli/PaperHarbor";

  interface Host {
    void back();
    void restart();
  }

  private final Activity a;
  private final Prefs prefs;
  private final Store store;
  private final Host host;
  private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "settings-usage");
    t.setDaemon(true);
    return t;
  });
  private TextView usage;
  private LinearLayout themeRow;

  SettingsScreen(Activity activity, Prefs prefs, Store store, Host host) {
    super(activity);
    this.a = activity;
    this.prefs = prefs;
    this.store = store;
    this.host = host;
    setBackgroundColor(Ui.BG);
    build();
    measureUsage();
  }

  private void build() {
    LinearLayout body = Ui.column(a);
    addView(body, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    LinearLayout header = Ui.row(a);
    header.setBackgroundColor(Ui.SURFACE);
    header.setPadding(Ui.dp(a, 6), Ui.dp(a, 10), Ui.dp(a, 18), Ui.dp(a, 10));
    header.addView(Ui.icon(a, R.drawable.ic_back, "返回", 44, Ui.INK, () -> host.back()));
    LinearLayout titles = Ui.column(a);
    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    tlp.leftMargin = Ui.dp(a, 6);
    titles.setLayoutParams(tlp);
    titles.addView(Ui.serifTitle(a, "设置", 22, Ui.INK));
    TextView sub = Ui.label(a, "全部资料只在本机", 10, Ui.MUTED);
    sub.setPadding(1, Ui.dp(a, 2), 0, 0);
    titles.addView(sub);
    header.addView(titles);
    body.addView(header);

    View rule = new View(a);
    rule.setBackgroundColor(Ui.LINE);
    body.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(a, .7f))));

    ScrollView scroll = new ScrollView(a);
    scroll.setFillViewport(true);
    scroll.setVerticalScrollBarEnabled(false);
    body.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 40));
    scroll.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    content.addView(Ui.sectionLabel(a, "外观"));
    themeRow = Ui.row(a);
    String[] themeNames = {"浅色", "深色", "跟随系统"};
    int[] themeIcons = {R.drawable.ic_light, R.drawable.ic_dark, R.drawable.ic_auto};
    final int[] themeModes = {Ui.MODE_LIGHT, Ui.MODE_DARK, Ui.MODE_SYSTEM};
    for (int i = 0; i < 3; i++) {
      ImageView glyph = new ImageView(a);
      glyph.setImageResource(themeIcons[i]);
      glyph.setColorFilter(Ui.INK);
      glyph.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 46)));
      glyph.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10));
      LinearLayout tile = Ui.choiceTile(a, glyph, themeNames[i], null);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
      if (i > 0) lp.leftMargin = Ui.dp(a, 8);
      tile.setLayoutParams(lp);
      final int mode = themeModes[i];
      tile.setOnClickListener(v -> {
        if (prefs.theme() == mode) return;
        prefs.setTheme(mode);
        host.restart();
      });
      themeRow.addView(tile);
    }
    content.addView(themeRow);
    syncThemeTiles(themeModes);

    content.addView(Ui.sectionLabel(a, "书写"));
    LinearLayout writing = Ui.column(a);
    writing.setBackground(Ui.round(a, Ui.SURFACE, 18));
    writing.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
    content.addView(writing);
    writing.addView(Ui.switchRow(a, R.drawable.ic_pen, "只用手写笔绘制",
        "手指只用来平移缩放，避免手掌误触", prefs.stylusOnly(),
        checked -> prefs.setStylusOnly(checked)));

    LinearLayout pressureBox = Ui.column(a);
    pressureBox.setPadding(Ui.dp(a, 14), Ui.dp(a, 8), Ui.dp(a, 14), Ui.dp(a, 10));
    LinearLayout pressureHead = Ui.row(a);
    pressureHead.addView(Ui.text(a, "压感灵敏度", 14.5f, Ui.INK));
    LinearLayout.LayoutParams phlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    TextView pressureValue = Ui.text(a, "", 12, Ui.MUTED);
    pressureValue.setGravity(Gravity.END);
    pressureHead.addView(pressureValue, phlp);
    pressureBox.addView(pressureHead);
    SeekBar pressureBar = new SeekBar(a);
    pressureBar.setMax(100);
    pressureBar.setProgressDrawable(Ui.sliderTrack(a, Ui.LINE, Ui.INK));
    pressureBar.setSplitTrack(false);
    pressureBar.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
    pressureBar.setProgress(Math.round((prefs.pressure() - .5f) / 1f * 100));
    pressureValue.setText(Str.of(pressureLabel(prefs.pressure())));
    pressureBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
        float value = .5f + progress / 100f;
        pressureValue.setText(Str.of(pressureLabel(value)));
        if (fromUser) prefs.setPressure(Math.round(value * 20) / 20f);
      }
      @Override public void onStartTrackingTouch(SeekBar bar) { }
      @Override public void onStopTrackingTouch(SeekBar bar) { }
    });
    LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    pblp.topMargin = Ui.dp(a, 4);
    pressureBox.addView(pressureBar, pblp);
    writing.addView(pressureBox);
    writing.addView(Ui.switchRow(a, R.drawable.ic_eraser, "橡皮默认范围擦除",
        "开启后只擦掉笔迹上碰到的一段", prefs.eraseMode() == 1,
        checked -> prefs.setEraseMode(checked ? 1 : 0)));

    content.addView(Ui.sectionLabel(a, "新笔记的纸张"));
    LinearLayout papers = Ui.row(a);
    String[] paperKeys = {"ruled", "grid", "plain"};
    String[] paperNames = {"横线", "方格", "空白"};
    LibraryScreen.TemplatePreview[] previews = new LibraryScreen.TemplatePreview[3];
    for (int i = 0; i < 3; i++) {
      final String key = paperKeys[i];
      LinearLayout cell = Ui.column(a);
      cell.setGravity(Gravity.CENTER);
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
      if (i > 0) clp.leftMargin = Ui.dp(a, 8);
      cell.setLayoutParams(clp);
      LibraryScreen.TemplatePreview preview = new LibraryScreen.TemplatePreview(a, key);
      preview.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 92)));
      preview.setOnClickListener(v -> {
        prefs.setPaper(key);
        for (int j = 0; j < 3; j++) previews[j].setChosen(paperKeys[j].equals(key));
      });
      preview.setChosen(key.equals(prefs.paper()));
      previews[i] = preview;
      cell.addView(preview);
      TextView name = Ui.text(a, paperNames[i], 11.5f, Ui.MUTED);
      name.setGravity(Gravity.CENTER);
      name.setPadding(0, Ui.dp(a, 6), 0, 0);
      cell.addView(name);
      papers.addView(cell);
    }
    content.addView(papers);

    content.addView(Ui.sectionLabel(a, "数据"));
    LinearLayout data = Ui.column(a);
    usage = Ui.text(a, "正在统计本机用量…", 12.5f, Ui.MUTED);
    usage.setPadding(Ui.dp(a, 16), Ui.dp(a, 14), Ui.dp(a, 16), Ui.dp(a, 14));
    data.setBackground(Ui.round(a, Ui.SURFACE, 18));
    data.addView(usage);
    View dataRule = new View(a);
    dataRule.setBackgroundColor(Ui.LINE);
    data.addView(dataRule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(a, .6f))));
    data.addView(Ui.iconRow(a, R.drawable.ic_palette, "清空最近使用颜色", false, () -> {
      prefs.clearRecents();
      toast("已清空最近颜色");
    }));
    content.addView(data);

    content.addView(Ui.sectionLabel(a, "关于"));
    LinearLayout about = Ui.column(a);
    about.setBackground(Ui.round(a, Ui.SURFACE, 18));
    about.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
    about.addView(aboutHeader());
    about.addView(Ui.iconRow(a, R.drawable.ic_person, "作者 kkkzheli", false,
        () -> open(AUTHOR_URL)));
    about.addView(Ui.iconRow(a, R.drawable.ic_link, "项目主页 · GitHub", false,
        () -> open(REPO_URL)));
    about.addView(Ui.iconRow(a, R.drawable.ic_info, "离线与隐私", false, this::showPrivacySheet));
    about.addView(Ui.iconRow(a, R.drawable.ic_notebook, "第三方许可", false, this::showLicenseSheet));
    content.addView(about);

    TextView footer = Ui.text(a, "纸港 1.0 · 由 kkkzheli 设计与实现 · 与 Goodnotes 无从属关系",
        10.5f, Ui.MUTED);
    footer.setGravity(Gravity.CENTER);
    footer.setPadding(0, Ui.dp(a, 22), 0, 0);
    content.addView(footer);
  }

  private void syncThemeTiles(int[] modes) {
    if (themeRow == null) return;
    int current = prefs.theme();
    for (int i = 0; i < themeRow.getChildCount() && i < modes.length; i++) {
      Ui.setChosen(themeRow.getChildAt(i), modes[i] == current);
    }
  }

  private static String pressureLabel(float value) {
    if (value < .8f) return "轻触即满";
    if (value < 1.05f) return "标准";
    if (value < 1.3f) return "偏重";
    return "需要较用力";
  }

  private View aboutHeader() {
    LinearLayout box = Ui.row(a);
    box.setPadding(Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 12));
    ImageView mark = new ImageView(a);
    mark.setImageResource(R.drawable.ic_brand);
    mark.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 52), Ui.dp(a, 52)));
    box.addView(mark);
    LinearLayout labels = Ui.column(a);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    lp.leftMargin = Ui.dp(a, 12);
    labels.setLayoutParams(lp);
    labels.addView(Ui.serifTitle(a, "纸港 · PaperHarbor", 18, Ui.INK));
    labels.addView(Ui.text(a, Str.version(versionName()), 11.5f, Ui.MUTED));
    box.addView(labels);
    return box;
  }

  private String versionName() {
    try {
      return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
    } catch (PackageManager.NameNotFoundException e) {
      return "1.0";
    }
  }

  private void measureUsage() {
    worker.execute(() -> {
      int notes = 0;
      long bytes = 0;
      try {
        for (String id : store.ids()) {
          notes++;
          bytes += size(store.folder(id));
        }
      } catch (Exception ignored) { }
      final int count = notes;
      final long total = bytes;
      a.runOnUiThread(() -> {
        if (usage != null) usage.setText(Str.storage(count, human(total)));
      });
    });
  }

  private static long size(File file) {
    if (file == null || !file.exists()) return 0;
    if (file.isFile()) return file.length();
    File[] children = file.listFiles();
    if (children == null) return 0;
    long total = 0;
    for (File child : children) total += size(child);
    return total;
  }

  static String human(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024f);
    return String.format(Locale.ROOT, "%.1f MB", bytes / (1024f * 1024f));
  }

  private void open(String url) {
    try {
      a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (ActivityNotFoundException e) {
      toast("没有可用的浏览器");
    }
  }

  private void toast(String message) {
    android.widget.Toast.makeText(a, message, android.widget.Toast.LENGTH_SHORT).show();
  }

  private void showPrivacySheet() {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 22), 0, Ui.dp(a, 22), Ui.dp(a, 24));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, "离线与隐私", () -> ref[0].dismiss()));
    String[] points = {
        "应用清单里没有网络权限，安装包里也查不到 INTERNET。",
        "笔记、识别文本、闪卡和录音都在应用私有目录，其他应用读不到。",
        "没有账号、云同步、统计或广告；断网时功能完全一样。",
        "卸载会一并删除本机数据，请先用 .phnote 备份到自己的存储。",
        "只有你点开 GitHub 链接时，才会交给系统浏览器处理。"
    };
    for (String point : points) {
      LinearLayout line = Ui.row(a);
      line.setPadding(0, Ui.dp(a, 7), 0, Ui.dp(a, 7));
      View dot = new View(a);
      dot.setBackground(Ui.circle(Ui.ACCENT));
      dot.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 6), Ui.dp(a, 6)));
      line.addView(dot);
      TextView text = Ui.text(a, point, 13, Ui.MUTED);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
      lp.leftMargin = Ui.dp(a, 10);
      text.setLineSpacing(Ui.dp(a, 2), 1f);
      line.addView(text, lp);
      content.addView(line);
    }
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }

  private void showLicenseSheet() {
    LinearLayout content = Ui.column(a);
    content.setPadding(Ui.dp(a, 22), 0, Ui.dp(a, 22), Ui.dp(a, 24));
    Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(a, "第三方许可", () -> ref[0].dismiss()));
    TextView text = Ui.text(a,
        Str.of("文字识别使用 Tesseract4Android（Apache License 2.0）与其语言数据 chi_sim、eng。")
            + Str.of("语言数据随安装包提供，识别完全在本机完成。")
            + Str.of("完整声明见应用资源 assets/ocr/NOTICE.txt 与仓库 licenses/ 目录。\n\n")
            + Str.of("界面图标与图形均为本项目原创的矢量资源。")
            + Str.of("本项目与 Goodnotes 无从属或授权关系，包含的任何名称仅用于说明兼容范围。"),
        13, Ui.MUTED);
    text.setLineSpacing(Ui.dp(a, 3), 1f);
    content.addView(text);
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }
}
