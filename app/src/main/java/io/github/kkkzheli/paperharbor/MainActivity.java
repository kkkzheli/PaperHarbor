/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation, not affiliated with Goodnotes.
 */
package io.github.kkkzheli.paperharbor;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Hosts the shelf, editor and settings, and owns file-picker intents. Everything stays local. */
public final class MainActivity extends Activity {
  private static final int RQ_PDF = 21, RQ_IMAGE = 22, RQ_RESTORE = 23, RQ_EXPORT = 24, RQ_BACKUP = 25,
      RQ_PAGE = 26, RQ_PLACE_IMAGE = 27, RQ_DOCX_EXPORT = 28, RQ_DOCX_IMPORT = 29;
  private static final ExecutorService disk = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "activity-disk");
    t.setDaemon(true);
    return t;
  });

  private Store store;
  private Prefs prefs;
  private StudyAudio study;
  private FrameLayout root;
  private LibraryScreen library;
  private EditorScreen editor;
  private SettingsScreen settings;
  private View loading;
  private TextView loadingLabel;

  private String exportId, exportTitle;
  private JSONObject exportDoc;
  private boolean exportBackup, exportPng;
  private int exportPage;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    prefs = new Prefs(this);
    Str.install(prefs.lang());
    Ui.install(this, prefs.theme(), prefs.accent());
    Ui.applySystemBars(this);
    getWindow().setBackgroundDrawable(new ColorDrawable(Ui.BG));
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    store = new Store(this);
    study = new StudyAudio(this);
    root = new FrameLayout(this);
    root.setBackgroundColor(Ui.BG);
    setContentView(root);
    boolean reopen = false;
    if (state != null) {
      exportId = state.getString("exportId");
      exportTitle = state.getString("exportTitle");
      exportBackup = state.getBoolean("exportBackup");
      exportPng = state.getBoolean("exportPng");
      exportPage = state.getInt("exportPage", 0);
      String snapshot = state.getString("exportDoc");
      if (snapshot != null) try { exportDoc = new JSONObject(snapshot); } catch (Exception ignored) { }
      String note = state.getString("openNote");
      if (note != null) { open(note, state.getInt("openPage", 0)); reopen = true; }
      else if (state.getBoolean("openSettings")) { showSettings(); reopen = true; }
    }
    if (!reopen) showLibrary();
  }

  @Override protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putString("openNote", editor == null ? null : editor.noteId());
    out.putInt("openPage", editor == null ? 0 : editor.pageIndex());
    out.putBoolean("openSettings", settings != null);
    out.putString("exportId", exportId);
    out.putString("exportTitle", exportTitle);
    out.putBoolean("exportBackup", exportBackup);
    out.putBoolean("exportPng", exportPng);
    out.putInt("exportPage", exportPage);
    if (exportDoc != null) out.putString("exportDoc", exportDoc.toString());
  }

  @Override public void onBackPressed() {
    if (editor != null) editor.requestExit();
    else if (settings != null) showLibrary();
    else super.onBackPressed();
  }

  @Override protected void onStop() {
    super.onStop();
    if (editor != null) editor.onHostStop();
    else study.stop();
  }

  @Override protected void onDestroy() {
    if (library != null) library.dispose();
    if (editor != null) editor.dispose();
    study.stop();
    super.onDestroy();
  }

  @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == 142 && editor != null) {
      editor.onPermissionResult(results.length > 0
          && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED);
    }
  }

  // ---- screens ------------------------------------------------------------------------------

  private void showLibrary() {
    EditorScreen leavingEditor = editor;
    SettingsScreen leavingSettings = settings;
    editor = null;
    settings = null;
    if (library == null) library = new LibraryScreen(this, store, prefs, new LibraryHost());
    else library.refresh();
    root.removeAllViews();
    root.addView(library, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    if (leavingEditor != null) {
      leavingEditor.onHostStop();
      leavingEditor.dispose();
    }
  }

  private void showSettings() {
    settings = new SettingsScreen(this, prefs, store, new SettingsHost());
    root.removeAllViews();
    root.addView(settings, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
  }

  private void open(final String id, final int page) {
    showLoading("正在打开笔记…");
    disk.execute(() -> {
      try {
        final JSONObject doc = store.load(id);
        runOnUiThread(() -> {
          hideLoading();
          settings = null;
          editor = new EditorScreen(this, store, prefs, study, id, doc, page, new EditorHost());
          root.removeAllViews();
          root.addView(editor, new FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        });
      } catch (final Exception e) {
        runOnUiThread(() -> {
          hideLoading();
          showError("无法打开笔记", e);
        });
      }
    });
  }

  private final class LibraryHost implements LibraryScreen.Host {
    @Override public void open(String id, int page) { MainActivity.this.open(id, page); }
    @Override public void importPdf() { pickFile(RQ_PDF, "application/pdf"); }
    @Override public void importImage() { pickFile(RQ_IMAGE, "image/*"); }
    @Override public void restoreBackup() { pickFile(RQ_RESTORE, "*/*"); }
    @Override public void exportPdf(String id) { loadThenExport(id, false); }
    @Override public void backup(String id) { loadThenExport(id, true); }
    @Override public void importDocx() { pickFile(RQ_DOCX_IMPORT, "*/*"); }
    @Override public void settings() { showSettings(); }
  }

  private final class SettingsHost implements SettingsScreen.Host {
    @Override public void back() { showLibrary(); }
    @Override public void restart() {
      // The palette is installed per activity, so a theme change rebuilds the window.
      recreate();
    }
  }

  private final class EditorHost implements EditorScreen.Host {
    @Override public void exit() {
      if (editor != null) editor.onHostStop();
      showLibrary();
    }
    @Override public void deleted() {
      if (editor != null) editor.onHostStop();
      showLibrary();
    }
    @Override public void exportPdf(String id, JSONObject snapshot) { startExport(id, snapshot, false, false, 0); }
    @Override public void exportImage(String id, JSONObject snapshot, int page) {
      savePagePng(id, snapshot, page);
    }
    @Override public void backup(String id, JSONObject snapshot) { startExport(id, snapshot, true, false, 0); }
    @Override public void exportDocx(String id, JSONObject snapshot) { startDocxExport(id, snapshot); }
    @Override public void pickImage() { pickFile(RQ_PLACE_IMAGE, "image/*"); }
  }

  // ---- files --------------------------------------------------------------------------------

  private void loadThenExport(final String id, final boolean backup) {
    showLoading(backup ? "正在准备备份…" : "正在准备导出…");
    disk.execute(() -> {
      try {
        final JSONObject doc = store.load(id);
        runOnUiThread(() -> {
          hideLoading();
          startExport(id, doc, backup, false, 0);
        });
      } catch (final Exception e) {
        runOnUiThread(() -> {
          hideLoading();
          showError("无法读取笔记", e);
        });
      }
    });
  }

  private void startExport(String id, JSONObject doc, boolean backup, boolean png, int page) {
    try {
      exportId = id;
      exportDoc = doc;
      exportBackup = backup;
      exportPng = png;
      exportPage = page;
      exportTitle = doc.optString("title", Str.of("笔记"));
      Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
      intent.addCategory(Intent.CATEGORY_OPENABLE);
      intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
      if (backup) {
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, safeName(exportTitle) + ".phnote");
        startActivityForResult(intent, RQ_BACKUP);
      } else if (png) {
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_TITLE,
            safeName(exportTitle) + "-" + (page + 1) + ".png");
        startActivityForResult(intent, RQ_PAGE);
      } else {
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_TITLE, safeName(exportTitle) + ".pdf");
        startActivityForResult(intent, RQ_EXPORT);
      }
    } catch (ActivityNotFoundException e) {
      showError("没有可用的文件选择器", e);
    }
  }

  /** Writes the notebook to a Word document chosen by the user. */
  private void startDocxExport(String id, JSONObject doc) {
    try {
      exportId = id;
      exportDoc = doc;
      exportTitle = doc.optString("title", Str.of("笔记"));
      Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
      intent.addCategory(Intent.CATEGORY_OPENABLE);
      intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
      intent.setType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
      intent.putExtra(Intent.EXTRA_TITLE, safeName(exportTitle) + ".docx");
      startActivityForResult(intent, RQ_DOCX_EXPORT);
    } catch (ActivityNotFoundException e) {
      showError("没有可用的文件选择器", e);
    }
  }

  private void pickFile(int request, String mime) {
    try {
      Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      intent.addCategory(Intent.CATEGORY_OPENABLE);
      intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
      intent.setType(mime);
      startActivityForResult(intent, request);
    } catch (ActivityNotFoundException e) {
      showError("没有可用的文件选择器", e);
    }
  }

  private static String safeName(String title) {
    String cleaned = title.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").trim();
    // A note imported from "report.docx" keeps that name; drop one known suffix first.
    cleaned = cleaned.replaceFirst("(?i)\\.(phnote|pdf|docx|doc|png|jpe?g)$", "");
    if (cleaned.isEmpty()) cleaned = Str.of("笔记");
    return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
  }

  private String displayName(Uri uri, String fallback) {
    try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst() && c.getString(0) != null) {
        return c.getString(0).replaceFirst("(?i)\\.(pdf|jpg|jpeg|png|webp|gif|bmp|phnote)$", "");
      }
    } catch (RuntimeException ignored) { }
    return fallback;
  }

  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    final Uri uri = data.getData();
    if (request == RQ_DOCX_IMPORT) {
      showLoading("正在导入 Word 文档…");
      final String title = displayName(uri, "Word 文档");
      disk.execute(() -> {
        try {
          final String id;
          try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("无法读取文件");
            id = store.createFromDoc(DocxImport.read(in, title));
          }
          runOnUiThread(() -> {
            hideLoading();
            open(id, 0);
          });
        } catch (final Exception e) {
          runOnUiThread(() -> {
            hideLoading();
            showError("导入失败", e);
          });
        }
      });
      return;
    }
    if (request == RQ_DOCX_EXPORT) {
      final String id = exportId;
      final JSONObject doc = exportDoc;
      showLoading("正在写入 Word 文档…");
      disk.execute(() -> {
        try {
          if (id == null || doc == null) throw new IOException("没有可导出的笔记");
          try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("无法写入目标文件");
            DocxExport.write(store, id, doc, out);
          }
          runOnUiThread(() -> {
            hideLoading();
            toast("已导出 Word 文档");
          });
        } catch (final Exception e) {
          runOnUiThread(() -> {
            hideLoading();
            showError("导出失败", e);
          });
        }
      });
      return;
    }
    if (request == RQ_PLACE_IMAGE) {
      if (editor != null) editor.insertImage(uri);
      return;
    }
    if (request == RQ_PDF || request == RQ_IMAGE || request == RQ_RESTORE) {
      showLoading(request == RQ_RESTORE ? "正在恢复备份…" : request == RQ_IMAGE ? "正在导入图片…" : "正在导入 PDF…");
      disk.execute(() -> {
        try {
          final String id;
          if (request == RQ_IMAGE) {
            id = ImagePage.importFile(this, store, uri, displayName(uri, "图片"));
          } else {
            String title = displayName(uri, request == RQ_RESTORE ? "恢复的笔记" : "导入的文档");
            try (InputStream in = getContentResolver().openInputStream(uri)) {
              if (in == null) throw new IOException("无法读取文件");
              id = request == RQ_RESTORE ? store.restore(in) : store.importPdf(in, title);
            }
          }
          runOnUiThread(() -> {
            hideLoading();
            open(id, 0);
          });
        } catch (final Exception e) {
          runOnUiThread(() -> {
            hideLoading();
            showError(request == RQ_RESTORE ? "恢复备份失败" : "导入失败", e);
          });
        }
      });
    } else if (request == RQ_EXPORT || request == RQ_BACKUP || request == RQ_PAGE) {
      final String id = exportId;
      final JSONObject doc = exportDoc;
      final boolean backup = request == RQ_BACKUP || exportBackup;
      final boolean png = request == RQ_PAGE || exportPng;
      final int page = exportPage;
      showLoading(backup ? "正在写入备份…" : png ? "正在渲染页面…" : "正在写入 PDF…");
      disk.execute(() -> {
        try {
          if (id == null || doc == null) throw new IOException("没有可导出的笔记");
          try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("无法写入目标文件");
            if (backup) store.backup(id, doc, out);
            else if (png) writePagePng(id, doc, page, out);
            else store.exportPdf(id, doc, out);
          }
          runOnUiThread(() -> {
            hideLoading();
            toast(backup ? "已备份可编辑笔记" : png ? "已导出页面图片" : "已导出 PDF");
          });
        } catch (final Exception e) {
          runOnUiThread(() -> {
            hideLoading();
            showError(backup ? "备份失败" : "导出失败", e);
          });
        }
      });
    }
  }

  /**
   * Saves the rendered page straight into the gallery. On Android 10 and newer this needs no
   * permission and skips the document picker entirely; if the media store refuses, the document
   * picker is used instead so the action never silently fails.
   */
  private void savePagePng(String id, JSONObject doc, int page) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      startExport(id, doc, false, true, page);
      return;
    }
    final String name = safeName(doc.optString("title", Str.of("笔记"))) + "-" + (page + 1) + ".png";
    showLoading(Str.of("正在保存图片…"));
    disk.execute(() -> {
      Uri uri = null;
      boolean saved = false;
      try {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + "/PaperHarbor");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("无法创建图片");
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
          if (out == null) throw new IOException("无法写入图片");
          writePagePng(id, doc, page, out);
        }
        ContentValues ready = new ContentValues();
        ready.put(MediaStore.Images.Media.IS_PENDING, 0);
        getContentResolver().update(uri, ready, null, null);
        saved = true;
      } catch (Exception ignored) {
        if (uri != null) {
          try { getContentResolver().delete(uri, null, null); } catch (Exception ignored2) { }
        }
      }
      final boolean ok = saved;
      runOnUiThread(() -> {
        hideLoading();
        if (ok) toast(Str.of("已保存到相册 Pictures/PaperHarbor"));
        else startExport(id, doc, false, true, page);
      });
    });
  }

  /** Renders one page to PNG at twice its stored page size. */
  private void writePagePng(String id, JSONObject doc, int pageIndex, OutputStream out) throws Exception {
    JSONObject page = doc.getJSONArray("pages").getJSONObject(
        Math.max(0, Math.min(pageIndex, doc.getJSONArray("pages").length() - 1)));
    int w = Math.max(1, page.optInt("w", 612)), h = Math.max(1, page.optInt("h", 792));
    Bitmap background = page.optInt("pdf", -1) >= 0 ? store.background(id, page, 2400) : null;
    Bitmap image = Bitmap.createBitmap(w * 2, h * 2, Bitmap.Config.ARGB_8888);
    try {
      Canvas canvas = new Canvas(image);
      canvas.scale(2f, 2f);
      InkView.drawPage(canvas, page, background, store, id);
      if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("无法编码 PNG");
    } finally {
      image.recycle();
      if (background != null) background.recycle();
    }
  }

  // ---- feedback -----------------------------------------------------------------------------

  private void showLoading(String message) {
    if (loading == null) {
      LinearLayout box = Ui.column(this);
      box.setGravity(Gravity.CENTER);
      box.setBackgroundColor(0xc017322c);
      ProgressBar bar = new ProgressBar(this);
      bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.WHITE));
      box.addView(bar);
      loadingLabel = Ui.text(this, "", 13, Ui.WHITE);
      loadingLabel.setPadding(0, Ui.dp(this, 12), 0, 0);
      box.addView(loadingLabel);
      loading = box;
      loading.setOnClickListener(null);
    }
    loadingLabel.setText(message);
    if (loading.getParent() == null) root.addView(loading, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
  }

  private void hideLoading() {
    if (loading != null && loading.getParent() != null) root.removeView(loading);
  }

  private void toast(String message) {
    Toast.makeText(this, Str.of(message), Toast.LENGTH_SHORT).show();
  }

  private void showError(String title, Exception e) {
    LinearLayout content = Ui.column(this);
    content.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 22), Ui.dp(this, 22));
    final Dialog[] ref = new Dialog[1];
    content.addView(Ui.sheetHeader(this, title, () -> ref[0].dismiss()));
    String message = e == null ? "未知错误"
        : (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    TextView text = Ui.text(this, message, 14, Ui.MUTED);
    text.setPadding(0, Ui.dp(this, 10), 0, 0);
    content.addView(text);
    LinearLayout footer = Ui.row(this);
    footer.setGravity(Gravity.END);
    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    flp.topMargin = Ui.dp(this, 18);
    footer.addView(Ui.pillButton(this, 0, "知道了", Ui.INK, Ui.ON_INK, () -> ref[0].dismiss()));
    content.addView(footer, flp);
    Dialog d = Ui.sheet(this, content, true);
    ref[0] = d;
    d.show();
  }
}
