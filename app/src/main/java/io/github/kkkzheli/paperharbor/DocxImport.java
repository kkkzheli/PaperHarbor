/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation.
 */
package io.github.kkkzheli.paperharbor;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads the text of a Word .docx and lays it out as annotatable pages.
 *
 * <p>The document is never re-edited in place: PaperHarbor shows the text as the page content and
 * keeps the source untouched, so ink and typed notes sit on top of it. Pagination is computed once
 * with the same text metrics the page renderer uses, which keeps each page's slice stable when the
 * note is reopened. Tables are flattened to spaces and images are dropped — the aim is a readable,
 * annotatable copy rather than a faithful Word rendering.
 */
final class DocxImport {
    private static final int MAX_XML = 24 * 1024 * 1024;
    private static final int MAX_TEXT = 200_000;
    private static final int PAGE_W = 595, PAGE_H = 842, MARGIN = 48, TEXT_SIZE = 15;

    private DocxImport() {}

    static JSONObject read(InputStream in, String title) throws Exception {
        String text = text(in);
        if (text.trim().isEmpty()) throw new IOException("文档里没有可显示的文本");
        JSONObject doc = new JSONObject()
            .put("title", title)
            .put("pages", pages(text))
            .put("cards", new JSONArray())
            .put("audio", new JSONArray())
            .put("docType", "docx")
            .put("cover", new JSONObject().put("style", "spine")
                .put("color", Ui.coverColor(title)));
        return doc;
    }

    private static String text(InputStream in) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!"word/document.xml".equals(entry.getName())) continue;
                return paragraphs(new Bounded(zip, MAX_XML));
            }
        }
        throw new IOException("不是 Word 文档（缺少 word/document.xml）");
    }

    private static String paragraphs(InputStream xml) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(xml, null);
        StringBuilder out = new StringBuilder();
        boolean inText = false;
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT && out.length() < MAX_TEXT) {
            if (event == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if ("t".equals(name)) inText = true;
                else if ("tab".equals(name)) out.append('\t');
                else if ("br".equals(name) || "cr".equals(name)) out.append('\n');
            } else if (event == XmlPullParser.END_TAG) {
                String name = parser.getName();
                if ("t".equals(name)) inText = false;
                else if ("p".equals(name)) out.append('\n');
                else if ("tc".equals(name)) out.append(' ');
            } else if (event == XmlPullParser.TEXT && inText) {
                out.append(parser.getText());
            }
        }
        return out.toString();
    }

    private static JSONArray pages(String text) throws Exception {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        paint.setTextSize(TEXT_SIZE);
        paint.density = 1;
        int width = PAGE_W - 2 * MARGIN;
        StaticLayout layout = StaticLayout.Builder
            .obtain(text, 0, text.length(), paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build();
        int lineHeight = layout.getLineCount() > 1
            ? Math.max(1, layout.getLineTop(1) - layout.getLineTop(0))
            : Math.max(1, layout.getHeight());
        int perPage = Math.max(1, (PAGE_H - 2 * MARGIN) / lineHeight);
        int total = layout.getLineCount();
        JSONArray pages = new JSONArray();
        for (int start = 0; start < total; start += perPage) {
            int end = Math.min(total, start + perPage);
            String slice = text.substring(layout.getLineStart(start), layout.getLineEnd(end - 1));
            pages.put(Store.page(PAGE_W, PAGE_H, "plain", -1).put("docText", slice));
        }
        if (pages.length() == 0) pages.put(Store.page(PAGE_W, PAGE_H, "plain", -1).put("docText", text));
        return pages;
    }

    /** Caps how much of one ZIP entry is read, so a zip bomb cannot exhaust memory. */
    private static final class Bounded extends InputStream {
        private final InputStream in;
        private int left;

        Bounded(InputStream in, int limit) { this.in = in; this.left = limit; }

        @Override public int read() throws IOException {
            if (left <= 0) throw new IOException("Word 文档内容过大");
            int value = in.read();
            if (value >= 0) left--;
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (left <= 0) throw new IOException("Word 文档内容过大");
            int read = in.read(buffer, offset, Math.min(length, left));
            if (read > 0) left -= read;
            return read;
        }

        @Override public void close() { /* the ZIP stream owns the entry */ }
    }
}
