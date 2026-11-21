package io.github.kkkzheli.paperharbor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Offline recognition of upright, printed Simplified Chinese and English pages. */
public final class OfflineOcr {
  private static final int MAX_EDGE = 3200;
  private static final int MAX_PIXELS = 5_000_000;

  private OfflineOcr() {}

  /**
   * Blocking; call on a worker thread. The caller owns the bitmap. Blank pages return an empty
   * string; missing/corrupt resources and engine failures are errors, not recognition results.
   * Serialize calls to bound the number of native engines and large temporary images in memory.
   */
  public static synchronized String recognize(Context ctx, Bitmap page) throws Exception {
    if (page == null || page.isRecycled()) throw new IllegalArgumentException("无效的页面位图");
    File root = new File(ctx.getNoBackupFilesDir(), "offline-ocr");
    File data = new File(root, "tessdata");
    Files.createDirectories(data.toPath());
    ensureModel(ctx, data, "chi_sim", 2469156,
        "a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730");
    ensureModel(ctx, data, "eng", 4113088,
        "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2");

    TessBaseAPI engine = null;
    try {
      engine = new TessBaseAPI();
      if (!engine.init(root.getAbsolutePath(), "chi_sim+eng", TessBaseAPI.OEM_LSTM_ONLY)) {
        throw new IOException("无法初始化离线 OCR 模型");
      }
      engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO); // No orientation-model dependency.
      Bitmap input = prepareBitmap(page);
      try {
        engine.setImage(input); // Copies into a native Pix; no bitmap is retained.
      } finally {
        if (input != page) input.recycle();
      }
      String text = engine.getUTF8Text();
      if (text == null) throw new IOException("离线 OCR 引擎未返回结果");
      return text;
    } catch (LinkageError e) {
      throw new IOException("无法加载离线 OCR 原生库", e);
    } finally {
      if (engine != null) engine.recycle();
    }
  }

  /** The JNI bitmap reader requires software ARGB_8888. Composite transparent pages onto paper. */
  private static Bitmap prepareBitmap(Bitmap page) throws IOException {
    int w = page.getWidth(), h = page.getHeight();
    double scale = Math.min(1d, Math.min((double) MAX_EDGE / Math.max(w, h),
        Math.sqrt(MAX_PIXELS / ((double) w * h))));
    if (scale == 1d && !page.hasAlpha() && page.getConfig() == Bitmap.Config.ARGB_8888) return page;
    int width = Math.max(1, (int) (w * scale));
    int height = Math.max(1, (int) (h * scale));
    Bitmap source = page;
    if (page.getConfig() == Bitmap.Config.HARDWARE) {
      // A software canvas cannot read hardware pixels. Never make an unbounded CPU-side copy.
      if (scale < 1d) throw new IOException("请先将页面渲染为较小的软件位图");
      source = page.copy(Bitmap.Config.ARGB_8888, false);
      if (source == null) throw new IOException("无法读取硬件位图");
    }
    try {
      Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      try {
        Canvas c = new Canvas(result);
        c.drawColor(Color.WHITE);
        c.drawBitmap(source, null, new Rect(0, 0, width, height), new Paint(Paint.FILTER_BITMAP_FLAG));
        return result;
      } catch (RuntimeException | Error e) {
        result.recycle();
        throw e;
      }
    } finally {
      if (source != page) source.recycle();
    }
  }

  private static void ensureModel(Context ctx, File dir, String lang, int size, String hash)
      throws IOException {
    String name = lang + ".traineddata";
    File target = new File(dir, name);
    if (target.isFile() && target.length() == size) {
      try (InputStream in = new FileInputStream(target)) {
        if (hash.equals(hashAndCopy(in, null, size))) return;
      }
    }
    File temp = File.createTempFile(lang + "-", ".tmp", dir);
    try {
      try (InputStream in = ctx.getAssets().open("ocr/" + name);
          FileOutputStream out = new FileOutputStream(temp)) {
        if (!hash.equals(hashAndCopy(in, out, size))) throw new IOException("OCR 模型校验失败: " + lang);
        out.getFD().sync();
      }
      Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp.toPath());
    }
  }

  private static String hashAndCopy(InputStream in, OutputStream out, int expected) throws IOException {
    MessageDigest sha;
    try {
      sha = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e); // Mandatory algorithm on Android.
    }
    byte[] buffer = new byte[32 * 1024];
    int total = 0, n;
    while ((n = in.read(buffer)) != -1) {
      if (n > expected - total) throw new IOException("OCR 模型超过预期大小");
      sha.update(buffer, 0, n);
      if (out != null) out.write(buffer, 0, n);
      total += n;
    }
    StringBuilder hex = new StringBuilder(64);
    for (byte b : sha.digest()) {
      hex.append(Character.forDigit((b & 255) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
    }
    return hex.toString();
  }
}
