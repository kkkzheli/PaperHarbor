package app.paperharbor.notes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;

import static app.paperharbor.notes.StoreArchive.*;
import static app.paperharbor.notes.StoreJson.require;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.*;

/** App-private editable notebooks. All methods are synchronous. Callers own their streams and
 * background bitmaps and must not mutate a supplied JSONObject until its operation returns. */
public final class Store {
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Object INDEX = new Object();
    private static final int RENDER_EDGE = 2048;
    private final File root;
    // source.pdf is immutable for the lifetime of its freshly allocated document ID.
    private final LruCache<File,int[][]> geometry = new LruCache<>(16);

    public Store(Context c) { root = new File(c.getFilesDir(), "notebooks"); }

    private Path root() throws IOException {
        Files.createDirectories(root.toPath());
        require(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS), "Notebook storage is not a directory");
        return root.toPath();
    }

    public List<String> ids() throws IOException {
        synchronized (INDEX) {
            Map<String,Long> times = new HashMap<>();
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root())) {
                for (Path d : dirs) {
                    String id = d.getFileName().toString(); Path note = d.resolve(NOTE);
                    if (ID.matcher(id).matches() && Files.isDirectory(d, NOFOLLOW_LINKS) && Files.isRegularFile(note, NOFOLLOW_LINKS))
                        times.put(id, Files.getLastModifiedTime(note, NOFOLLOW_LINKS).toMillis());
                }
            }
            List<String> result = new ArrayList<>(times.keySet());
            Collections.sort(result, (a,b) -> { int c = Long.compare(times.get(b), times.get(a)); return c != 0 ? c : a.compareTo(b); });
            return result;
        }
    }

    /** Notebook root; finalized recordings live in its existing audio/ child. Never creates an ID. */
    public File folder(String id) throws IOException {
        require(id != null && ID.matcher(id).matches(), "Invalid notebook ID");
        Path d = root().resolve(id);
        require(Files.isDirectory(d, NOFOLLOW_LINKS) && Files.isRegularFile(d.resolve(NOTE), NOFOLLOW_LINKS) &&
                Files.isDirectory(d.resolve("audio"), NOFOLLOW_LINKS), "Notebook is missing"); return d.toFile();
    }

    public static JSONObject page(int w, int h, String paper, int pdfIndex) throws JSONException {
        StoreJson.checkPage(w, h, paper, pdfIndex);
        return new JSONObject().put("w",w).put("h",h).put("paper",paper).put("pdf",pdfIndex)
                .put("ink",new JSONArray()).put("text","").put("bookmark",false);
    }

    private static JSONObject notebook(String title, JSONArray pages) throws JSONException {
        return new JSONObject().put("title", title).put("pages", pages).put("cards", new JSONArray()).put("audio", new JSONArray());
    }

    public String create(String title, String paper) throws Exception {
        JSONObject doc = notebook(title, new JSONArray().put(page(612,792,paper,-1)));
        StoreJson.validate(doc);
        try (Stage s = new Stage()) { writeNote(s.dir, doc); return s.publish(); }
    }

    public JSONObject load(String id) throws Exception {
        File dir = folder(id); JSONObject doc = StoreJson.read(new File(dir, NOTE));
        checkAssets(dir, doc, StoreJson.validate(doc), false); return doc;
    }

    public void save(String id, JSONObject data) throws Exception {
        File dir = folder(id);
        checkAssets(dir, data, StoreJson.validate(data), false);
        writeNote(dir, data);
    }

    private static void writeNote(File dir, JSONObject doc) throws Exception {
        Path tmp = Files.createTempFile(dir.toPath(), ".note-", ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp.toFile())) {
                StoreJson.write(doc, out); out.getFD().sync();
            }
            Files.move(tmp, dir.toPath().resolve(NOTE), ATOMIC_MOVE, REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }

    private final class Stage implements AutoCloseable {
        final File dir; boolean published;
        Stage() throws IOException {
            dir = Files.createTempDirectory(root(), ".new-").toFile();
            try { Files.createDirectory(dir.toPath().resolve("audio")); }
            catch (IOException e) { Files.delete(dir.toPath()); throw e; }
        }
        String publish() throws IOException {
            synchronized (INDEX) {
                String id; Path target;
                do { id = UUID.randomUUID().toString(); target = root.toPath().resolve(id); }
                while (Files.exists(target, NOFOLLOW_LINKS));
                Files.move(dir.toPath(), target, ATOMIC_MOVE); published = true;
                int[][] dims = geometry.remove(dir); if (dims != null) geometry.put(target.toFile(), dims);
                return id;
            }
        }
        @Override public void close() throws IOException {
            if (!published) { geometry.remove(dir); removeTree(dir.toPath()); }
        }
    }

    public void delete(String id) throws IOException {
        Path trash;
        synchronized (INDEX) {
            File dir = folder(id); trash = root.toPath().resolve(".trash-" + UUID.randomUUID());
            Files.move(dir.toPath(), trash, ATOMIC_MOVE); geometry.remove(dir);
        }
        removeTree(trash);
    }

    private static void removeTree(Path dir) throws IOException {
        // Do not follow symlinks. Unpublish first so an interrupted delete is never half-visible.
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                Files.delete(f); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path d, IOException failure) throws IOException {
                if (failure != null) throw failure; Files.delete(d); return FileVisitResult.CONTINUE;
            }
        });
    }

    private static PdfRenderer renderer(File dir) throws IOException {
        File pdf = new File(dir, PDF); fileSize(pdf, PDF_BYTES);
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
        try { return new PdfRenderer(fd); } // The renderer takes ownership only on success.
        catch (IOException | RuntimeException e) { fd.close(); throw e; }
    }

    private int[][] dimensions(File dir) throws Exception {
        int[][] sizes = geometry.get(dir); if (sizes != null) return sizes;
        try (PdfRenderer r = renderer(dir)) {
            int n = r.getPageCount(); require(n >= 1 && n <= StoreJson.PAGES, "PDF must have 1–256 pages");
            sizes = new int[n][2];
            for (int i = 0; i < n; i++) try (PdfRenderer.Page p = r.openPage(i)) {
                int w = p.getWidth(), h = p.getHeight(); StoreJson.checkPage(w, h, "pdf", i);
                sizes[i][0] = w; sizes[i][1] = h;
            }
        }
        geometry.put(dir, sizes); return sizes;
    }

    private void checkAssets(File dir, JSONObject doc, Set<String> clips, boolean probeAudio) throws Exception {
        File pdf = new File(dir, PDF);
        long bytes = 0; int[][] dims = null;
        if (Files.exists(pdf.toPath(), NOFOLLOW_LINKS)) { bytes = fileSize(pdf, PDF_BYTES); dims = dimensions(dir); }
        JSONArray pages = doc.getJSONArray("pages");
        for (int i = 0; i < pages.length(); ++i) {
            JSONObject p = pages.getJSONObject(i); int index = p.getInt("pdf");
            if (index < 0) continue;
            require(dims != null && index < dims.length, "Missing PDF page");
            int[] wh = dims[index];
            require((long)p.getInt("w") * wh[1] == (long)p.getInt("h") * wh[0], "PDF page proportions do not match source.pdf");
        }
        for (String path : clips) {
            File f = new File(dir, path); bytes += fileSize(f, CLIP_BYTES);
            require(bytes <= TOTAL, "Notebook assets exceed 128 MiB");
            if (probeAudio) checkClip(f);
        }
    }

    public String importPdf(InputStream in, String title) throws Exception {
        try (Stage s = new Stage()) {
            try (FileOutputStream dest = new FileOutputStream(new File(s.dir, PDF))) { copy(in, dest, PDF_BYTES, null); }
            int[][] sizes = dimensions(s.dir); JSONArray pages = new JSONArray();
            for (int i = 0; i < sizes.length; ++i) pages.put(page(sizes[i][0], sizes[i][1], "pdf", i));
            JSONObject doc = notebook(title, pages); StoreJson.validate(doc); writeNote(s.dir, doc); return s.publish();
        }
    }

    private static Bitmap render(PdfRenderer renderer, int index, int edge, int mode) throws Exception {
        require(index >= 0 && index < renderer.getPageCount(), "PDF page index out of range");
        try (PdfRenderer.Page p = renderer.openPage(index)) {
            int w = p.getWidth(), h = p.getHeight(); StoreJson.checkPage(w, h, "pdf", index);
            double scale = (double)edge / Math.max(w, h);
            int bw = Math.max(1, (int)Math.round(w * scale)), bh = Math.max(1, (int)Math.round(h * scale));
            Bitmap image = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            try {
                image.eraseColor(Color.WHITE); Matrix m = new Matrix(); m.setScale((float)bw / w, (float)bh / h);
                p.render(image, null, m, mode); return image;
            } catch (RuntimeException | Error e) { image.recycle(); throw e; }
        }
    }

    /** Only a source PDF supplies a bitmap. Paper templates are drawn by InkView. */
    public Bitmap background(String id, JSONObject page, int maxEdge) throws Exception {
        File dir = folder(id); int index = (int)StoreJson.whole(page, "pdf", -1, StoreJson.PAGES - 1);
        if (index < 0) return null;
        require(maxEdge > 0, "Preview size must be positive");
        try (PdfRenderer r = renderer(dir)) { return render(r, index, Math.min(RENDER_EDGE, maxEdge), PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); }
    }

    public void exportPdf(String id, JSONObject doc, OutputStream out) throws Exception {
        File dir = folder(id); checkAssets(dir, doc, StoreJson.validate(doc), false);
        JSONArray pages = doc.getJSONArray("pages"); int pdfPages = 0;
        for (int i = 0; i < pages.length(); ++i) if (pages.getJSONObject(i).getInt("pdf") >= 0) ++pdfPages;
        // PdfDocument can retain raster resources until writeTo. Budget the whole export, not just one bitmap.
        long pixels = 12L * 1024 * 1024 / Math.max(1, pdfPages);
        PdfDocument output = new PdfDocument();
        try (PdfRenderer r = pdfPages == 0 ? null : renderer(dir)) {
            for (int i = 0; i < pages.length(); ++i) {
                JSONObject p = pages.getJSONObject(i); int w = p.getInt("w"), h = p.getInt("h"), index = p.getInt("pdf");
                double ratio = (double)Math.max(w,h) / Math.min(w,h);
                int edge = Math.max(1, Math.min(RENDER_EDGE, (int)Math.sqrt(pixels * ratio)));
                Bitmap bg = index == -1 ? null : render(r, index, edge, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                try {
                    PdfDocument.Page sheet = output.startPage(new PdfDocument.PageInfo.Builder(w,h,i+1).create());
                    try { InkView.drawPage(sheet.getCanvas(), p, bg); } finally { output.finishPage(sheet); }
                } finally { if (bg != null) bg.recycle(); }
            }
            output.writeTo(out);
        } finally { output.close(); }
    }

    public void backup(String id, JSONObject doc, OutputStream out) throws Exception {
        File dir = folder(id); Set<String> clips = StoreJson.validate(doc); checkAssets(dir, doc, clips, true);
        Path json = Files.createTempFile(root(), ".backup-", ".json");
        try {
            try (FileOutputStream data = new FileOutputStream(json.toFile())) { StoreJson.write(doc, data); }
            StoreArchive.write(dir, json.toFile(), clips, out);
        } finally { Files.deleteIfExists(json); }
    }

    public String restore(InputStream in) throws Exception {
        try (Stage s = new Stage()) {
            Set<String> entries = StoreArchive.extract(in, s.dir);
            JSONObject doc = StoreJson.read(new File(s.dir, NOTE)); Set<String> clips = StoreJson.validate(doc);
            require(entries.equals(clips), "Recording manifest does not match ZIP contents");
            checkAssets(s.dir, doc, clips, true);
            writeNote(s.dir, doc); return s.publish();
        }
    }
}
