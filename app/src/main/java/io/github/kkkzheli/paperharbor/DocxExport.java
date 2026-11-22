/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation.
 */
package io.github.kkkzheli.paperharbor;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a notebook to a Word .docx whose pages are the rendered pages, ink and notes included.
 *
 * <p>This is the "original format" export for a .docx that was imported for annotation: someone
 * without PaperHarbor can open it in Word or LibreOffice and see every annotation. The editable
 * copy stays in the .phnote backup — a picture of a page cannot be re-edited as ink.
 *
 * <p>Pages are rendered and compressed one at a time straight into the ZIP, so a long notebook
 * never holds more than one page bitmap in memory.
 */
final class DocxExport {
    private static final int MAX_EDGE = 1400;

    private DocxExport() {}

    static void write(Store store, String id, JSONObject doc, OutputStream out) throws Exception {
        JSONArray pages = doc.getJSONArray("pages");
        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FilterOutputStream(out) {
            @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
            @Override public void close() throws IOException { flush(); }
        }));
        try {
            entry(zip, "[Content_Types].xml", contentTypes());
            entry(zip, "_rels/.rels", packageRels());
            entry(zip, "word/document.xml", document(pages));
            entry(zip, "word/_rels/document.xml.rels", documentRels(pages.length()));
            for (int i = 0; i < pages.length(); i++) {
                Bitmap bitmap = render(store, id, pages.getJSONObject(i));
                try {
                    zip.putNextEntry(new ZipEntry("word/media/page" + (i + 1) + ".png"));
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                        throw new IOException("无法编码页面图片");
                    zip.closeEntry();
                } finally {
                    bitmap.recycle();
                }
            }
        } finally {
            zip.close();
        }
    }

    private static void entry(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static Bitmap render(Store store, String id, JSONObject page) throws Exception {
        int w = Math.max(1, page.optInt("w", 612)), h = Math.max(1, page.optInt("h", 792));
        float scale = Math.min(2f, MAX_EDGE / (float)Math.max(w, h));
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(w * scale)),
            Math.max(1, Math.round(h * scale)), Bitmap.Config.ARGB_8888);
        Bitmap background = null;
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(bitmap.getWidth() / (float)w, bitmap.getHeight() / (float)h);
            if (page.optInt("pdf", -1) >= 0) background = store.background(id, page, MAX_EDGE);
            InkView.drawPage(canvas, page, background, store, id);
        } catch (Exception e) {
            bitmap.recycle();
            throw e;
        } finally {
            if (background != null) background.recycle();
        }
        return bitmap;
    }

    private static String contentTypes() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
            + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
            + "</Types>";
    }

    private static String packageRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
            + "</Relationships>";
    }

    private static String documentRels(int pages) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        b.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < pages; i++) {
            b.append("<Relationship Id=\"rId").append(i + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/page")
                .append(i + 1).append(".png\"/>");
        }
        b.append("</Relationships>");
        return b.toString();
    }

    private static String document(JSONArray pages) throws Exception {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        b.append("<w:document")
            .append(" xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"")
            .append(" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"")
            .append(" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"")
            .append(" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"")
            .append(" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
            .append("<w:body>");
        for (int i = 0; i < pages.length(); i++) {
            JSONObject page = pages.getJSONObject(i);
            long cx = Math.max(1, page.optInt("w", 612)) * 12700L;
            long cy = Math.max(1, page.optInt("h", 792)) * 12700L;
            b.append("<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
                .append("<wp:extent cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/>")
                .append("<wp:docPr id=\"").append(i + 1).append("\" name=\"Page ").append(i + 1).append("\"/>")
                .append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
                .append("<pic:pic><pic:nvPicPr><pic:cNvPr id=\"").append(i + 1)
                .append("\" name=\"page").append(i + 1).append(".png\"/><pic:cNvPicPr/></pic:nvPicPr>")
                .append("<pic:blipFill><a:blip r:embed=\"rId").append(i + 1)
                .append("\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
                .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(cx)
                .append("\" cy=\"").append(cy).append("\"/></a:xfrm>")
                .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>")
                .append("</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>");
        }
        b.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
            .append("<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/>")
            .append("</w:sectPr></w:body></w:document>");
        return b.toString();
    }
}
