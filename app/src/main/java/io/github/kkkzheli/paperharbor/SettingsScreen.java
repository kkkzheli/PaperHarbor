/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation.
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

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local settings: appearance, language, writing, new-note defaults, storage and attribution. */
final class SettingsScreen extends android.widget.FrameLayout {
  static final String AUTHOR_URL = "https://github.com/kkkzheli";
  static final String REPO_URL = "https://github.com/kkkzheli/PaperHarbor";
  static final String RELEASES_URL = "https://github.com/kkkzheli/PaperHarbor/releases";

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
  private LinearLayout themeRow, languageRow, accentRow;

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

    // ---- appearance ----
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

    LinearLayout accentBox = Ui.column(a);
    accentBox.setBackground(Ui.round(a, Ui.SURFACE, 18));
    accentBox.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 14));
    LinearLayout.LayoutParams ablp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    ablp.topMargin = Ui.dp(a, 10);
    content.addView(accentBox, ablp);
    accentBox.addView(Ui.text(a, "强调色", 14.5f, Ui.INK));
    accentRow = Ui.row(a);
    accentRow.setPadding(0, Ui.dp(a, 10), 0, 0);
    accentBox.addView(accentRow);
    for (int i = 0; i < Ui.ACCENTS.length; i++) {
      final int index = i;
      Ui.ColorChip chip = new Ui.ColorChip(a);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 34), Ui.dp(a, 34));
      if (i > 0) lp.leftMargin = Ui.dp(a, 10);
      chip.setLayoutParams(lp);
      chip.setColor(Ui.ACCENTS[i][Ui.dark ? 2 : 0]);
      chip.setSelectedChip(i == prefs.accent());
      chip.setContentDescription(Str.of("强调色"));
      chip.setOnClickListener(v -> {
        if (prefs.accent() == index) return;
        prefs.setAccent(index);
        host.restart();
      });
      accentRow.addView(chip);
    }

    // ---- language ----
    content.addView(Ui.sectionLabel(a, "语言"));
    languageRow = Ui.row(a);
    final int[] langs = {0, 1, 2};
    String[] langNames = {"跟随系统", "简体中文", "English"};
    for (int i = 0; i < 3; i++) {
      LinearLayout tile = Ui.choiceTile(a, null, langNames[i], null);
      tile.setPadding(Ui.dp(a, 10), Ui.dp(a, 20), Ui.dp(a, 10), Ui.dp(a, 20));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
      if (i > 0) lp.leftMargin = Ui.dp(a, 8);
      tile.setLayoutParams(lp);
      final int value = langs[i];
      tile.setOnClickListener(v -> {
        if (prefs.lang() == value) return;
        prefs.setLang(value);
        host.restart();
      });
      languageRow.addView(tile);
    }
    content.addView(languageRow);
    syncLanguageTiles(langs);

    // ---- writing ----
    content.addView(Ui.sectionLabel(a, "书写"));
    LinearLayout writing = Ui.column(a);
    writing.setBackground(Ui.round(a, Ui.SURFACE, 18));
    writing.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
    content.addView(writing);
    writing.addView(Ui.switchRow(a, R.drawable.ic_pen, "只用手写笔绘制",
        "手指只用来平移缩放，避免手掌误触", prefs.stylusOnly(),
        checked -> prefs.setStylusOnly(checked)));
    writing.addView(sliderRow("压感灵敏度", .5f, 1.5f, prefs.pressure(),
        value -> Str.of(pressureLabel(value)), prefs::setPressure));
    writing.addView(sliderRow("荧光笔不透明度", .15f, 1f, prefs.markerAlpha(),
        value -> Math.round(value * 100) + "%", prefs::setMarkerAlpha));
    writing.addView(Ui.switchRow(a, R.drawable.ic_eraser, "橡皮默认范围擦除",
        "开启后只擦掉笔迹上碰到的一段", prefs.eraseMode() == 1,
        checked -> prefs.setEraseMode(checked ? 1 : 0)));

    // ---- new note defaults ----
    content.addView(Ui.sectionLabel(a, "新笔记的样式"));
    LinearLayout papers = Ui.row(a);
    String[] paperKeys = InkPaper.PATTERNS;
    final LibraryScreen.TemplatePreview[] previews = new LibraryScreen.TemplatePreview[paperKeys.length];
    for (int i = 0; i < paperKeys.length; i++) {
      final String key = paperKeys[i];
      LinearLayout cell = Ui.column(a);
      cell.setGravity(Gravity.CENTER);
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
      if (i > 0) clp.leftMargin = Ui.dp(a, 6);
      cell.setLayoutParams(clp);
      LibraryScreen.TemplatePreview preview = new LibraryScreen.TemplatePreview(a, key);
      preview.setTint(prefs.paperTint());
      preview.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 84)));
      preview.setOnClickListener(v -> {
        prefs.setPaper(key);
        for (int j = 0; j < previews.length; j++) previews[j].setChosen(paperKeys[j].equals(key));
      });
      preview.setChosen(key.equals(prefs.paper()));
      previews[i] = preview;
      cell.addView(preview);
      TextView name = Ui.text(a, InkPaper.PATTERN_NAMES[i], 10.5f, Ui.MUTED);
      name.setGravity(Gravity.CENTER);
      name.setPadding(0, Ui.dp(a, 5), 0, 0);
      cell.addView(name);
      papers.addView(cell);
    }
    content.addView(papers);

    LinearLayout tintRow = Ui.row(a);
    tintRow.setGravity(Gravity.CENTER);
    tintRow.setPadding(0, Ui.dp(a, 12), 0, 0);
    content.addView(tintRow);
    for (int i = 0; i < InkPaper.TINTS.length; i++) {
      final int value = InkPaper.TINTS[i];
      Ui.ColorChip chip = new Ui.ColorChip(a);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 36), Ui.dp(a, 36));
      if (i > 0) lp.leftMargin = Ui.dp(a, 10);
      chip.setLayoutParams(lp);
      chip.setColor(value);
      chip.setSelectedChip(value == prefs.paperTint());
      chip.setContentDescription(Str.of("纸张颜色"));
      chip.setOnClickListener(v -> {
        prefs.setPaperTint(value);
        for (int j = 0; j < tintRow.getChildCount(); j++)
          ((Ui.ColorChip) tintRow.getChildAt(j)).setSelectedChip(tintRow.getChildAt(j) == chip);
        for (LibraryScreen.TemplatePreview preview : previews) preview.setTint(value);
      });
      tintRow.addView(chip);
    }

    LinearLayout coverBox = Ui.column(a);
    coverBox.setBackground(Ui.round(a, Ui.SURFACE, 18));
    coverBox.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 14));
    LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    cblp.topMargin = Ui.dp(a, 12);
    content.addView(coverBox, cblp);
    coverBox.addView(Ui.text(a, "默认封面", 14.5f, Ui.INK));
    LinearLayout coverStyles = Ui.row(a);
    LinearLayout.LayoutParams cslp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    cslp.topMargin = Ui.dp(a, 10);
    coverBox.addView(coverStyles, cslp);
    final CoverArt[] samples = new CoverArt[CoverArt.STYLES.length];
    final LinearLayout[] styleTiles = new LinearLayout[CoverArt.STYLES.length];
    for (int i = 0; i < CoverArt.STYLES.length; i++) {
      final String key = CoverArt.STYLES[i];
      LinearLayout cell = Ui.column(a);
      cell.setGravity(Gravity.CENTER);
      CoverArt sample = new CoverArt(a);
      sample.setCover(key, prefs.coverColor());
      sample.setTitle(Str.of("纸港"));
      sample.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 62)));
      cell.addView(sample);
      LinearLayout tile = Ui.choiceTile(a, cell, CoverArt.STYLE_NAMES[i], null);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
      if (i > 0) lp.leftMargin = Ui.dp(a, 6);
      tile.setLayoutParams(lp);
      final int index = i;
      tile.setOnClickListener(v -> {
        prefs.setCoverStyle(key);
        for (int j = 0; j < styleTiles.length; j++) Ui.setChosen(styleTiles[j], j == index);
      });
      samples[i] = sample;
      styleTiles[i] = tile;
      coverStyles.addView(tile);
    }
    for (int i = 0; i < styleTiles.length; i++)
      Ui.setChosen(styleTiles[i], CoverArt.STYLES[i].equals(prefs.coverStyle()));
    LinearLayout coverColors = Ui.row(a);
    coverColors.setGravity(Gravity.CENTER);
    coverColors.setPadding(0, Ui.dp(a, 12), 0, 0);
    coverBox.addView(coverColors);
    for (int i = 0; i < CoverArt.PALETTE.length; i++) {
      final int value = CoverArt.PALETTE[i];
      Ui.ColorChip chip = new Ui.ColorChip(a);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 30), Ui.dp(a, 30));
      if (i > 0) lp.leftMargin = Ui.dp(a, 8);
      chip.setLayoutParams(lp);
      chip.setColor(value);
      chip.setSelectedChip(value == prefs.coverColor());
      chip.setContentDescription(Str.of("封面颜色"));
      chip.setOnClickListener(v -> {
        prefs.setCoverColor(value);
        for (int j = 0; j < coverColors.getChildCount(); j++)
          ((Ui.ColorChip) coverColors.getChildAt(j)).setSelectedChip(coverColors.getChildAt(j) == chip);
        for (int j = 0; j < samples.length; j++) samples[j].setCover(CoverArt.STYLES[j], value);
      });
      coverColors.addView(chip);
    }

    // ---- data ----
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
    data.addView(Ui.iconRow(a, R.drawable.ic_image, "清空缩略图缓存", false, () -> {
      Thumbnails.clear();
      PageImage.clear();
      toast("缓存已清空");
    }));
    content.addView(data);

    // ---- about ----
    content.addView(Ui.sectionLabel(a, "关于"));
    LinearLayout about = Ui.column(a);
    about.setBackground(Ui.round(a, Ui.SURFACE, 18));
    about.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
    about.addView(aboutHeader());
    TextView intro = Ui.text(a,
        "纸港由 kkkzheli 独立设计与实现：不登录、不联网、不上传，笔迹、文字、闪卡和录音只保存在这台设备上。",
        12.5f, Ui.MUTED);
    intro.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), Ui.dp(a, 14));
    intro.setLineSpacing(Ui.dp(a, 3), 1f);
    about.addView(intro);
    about.addView(Ui.iconRow(a, R.drawable.ic_person, "作者 kkkzheli", false,
        () -> open(AUTHOR_URL)));
    about.addView(Ui.iconRow(a, R.drawable.ic_link, "项目主页 · GitHub", false,
        () -> open(REPO_URL)));
    about.addView(Ui.iconRow(a, R.drawable.ic_export, "版本发布 · GitHub", false,
        () -> open(RELEASES_URL)));
    about.addView(Ui.iconRow(a, R.drawable.ic_info, "离线与隐私", false, this::showPrivacySheet));
    about.addView(Ui.iconRow(a, R.drawable.ic_notebook, "第三方许可", false, this::showLicenseSheet));
    content.addView(about);

    TextView footer = Ui.text(a, Str.of("纸港") + " v" + versionName() + " · kkkzheli", 10.5f, Ui.MUTED);
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

  private void syncLanguageTiles(int[] values) {
    if (languageRow == null) return;
    int current = prefs.lang();
    for (int i = 0; i < languageRow.getChildCount() && i < values.length; i++) {
      Ui.setChosen(languageRow.getChildAt(i), values[i] == current);
    }
  }

  /**
   * A title, its current value and a slider on one card row. The label is rebuilt on every change,
   * so a formatter can show a percentage or a named step.
   */
  private LinearLayout sliderRow(String title, float min, float max, float value,
                                 java.util.function.Function<Float, String> label,
                                 java.util.function.Consumer<Float> onChange) {
    LinearLayout box = Ui.column(a);
    box.setPadding(Ui.dp(a, 14), Ui.dp(a, 8), Ui.dp(a, 14), Ui.dp(a, 10));
    LinearLayout head = Ui.row(a);
    head.addView(Ui.text(a, title, 14.5f, Ui.INK));
    LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    TextView valueView = Ui.text(a, label.apply(value), 12, Ui.MUTED);
    valueView.setGravity(Gravity.END);
    head.addView(valueView, hlp);
    box.addView(head);
    SeekBar bar = new SeekBar(a);
    bar.setMax(1000);
    bar.setProgressDrawable(Ui.sliderTrack(a, Ui.LINE, Ui.INK));
    bar.setSplitTrack(false);
    bar.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
    bar.setProgress(Math.round((value - min) / (max - min) * 1000));
    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    blp.topMargin = Ui.dp(a, 2);
    box.addView(bar, blp);
    bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        float current = min + (max - min) * progress / 1000f;
        valueView.setText(label.apply(current));
        if (fromUser) onChange.accept(current);
      }
      @Override public void onStartTrackingTouch(SeekBar seekBar) { }
      @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    });
    return box;
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
    labels.addView(Ui.text(a, "v" + versionName() + " · kkkzheli", 11.5f, Ui.MUTED));
    box.addView(labels);
    return box;
  }

  private String versionName() {
    try {
      return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
    } catch (PackageManager.NameNotFoundException e) {
      return "1.1.0";
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

  static String human(long bytes) { return Str.bytes(bytes); }

  private void open(String url) {
    try {
      a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (ActivityNotFoundException e) {
      toast("没有可用的浏览器");
    }
  }

  private void toast(String message) {
    android.widget.Toast.makeText(a, Str.of(message), android.widget.Toast.LENGTH_SHORT).show();
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
            + Str.of("界面图标与图形均为本项目原创的矢量资源。"),
        13, Ui.MUTED);
    text.setLineSpacing(Ui.dp(a, 3), 1f);
    content.addView(text);
    Dialog d = Ui.sheet(a, content, true);
    ref[0] = d;
    d.show();
  }
}
