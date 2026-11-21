package app.paperharbor.notes;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
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

/** Hosts the shelf and editor, and owns file-picker intents. Everything stays on this device. */
public final class MainActivity extends Activity {
  private static final int RQ_PDF = 21, RQ_IMAGE = 22, RQ_RESTORE = 23, RQ_EXPORT = 24, RQ_BACKUP = 25;
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
  private View loading;
  private TextView loadingLabel;

  private String exportId, exportTitle;
  private JSONObject exportDoc;
  private boolean exportBackup;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    Ui.applySystemBars(this);
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    store = new Store(this);
    prefs = new Prefs(this);
    study = new StudyAudio(this);
    root = new FrameLayout(this);
    root.setBackgroundColor(Ui.BG);
    setContentView(root);
    if (state != null) {
      exportId = state.getString("exportId");
      exportTitle = state.getString("exportTitle");
      exportBackup = state.getBoolean("exportBackup");
      String snapshot = state.getString("exportDoc");
      if (snapshot != null) try { exportDoc = new JSONObject(snapshot); } catch (Exception ignored) { }
      String note = state.getString("openNote");
      int page = state.getInt("openPage", 0);
      if (note != null) open(note, page); else showLibrary();
    } else {
      showLibrary();
    }
  }

  @Override protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putString("openNote", editor == null ? null : editor.noteId());
    out.putInt("openPage", editor == null ? 0 : editor.pageIndex());
    out.putString("exportId", exportId);
    out.putString("exportTitle", exportTitle);
    out.putBoolean("exportBackup", exportBackup);
    if (exportDoc != null) out.putString("exportDoc", exportDoc.toString());
  }

  @Override public void onBackPressed() {
    if (editor != null) editor.requestExit();
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
    EditorScreen leaving = editor;
    editor = null;
    if (library == null) library = new LibraryScreen(this, store, new LibraryHost());
    else library.refresh();
    root.removeAllViews();
    root.addView(library, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    if (leaving != null) {
      leaving.onHostStop();
      leaving.dispose();
    }
  }

  private void open(final String id, final int page) {
    showLoading("正在打开笔记…");
    disk.execute(() -> {
      try {
        final JSONObject doc = store.load(id);
        runOnUiThread(() -> {
          hideLoading();
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
    @Override public void importPdf() { pickFile(RQ_PDF, "application/pdf", false); }
    @Override public void importImage() { pickFile(RQ_IMAGE, "image/*", false); }
    @Override public void restoreBackup() { pickFile(RQ_RESTORE, "*/*", false); }
    @Override public void exportPdf(String id) { loadThenExport(id, false); }
    @Override public void backup(String id) { loadThenExport(id, true); }
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
    @Override public void exportPdf(String id, JSONObject snapshot) { startExport(id, snapshot, false); }
    @Override public void backup(String id, JSONObject snapshot) { startExport(id, snapshot, true); }
  }

  // ---- files --------------------------------------------------------------------------------

  private void loadThenExport(final String id, final boolean backup) {
    showLoading(backup ? "正在准备备份…" : "正在准备导出…");
    disk.execute(() -> {
      try {
        final JSONObject doc = store.load(id);
        runOnUiThread(() -> {
          hideLoading();
          startExport(id, doc, backup);
        });
      } catch (final Exception e) {
        runOnUiThread(() -> {
          hideLoading();
          showError("无法读取笔记", e);
        });
      }
    });
  }

  private void startExport(String id, JSONObject doc, boolean backup) {
    try {
      exportId = id;
      exportDoc = doc;
      exportBackup = backup;
      exportTitle = doc.optString("title", "笔记");
      Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
      intent.addCategory(Intent.CATEGORY_OPENABLE);
      intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
      intent.setType(backup ? "application/octet-stream" : "application/pdf");
      intent.putExtra(Intent.EXTRA_TITLE, safeName(exportTitle) + (backup ? ".phnote" : ".pdf"));
      startActivityForResult(intent, backup ? RQ_BACKUP : RQ_EXPORT);
    } catch (ActivityNotFoundException e) {
      showError("没有可用的文件选择器", e);
    }
  }

  private void pickFile(int request, String mime, boolean write) {
    try {
      Intent intent = new Intent(write ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
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
    if (cleaned.isEmpty()) cleaned = "笔记";
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
    } else if (request == RQ_EXPORT || request == RQ_BACKUP) {
      final String id = exportId;
      final JSONObject doc = exportDoc;
      final boolean backup = request == RQ_BACKUP || exportBackup;
      showLoading(backup ? "正在写入备份…" : "正在写入 PDF…");
      disk.execute(() -> {
        try {
          if (id == null || doc == null) throw new IOException("没有可导出的笔记");
          try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("无法写入目标文件");
            if (backup) store.backup(id, doc, out);
            else store.exportPdf(id, doc, out);
          }
          runOnUiThread(() -> {
            hideLoading();
            toast(backup ? "已备份可编辑笔记" : "已导出 PDF");
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
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
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
    footer.addView(Ui.pillButton(this, 0, "知道了", Ui.INK, Ui.WHITE, () -> ref[0].dismiss()));
    content.addView(footer, flp);
    Dialog d = Ui.sheet(this, content, true);
    ref[0] = d;
    d.show();
  }
}
