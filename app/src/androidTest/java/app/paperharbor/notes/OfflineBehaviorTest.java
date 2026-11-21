package app.paperharbor.notes;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.test.InstrumentationTestCase;
import android.view.MotionEvent;
import java.io.*;
import org.json.*;

public final class OfflineBehaviorTest extends InstrumentationTestCase {
  public void testNoNetworkPermissions() throws Exception {
    Context c = getInstrumentation().getTargetContext();
    for (String permission :
        new String[] {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"})
      assertEquals(
          PackageManager.PERMISSION_DENIED,
          c.getPackageManager().checkPermission(permission, c.getPackageName()));
  }

  public void testInkSaveRestoreAndExport() throws Exception {
    Context c = getInstrumentation().getTargetContext();
    Store s = new Store(c);
    String id = s.create("Synthetic ink test", "plain"), restored = null;
    try {
      JSONObject note = s.load(id), page = note.getJSONArray("pages").getJSONObject(0);
      final int[] callbacks = {0};
      getInstrumentation()
          .runOnMainSync(
              () -> {
                InkView ink = new InkView(c);
                ink.layout(0, 0, 612, 792);
                ink.bind(page, null, () -> callbacks[0]++);
                ink.tool("pen");
                ink.width(4);
                long t = android.os.SystemClock.uptimeMillis();
                for (int i = 0; i < 3; i++) {
                  MotionEvent e =
                      MotionEvent.obtain(
                          t,
                          t + i * 20,
                          i == 0
                              ? MotionEvent.ACTION_DOWN
                              : i == 1 ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_UP,
                          200 + i * 40,
                          300 + i * 15,
                          0);
                  ink.onTouchEvent(e);
                  e.recycle();
                }
                assertTrue(page.optJSONArray("ink").length() > 0);
                assertTrue(ink.undo());
                assertEquals(0, page.optJSONArray("ink").length());
                assertTrue(ink.redo());
              });
      assertTrue(callbacks[0] >= 3);
      s.save(id, note);
      assertEquals(
          page.getJSONArray("ink").toString(),
          s.load(id).getJSONArray("pages").getJSONObject(0).getJSONArray("ink").toString());
      ByteArrayOutputStream zip = new ByteArrayOutputStream();
      s.backup(id, note, zip);
      restored = s.restore(new ByteArrayInputStream(zip.toByteArray()));
      assertFalse(id.equals(restored));
      JSONObject copy = s.load(restored);
      assertEquals(
          page.getJSONArray("ink").toString(),
          copy.getJSONArray("pages").getJSONObject(0).getJSONArray("ink").toString());
      ByteArrayOutputStream pdf = new ByteArrayOutputStream();
      s.exportPdf(restored, copy, pdf);
      assertTrue(pdf.size() > 100);
      String imported = s.importPdf(new ByteArrayInputStream(pdf.toByteArray()), "Synthetic PDF");
      try {
        JSONObject p = s.load(imported).getJSONArray("pages").getJSONObject(0);
        Bitmap raster = s.background(imported, p, 800);
        try {
          int[] pixels = new int[raster.getWidth() * raster.getHeight()];
          raster.getPixels(
              pixels, 0, raster.getWidth(), 0, 0, raster.getWidth(), raster.getHeight());
          int dark = 0;
          for (int pixel : pixels)
            if (Color.red(pixel) < 160 && Color.green(pixel) < 160 && Color.blue(pixel) < 160)
              dark++;
          assertTrue("PDF lost the test stroke", dark > 12);
        } finally {
          raster.recycle();
        }
      } finally {
        s.delete(imported);
      }
    } finally {
      s.delete(id);
      if (restored != null) s.delete(restored);
    }
  }

  public void testBundledChineseOcr() throws Exception {
    Bitmap b = Bitmap.createBitmap(1100, 400, Bitmap.Config.ARGB_8888);
    try {
      Canvas c = new Canvas(b);
      c.drawColor(Color.WHITE);
      Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
      p.setColor(Color.BLACK);
      p.setTextSize(82);
      p.setTextLocale(java.util.Locale.SIMPLIFIED_CHINESE);
      c.drawText("中文学习笔记", 70, 138, p);
      c.drawText("今天学习中文", 70, 260, p);
      String text =
          OfflineOcr.recognize(getInstrumentation().getTargetContext(), b).replaceAll("\\s+", "");
      assertTrue("Chinese OCR result: " + text, text.contains("中文"));
    } finally {
      b.recycle();
    }
  }

  public void testAudioAndCardsInEditableBackup() throws Exception {
    Store store = new Store(getInstrumentation().getTargetContext());
    String id = store.create("Synthetic local media", "plain"), next = null;
    try {
      JSONObject doc = store.load(id);
      File clip = new File(store.folder(id), "audio/clip-1.m4a");
      try (InputStream in =
          getInstrumentation().getContext().getAssets().open("synthetic-silence.m4a")) {
        java.nio.file.Files.copy(in, clip.toPath());
      }
      doc.getJSONArray("audio")
          .put(
              new JSONObject()
                  .put("name", "Silence")
                  .put("path", "audio/clip-1.m4a")
                  .put("durationMs", 800)
                  .put("createdAt", 1));
      doc.getJSONArray("cards")
          .put(
              new JSONObject()
                  .put("front", "2+2")
                  .put("back", "4")
                  .put("rating", "good")
                  .put("reviews", 1));
      store.save(id, doc);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      store.backup(id, doc, out);
      next = store.restore(new ByteArrayInputStream(out.toByteArray()));
      JSONObject copy = store.load(next);
      assertEquals("4", copy.getJSONArray("cards").getJSONObject(0).getString("back"));
      assertEquals("good", copy.getJSONArray("cards").getJSONObject(0).getString("rating"));
      File copied =
          new File(
              store.folder(next), copy.getJSONArray("audio").getJSONObject(0).getString("path"));
      assertTrue(
          java.util.Arrays.equals(
              java.nio.file.Files.readAllBytes(clip.toPath()),
              java.nio.file.Files.readAllBytes(copied.toPath())));
      android.media.MediaPlayer player = new android.media.MediaPlayer();
      try {
        player.setDataSource(copied.getPath());
        player.prepare();
        assertTrue(player.getDuration() > 700);
      } finally {
        player.release();
      }
    } finally {
      store.delete(id);
      if (next != null) store.delete(next);
    }
  }

  public void testBundledOcr() throws Exception {
    Bitmap b = Bitmap.createBitmap(1100, 400, Bitmap.Config.ARGB_8888);
    try {
      Canvas c = new Canvas(b);
      c.drawColor(Color.WHITE);
      Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
      p.setColor(Color.BLACK);
      p.setTextSize(78);
      c.drawText("PAPER HARBOR", 65, 138, p);
      c.drawText("OFFLINE NOTES", 65, 252, p);
      String text =
          OfflineOcr.recognize(getInstrumentation().getTargetContext(), b)
              .toUpperCase(java.util.Locale.ROOT);
      assertTrue(
          "Expected printed English words in OCR result",
          text.contains("HARBOR") || text.contains("OFFLINE"));
    } finally {
      b.recycle();
    }
  }
}
