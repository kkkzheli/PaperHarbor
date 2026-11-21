package io.github.kkkzheli.paperharbor;

import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** The editable format. Known fields have contracts; unknown JSON values round-trip. */
final class StoreJson {
    static final int BYTES = 8 * 1024 * 1024, PAGES = 256, EDGE = 8192, CLIPS = 100;
    static final Pattern CLIP = Pattern.compile("audio/clip-[1-9][0-9]{0,3}\\.m4a");
    private static final int DEPTH = 32, NODES = 450000, STRING = 131072;
    private StoreJson() {}

    static void require(boolean ok, String reason) throws IOException {
        if (!ok) throw new IOException(reason);
    }

    // Bound the JSON tree *during* construction; org.json's string parser is recursive and lenient.
    static JSONObject read(File file) throws Exception {
        require(file.length() > 0 && file.length() <= BYTES, "Invalid note.json size");
        try (JsonReader in = new JsonReader(new InputStreamReader(new FileInputStream(file),
                StandardCharsets.UTF_8.newDecoder()))) {
            Object v = readValue(in, 0, new int[]{NODES});
            require(in.peek() == JsonToken.END_DOCUMENT && v instanceof JSONObject, "Expected one notebook object");
            return (JSONObject) v;
        }
    }

    private static Object readValue(JsonReader in, int depth, int[] left) throws Exception {
        require(depth <= DEPTH && --left[0] >= 0, "Notebook JSON is too complex");
        switch (in.peek()) {
            case BEGIN_ARRAY: {
                in.beginArray(); JSONArray a = new JSONArray();
                while (in.hasNext()) a.put(readValue(in, depth + 1, left));
                in.endArray(); return a;
            }
            case BEGIN_OBJECT: {
                in.beginObject(); JSONObject o = new JSONObject();
                while (in.hasNext()) {
                    String key = in.nextName();
                    require(key.length() <= 256 && !o.has(key), "Invalid or duplicate JSON key");
                    o.put(key, readValue(in, depth + 1, left));
                }
                in.endObject(); return o;
            }
            case NULL: in.nextNull(); return JSONObject.NULL;
            case BOOLEAN: return in.nextBoolean();
            case STRING: {
                String s = in.nextString(); require(s.length() <= STRING, "JSON string too long"); return s;
            }
            case NUMBER: {
                String s = in.nextString(); require(s.length() <= 64, "JSON number too long");
                try { return Long.valueOf(s); } catch (NumberFormatException ignored) { }
                BigDecimal n = new BigDecimal(s);
                require(Double.isFinite(n.doubleValue()), "Number out of range"); return n;
            }
            default: throw new IOException("Invalid JSON value");
        }
    }

    /** Does not close the destination. Use a streaming encoder, including for caller-created objects. */
    static void write(JSONObject doc, OutputStream dest) throws Exception {
        try (JsonWriter out = new JsonWriter(new OutputStreamWriter(new FilterOutputStream(dest) {
            private long left = BYTES;
            @Override public void write(byte[] b, int off, int len) throws IOException {
                require((left -= len) >= 0, "Notebook exceeds 8 MiB"); out.write(b, off, len);
            }
            @Override public void write(int b) throws IOException {
                require(--left >= 0, "Notebook exceeds 8 MiB"); out.write(b);
            }
            @Override public void close() throws IOException { flush(); }
        }, StandardCharsets.UTF_8.newEncoder()))) {
            writeValue(out, doc, 0, new int[]{NODES});
        }
    }

    private static void writeValue(JsonWriter out, Object v, int depth, int[] left) throws Exception {
        require(depth <= DEPTH && --left[0] >= 0, "Notebook JSON is too complex");
        if (v instanceof JSONObject) {
            out.beginObject(); JSONObject o = (JSONObject)v;
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next(); require(k.length() <= 256, "JSON key too long");
                out.name(k); writeValue(out, o.get(k), depth + 1, left);
            }
            out.endObject();
        } else if (v instanceof JSONArray) {
            out.beginArray(); JSONArray a = (JSONArray)v;
            for (int i = 0; i < a.length(); ++i) writeValue(out, a.get(i), depth + 1, left);
            out.endArray();
        } else if (v == JSONObject.NULL || v == null) out.nullValue();
        else if (v instanceof Boolean) out.value((Boolean)v);
        else if (v instanceof Number) {
            Number n = (Number)v;
            require(Double.isFinite(n.doubleValue()) && n.toString().length() <= 64, "Invalid JSON number");
            out.value(n);
        } else if (v instanceof String) {
            require(((String)v).length() <= STRING, "JSON string too long"); out.value((String)v);
        } else throw new IOException("Unsupported JSON value");
    }

    static void checkPage(int w, int h, String paper, int pdf) throws JSONException {
        if (w < 1 || w > EDGE || h < 1 || h > EDGE || pdf < -1 || pdf >= PAGES ||
                paper == null || paper.isEmpty() || paper.length() > 64) throw new JSONException("Invalid page geometry or template");
    }

    private static String string(JSONObject o, String key, int limit) throws Exception {
        Object v = o.get(key);
        require(v instanceof String && ((String)v).length() <= limit, "Invalid " + key); return (String)v;
    }
    private static double number(Object v, double min, double max) throws IOException {
        require(v instanceof Number, "Expected a number");
        double d = ((Number)v).doubleValue(); require(d >= min && d <= max, "Number outside allowed range"); return d;
    }
    static long whole(JSONObject o, String key, long min, long max) throws Exception {
        Object v = o.get(key);
        number(v, min, max);
        long value;
        try { value = new BigDecimal(v.toString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException e) { throw new IOException("Expected an integer: " + key, e); }
        require(value >= min && value <= max, "Invalid " + key); return value;
    }

    /** Validate the entire known logical schema. The returned paths are the complete clip manifest. */
    static Set<String> validate(JSONObject doc) throws Exception {
        string(doc, "title", 512);
        JSONArray pages = doc.getJSONArray("pages");
        require(pages.length() > 0 && pages.length() <= PAGES, "Notebook must have 1–256 pages");
        int strokesLeft = 8192, pointsLeft = 80000;
        for (int i = 0; i < pages.length(); ++i) {
            JSONObject p = pages.getJSONObject(i);
            int w = (int)whole(p, "w", 1, EDGE), h = (int)whole(p, "h", 1, EDGE);
            int pdf = (int)whole(p, "pdf", -1, PAGES - 1);
            checkPage(w, h, string(p, "paper", 64), pdf);
            string(p, "text", 32768); require(p.get("bookmark") instanceof Boolean, "Invalid bookmark");
            JSONArray strokes = p.getJSONArray("ink");
            require((strokesLeft -= strokes.length()) >= 0, "Too many strokes");
            for (int j = 0; j < strokes.length(); ++j) {
                JSONObject s = strokes.getJSONObject(j); String tool = string(s, "tool", 8);
                boolean shape = tool.equals("rect") || tool.equals("line") || tool.equals("shape");
                require(shape || tool.equals("pen") || tool.equals("marker") || tool.equals("brush"),
                        "Unknown ink tool");
                if (s.has("shape")) require(InkStroke.shapeKind(string(s, "shape", 16)), "Unknown shape");
                whole(s, "argb", Integer.MIN_VALUE, Integer.MAX_VALUE);
                require(number(s.get("width"), 0, 512) > 0, "Invalid ink width");
                JSONArray points = s.getJSONArray("points");
                require(points.length() > 0 && (!shape || points.length() == 2) &&
                        (pointsLeft -= points.length()) >= 0, "Invalid ink point count");
                for (int k = 0; k < points.length(); ++k) {
                    JSONArray pt = points.getJSONArray(k); require(pt.length() == 3, "Invalid ink point");
                    // Transformed selections may sit outside the page and are clipped when drawn.
                    number(pt.get(0), -w, 2.0 * w); number(pt.get(1), -h, 2.0 * h);
                    number(pt.get(2), 0, 2.5);
                }
            }
        }
        if (doc.has("cards")) {
            JSONArray a = doc.getJSONArray("cards"); require(a.length() <= 500, "Too many cards");
            for (int i = 0; i < a.length(); ++i) {
                JSONObject c = a.getJSONObject(i); string(c, "front", 2000); string(c, "back", 2000);
                String r = string(c, "rating", 8);
                require(r.equals("new") || r.equals("again") || r.equals("hard") || r.equals("good"), "Invalid card rating");
                whole(c, "reviews", 0, Integer.MAX_VALUE);
            }
        }
        Set<String> clips = new LinkedHashSet<>();
        if (doc.has("audio")) {
            JSONArray a = doc.getJSONArray("audio"); require(a.length() <= CLIPS, "Too many recordings");
            for (int i = 0; i < a.length(); ++i) {
                JSONObject c = a.getJSONObject(i); string(c, "name", 80);
                String path = string(c, "path", 64);
                require(CLIP.matcher(path).matches() && clips.add(path), "Invalid or duplicate recording path");
                whole(c, "durationMs", 0, Integer.MAX_VALUE); whole(c, "createdAt", 0, Long.MAX_VALUE);
            }
        }
        return clips;
    }
}
