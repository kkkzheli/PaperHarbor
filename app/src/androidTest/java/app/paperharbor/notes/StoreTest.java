package app.paperharbor.notes;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.test.InstrumentationTestCase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Platform-only, offline integration tests; use a fresh private directory, not the user's shelf. */
public final class StoreTest extends InstrumentationTestCase {
    private Store store;
    private File scratch;

    @Override protected void setUp() throws Exception {
        super.setUp();
        Context ctx = getInstrumentation().getTargetContext();
        scratch = Files.createTempDirectory(ctx.getCacheDir().toPath(), "store-test-").toFile();
        store = new Store(new ContextWrapper(ctx) { @Override public File getFilesDir() { return scratch; } });
    }
    @Override protected void tearDown() throws Exception {
        try { if (scratch != null) erase(scratch); } finally { super.tearDown(); }
    }
    private static void erase(File f) throws IOException {
        File[] files = f.listFiles(); if (files != null) for (File child : files) erase(child);
        Files.deleteIfExists(f.toPath());
    }
    private static byte[] zip(String json, String extra) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("note.json")); zip.write(json.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            if (extra != null) { zip.putNextEntry(new ZipEntry(extra)); zip.write(1); zip.closeEntry(); }
        }
        return bytes.toByteArray();
    }
    private void mustReject(byte[] bytes) throws Exception {
        List<String> before = store.ids();
        try { store.restore(new ByteArrayInputStream(bytes)); fail("Invalid backup was accepted"); }
        catch (Exception expected) { /* Assert isolation as well as rejection. */ }
        assertEquals(before, store.ids());
    }

    public void testAtomicSaveAndFreshRestore() throws Exception {
        String id = store.create("草稿", "grid"); JSONObject doc = store.load(id);
        doc.put("custom", new JSONObject().put("labels", new JSONArray().put("α").put("墨水")));
        store.save(id, doc);
        doc.put("title", "unsaved view");
        ByteArrayOutputStream backup = new ByteArrayOutputStream(); store.backup(id, doc, backup);
        String copy = store.restore(new ByteArrayInputStream(backup.toByteArray()));
        assertFalse(id.equals(copy)); assertEquals("草稿", store.load(id).getString("title"));
        assertEquals("unsaved view", store.load(copy).getString("title"));
        assertEquals("墨水", store.load(copy).getJSONObject("custom").getJSONArray("labels").getString(1));
        // Fail after the temp JSON file has been opened, not merely at schema validation.
        JSONObject deep = doc;
        for (int i=0; i<40; ++i) { JSONObject next = new JSONObject(); deep.put("child", next); deep = next; }
        try { store.save(id, doc); fail("Unbounded JSON depth accepted"); } catch (Exception expected) { }
        assertEquals("草稿", store.load(id).getString("title")); assertFalse(store.load(id).has("child"));
        store.delete(copy); assertEquals(Arrays.asList(id), store.ids());
    }

    public void testRejectsMalformedBackupsWithoutPublishing() throws Exception {
        String id = store.create("Synthetic", "plain"); JSONObject doc = store.load(id);
        mustReject(zip(doc.toString(), "../stray")); assertFalse(new File(scratch,"notebooks/stray").exists());
        mustReject(zip(doc.toString(), "audio/clip-1.m4a")); // Not in the recording manifest.
        byte[] good = zip(doc.toString(), null); mustReject(Arrays.copyOf(good, good.length-8));
        JSONObject p = doc.getJSONArray("pages").getJSONObject(0);
        p.put("w", 9000); mustReject(zip(doc.toString(), null));
        p.put("w", 612).put("pdf", 0); mustReject(zip(doc.toString(), null)); // Missing source.pdf.
        assertEquals("Synthetic", store.load(id).getString("title"));
    }

    public void testMixedPagePdfKeepsGeometryAndBackground() throws Exception {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        PdfDocument pdf = new PdfDocument();
        try {
            for (int i=0; i<2; ++i) {
                PdfDocument.Page p = pdf.startPage(new PdfDocument.PageInfo.Builder(i==0 ? 96:192,96,i+1).create());
                p.getCanvas().drawColor(i==0 ? Color.RED : Color.BLUE); pdf.finishPage(p);
            }
            pdf.writeTo(input);
        } finally { pdf.close(); }
        String id = store.importPdf(new ByteArrayInputStream(input.toByteArray()),"Synthetic PDF");
        JSONObject d = store.load(id); JSONArray pages = d.getJSONArray("pages");
        assertEquals(2,pages.length()); assertEquals(96,pages.getJSONObject(0).getInt("w"));
        JSONObject wide = pages.getJSONObject(1); assertEquals(192,wide.getInt("w")); assertEquals(1,wide.getInt("pdf"));
        d.put("pages",new JSONArray().put(wide)); // Export a subset, using its original source index.
        ByteArrayOutputStream backup = new ByteArrayOutputStream(); store.backup(id,d,backup);
        String restored = store.restore(new ByteArrayInputStream(backup.toByteArray()));
        ByteArrayOutputStream exported = new ByteArrayOutputStream(); store.exportPdf(restored,store.load(restored),exported);
        String flat = store.importPdf(new ByteArrayInputStream(exported.toByteArray()),"Flattened");
        JSONArray flattened = store.load(flat).getJSONArray("pages"); assertEquals(1,flattened.length());
        Bitmap bitmap = store.background(flat,flattened.getJSONObject(0),128);
        try {
            assertEquals(128,bitmap.getWidth()); assertEquals(64,bitmap.getHeight());
            int center = bitmap.getPixel(64,32); assertTrue(Color.blue(center)>220 && Color.red(center)<30);
        } finally { bitmap.recycle(); }
    }
}
