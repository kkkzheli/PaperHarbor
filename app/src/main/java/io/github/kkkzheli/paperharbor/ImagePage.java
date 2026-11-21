package io.github.kkkzheli.paperharbor;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.media.ExifInterface;
import android.net.Uri;
import java.io.*;

final class ImagePage {
  private ImagePage() {}

  static String importFile(Context ctx, Store store, Uri uri, String title) throws Exception {
    File src = File.createTempFile("image-", ".tmp", ctx.getCacheDir());
    File pdf = null;
    Bitmap image = null, upright = null;
    try {
      try (InputStream in = ctx.getContentResolver().openInputStream(uri);
          OutputStream out = new FileOutputStream(src)) {
        if (in == null) throw new IOException("无法打开图片");
        byte[] block = new byte[32768];
        long size = 0;
        int n;
        while ((n = in.read(block)) != -1) {
          size += n;
          if (size > 48L * 1024 * 1024) throw new IOException("图片超过 48 MiB");
          out.write(block, 0, n);
        }
      }
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(src.getPath(), o);
      if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("无法识别图片格式");
      int sample = 1;
      while (Math.max(o.outWidth, o.outHeight) / (long) sample > 2048) sample *= 2;
      o.inJustDecodeBounds = false;
      o.inSampleSize = sample;
      image = BitmapFactory.decodeFile(src.getPath(), o);
      if (image == null) throw new IOException("图片解码失败");
      int orient = 1;
      try {
        orient = new ExifInterface(src.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
      } catch (IOException ignored) {
      }
      Matrix m = new Matrix();
      switch (orient) {
        case 2:
          m.setScale(-1, 1);
          break;
        case 3:
          m.setRotate(180);
          break;
        case 4:
          m.setScale(1, -1);
          break;
        case 5:
          m.setRotate(90);
          m.postScale(-1, 1);
          break;
        case 6:
          m.setRotate(90);
          break;
        case 7:
          m.setRotate(-90);
          m.postScale(-1, 1);
          break;
        case 8:
          m.setRotate(-90);
          break;
      }
      upright =
          m.isIdentity()
              ? image
              : Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), m, true);
      float scale = 792f / Math.max(upright.getWidth(), upright.getHeight());
      int w = Math.max(1, Math.round(upright.getWidth() * scale)),
          h = Math.max(1, Math.round(upright.getHeight() * scale));
      pdf = File.createTempFile("page-", ".pdf", ctx.getCacheDir());
      PdfDocument target = new PdfDocument();
      try (OutputStream out = new FileOutputStream(pdf)) {
        PdfDocument.Page p = target.startPage(new PdfDocument.PageInfo.Builder(w, h, 1).create());
        p.getCanvas().drawColor(Color.WHITE);
        p.getCanvas()
            .drawBitmap(upright, null, new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
        target.finishPage(p);
        target.writeTo(out);
      } finally {
        target.close();
      }
      try (InputStream in = new FileInputStream(pdf)) {
        return store.importPdf(in, title);
      }
    } finally {
      if (upright != null && upright != image) upright.recycle();
      if (image != null) image.recycle();
      if (pdf != null) pdf.delete();
      src.delete();
    }
  }
}
