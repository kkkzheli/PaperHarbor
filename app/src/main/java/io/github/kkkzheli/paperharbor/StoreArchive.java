package io.github.kkkzheli.paperharbor;

import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.*;
import java.util.zip.*;

import static io.github.kkkzheli.paperharbor.StoreJson.require;

/** Single-notebook ZIP32 reader/writer. Nothing is extracted directly to a published notebook. */
final class StoreArchive {
    static final String NOTE = "note.json", PDF = "source.pdf";
    static final long MIB = 1L << 20, PDF_BYTES = 64*MIB, CLIP_BYTES = 16*MIB, IMG_BYTES = 8*MIB,
        TOTAL = 128*MIB;
    private static final int ENTRIES = StoreJson.CLIPS + StoreJson.IMAGES + 4;
    private StoreArchive() {}

    static long copy(InputStream in, OutputStream out, long limit, CRC32 crc) throws IOException {
        byte[] buf = new byte[32768]; long total = 0;
        for (int n; (n = in.read(buf)) != -1;) {
            require(n > 0, "Stalled input stream"); total += n;
            require(total <= limit, "Asset or archive size limit exceeded");
            out.write(buf, 0, n); if (crc != null) crc.update(buf, 0, n);
        }
        return total;
    }

    static long fileSize(File f, long limit) throws IOException {
        require(Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS), "Missing notebook file: " + f.getName());
        long n = Files.size(f.toPath()); require(n > 0 && n <= limit, "Invalid size for " + f.getName()); return n;
    }

    private static long entryLimit(String name) throws IOException {
        if (name.equals(NOTE)) return StoreJson.BYTES;
        if (name.equals(PDF)) return PDF_BYTES;
        if (StoreJson.CLIP.matcher(name).matches()) return CLIP_BYTES;
        if (StoreJson.IMAGE.matcher(name).matches()) return IMG_BYTES;
        if (name.equals("audio/") || name.equals("images/")) return 0;
        throw new IOException("Unsupported notebook ZIP entry");
    }

    // Confirm a bounded central directory before asking ZipFile to allocate its index. This also
    // rejects incomplete streaming ZIPs (e.g. local records with no end-of-central-directory).
    private static int centralCount(File archive) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(archive, "r")) {
            int len = (int)Math.min(f.length(), 65535 + 22); byte[] last = new byte[len];
            long base = f.length() - len; f.seek(base); f.readFully(last);
            for (int i = len - 22; i >= 0; --i) {
                if (u32(last, i) != 0x06054b50L || i + 22 + u16(last, i + 20) != len) continue;
                int count = u16(last, i + 10); long size = u32(last, i + 12), start = u32(last, i + 16);
                require(u32(last, i + 4) == 0 && count == u16(last, i + 8) && count > 0 && count <= ENTRIES &&
                        size <= MIB && size >= 46L * count && start + size == base + i, "Invalid ZIP directory");
                return count;
            }
            throw new IOException("Missing ZIP directory");
        }
    }
    private static int u16(byte[] b, int i) { return (b[i]&255) | ((b[i+1]&255)<<8); }
    private static long u32(byte[] b, int i) { return u16(b,i) | ((long)u16(b,i+2)<<16); }

    /** The only archive names resolved as paths have already passed a full, ASCII allowlist. */
    static Set<String> extract(InputStream in, File stage) throws IOException {
        File archive = new File(stage, ".incoming");
        try (OutputStream out = new FileOutputStream(archive)) { copy(in, out, 136*MIB, null); }
        int count = centralCount(archive);
        Set<String> seen = new HashSet<>(); long remaining = TOTAL, compressedLeft = archive.length();
        try (ZipFile zip = new ZipFile(archive)) {
            require(zip.size() == count, "ZIP entry count mismatch");
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement(); String name = e.getName(); long cap = entryLimit(name);
                require(seen.size() < count && seen.add(name), "Duplicate ZIP entry");
                require(e.getMethod() == ZipEntry.DEFLATED || e.getMethod() == ZipEntry.STORED, "Unsupported ZIP method");
                long size = e.getSize(), compressed = e.getCompressedSize();
                require(size >= 0 && size <= cap && size <= remaining && compressed >= 0 && compressed <= compressedLeft,
                        "Invalid notebook ZIP entry size");
                compressedLeft -= compressed; // Bound total decoder input even when entry ranges overlap.
                if (name.equals("audio/") || name.equals("images/")) {
                    require(size == 0 && e.getCrc() == 0, "Invalid directory entry"); continue;
                }
                require(size > 0, "Empty notebook asset");
                File f = new File(stage, name);
                File parent = f.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create asset folder");
                require(f.createNewFile(), "ZIP entry collision");
                CRC32 crc = new CRC32();
                try (InputStream src = zip.getInputStream(e); OutputStream dest = new FileOutputStream(f)) {
                    require(copy(src, dest, size, crc) == size && crc.getValue() == e.getCrc(), "Damaged notebook ZIP entry");
                }
                remaining -= size;
            }
        }
        require(seen.size() == count && seen.remove(NOTE), "Missing note.json");
        Files.delete(archive.toPath()); seen.remove("audio/"); seen.remove("images/"); seen.remove(PDF);
        return seen;
    }

    static void write(File dir, File json, Set<String> clips, OutputStream destination) throws IOException {
        Map<String,File> files = new LinkedHashMap<>(); files.put(NOTE, json);
        File pdf = new File(dir, PDF);
        if (Files.exists(pdf.toPath(), LinkOption.NOFOLLOW_LINKS)) files.put(PDF, pdf);
        for (String clip : clips) files.put(clip, new File(dir, clip));
        Map<String,Long> sizes = new HashMap<>(); long total = 0;
        for (Map.Entry<String,File> e : files.entrySet()) {
            long n = fileSize(e.getValue(), entryLimit(e.getKey())); sizes.put(e.getKey(), n);
            require((total += n) <= TOTAL, "Backup exceeds 128 MiB");
        }
        // ZipOutputStream must be closed to release its deflater, but the caller owns destination.
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FilterOutputStream(destination) {
            @Override public void write(byte[] b, int p, int n) throws IOException { out.write(b, p, n); }
            @Override public void close() throws IOException { flush(); }
        }))) {
            for (Map.Entry<String,File> e : files.entrySet()) {
                long size = sizes.get(e.getKey()); zip.putNextEntry(new ZipEntry(e.getKey()));
                try (InputStream in = new FileInputStream(e.getValue())) {
                    require(copy(in, zip, size, null) == size, "Notebook asset changed during backup");
                }
                zip.closeEntry();
            }
        }
    }

    /** A restored picture must really be a PNG; the note.json path check alone is not enough. */
    static void checkPng(File file) throws IOException {
        fileSize(file, IMG_BYTES);
        byte[] header = new byte[8];
        try (FileInputStream in = new FileInputStream(file)) {
            require(in.read(header) == header.length, "Missing PNG header");
        }
        byte[] signature = {(byte)0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        require(Arrays.equals(header, signature), "Picture is not a PNG");
    }

    /** Bound the input before probing. A .m4a extension alone does not establish an AAC recording. */
    static void checkClip(File clip) throws IOException {
        fileSize(clip, CLIP_BYTES);
        try (FileInputStream src = new FileInputStream(clip)) {
            DataInputStream header = new DataInputStream(src); int box = header.readInt();
            require(box >= 16 && box <= clip.length() && header.readInt() == 0x66747970, "Invalid M4A container");
            src.getChannel().position(0);
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(src.getFD(), 0, clip.length());
                require(extractor.getTrackCount() == 1 && "audio/mp4a-latm".equals(
                        extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME)), "Expected one AAC audio track");
                extractor.selectTrack(0); require(extractor.getSampleTrackIndex() == 0, "Empty AAC recording");
            } finally { extractor.release(); }
        }
    }
}
