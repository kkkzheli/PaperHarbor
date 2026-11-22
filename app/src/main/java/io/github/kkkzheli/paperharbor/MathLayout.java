/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation.
 */
package io.github.kkkzheli.paperharbor;

import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.List;

/**
 * A small maths typesetter for formula blocks.
 *
 * <p>{@link MathText} can flatten a formula into Unicode, but a flat string cannot put a bar over
 * a radicand or stack a fraction — the radical ends up beside its content instead of around it.
 * This class lays the same LaTeX subset out as boxes: fractions stack over a rule, radicals get a
 * scaled root sign plus an overline that reaches across the whole radicand, and scripts are raised
 * or lowered at a smaller size. It draws with the block's own typeface, so a formula still follows
 * the serif/sans/mono/handwriting choice, and needs no formula font or web view.
 *
 * <p>Supported: frac, sqrt (with an optional index), ^, _, groups, \left…\right delimiters,
 * overline/bar, vec/hat/tilde, Greek letters, relations/operators, common functions and spacing.
 * Anything it does not know is drawn literally, exactly as the user typed it.
 */
final class MathLayout {
    private MathLayout() {}

    /** Measures a formula: {width, ascent above the baseline, descent below it}. */
    static float[] measure(String tex, Paint paint, float size) {
        Box box = parse(tex, paint, size);
        return new float[] {box.w, box.asc, box.desc};
    }

    /** Draws the formula with its top-left at (x, top); {@code wrap} is used for alignment. */
    static void draw(Canvas c, Paint paint, String tex, float x, float top, float size,
                     int align, float wrap) {
        Box box = parse(tex, paint, size);
        float dx = 0;
        if (wrap > box.w) {
            if (align == 1) dx = (wrap - box.w) * .5f;
            else if (align == 2) dx = wrap - box.w;
        }
        box.draw(c, paint, x + dx, top + box.asc);
        paint.setTextSize(size);
        paint.setStyle(Paint.Style.FILL);
    }

    // ---- boxes ----------------------------------------------------------------------------------

    private static final class Box {
        static final int TEXT = 0, SPACE = 1, ROW = 2, FRAC = 3, RADICAL = 4, SCRIPT = 5, OVER = 6,
            LIMITS = 7;
        final int kind;
        float w, asc, desc;
        String text;
        float textSize;
        float space;              // fraction side padding
        float offset;             // script rise / fraction axis
        Box a, b, c;              // fraction(num,den), radical(body,index), script(base,sup,sub)
        List<Box> kids;           // row

        Box(int kind) { this.kind = kind; }

        void draw(Canvas canvas, Paint paint, float x, float baseline) {
            switch (kind) {
                case TEXT:
                    paint.setTextSize(textSize);
                    canvas.drawText(text, x, baseline, paint);
                    break;
                case SPACE:
                    break;
                case ROW:
                    float cx = x;
                    for (Box kid : kids) { kid.draw(canvas, paint, cx, baseline); cx += kid.w; }
                    break;
                case FRAC: {
                    float axis = baseline - offset;
                    float gap = textSize * .09f;
                    a.draw(canvas, paint, x + (w - a.w) * .5f, axis - gap - a.desc);
                    b.draw(canvas, paint, x + (w - b.w) * .5f, axis + gap + b.asc);
                    float thickness = Math.max(1.1f, textSize * .055f);
                    canvas.drawRect(x + space, axis - thickness * .5f, x + w - space, axis + thickness * .5f, paint);
                    break;
                }
                case RADICAL: {
                    float glyphSize = offset;
                    paint.setTextSize(glyphSize);
                    Paint.FontMetrics fm = paint.getFontMetrics();
                    boolean cube = "3".equals(text), fourth = "4".equals(text);
                    String sign = cube ? "∛" : fourth ? "∜" : "√";
                    float signW = space;
                    float top = baseline - asc + Math.max(1.2f, textSize * .07f);
                    float x0 = x + (b == null ? 0 : b.w * .5f);
                    canvas.drawText(sign, x0, top - fm.ascent, paint);
                    a.draw(canvas, paint, x0 + signW * .92f, baseline);
                    if (b != null) {
                        paint.setTextSize(b.textSize);
                        b.draw(canvas, paint, x, baseline - asc + b.desc + b.asc);
                    }
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(Math.max(1.2f, textSize * .06f));
                    canvas.drawLine(x0 + signW * .55f, top, x + w, top, paint);
                    paint.setStyle(Paint.Style.FILL);
                    break;
                }
                case SCRIPT:
                    a.draw(canvas, paint, x, baseline);
                    if (b != null) b.draw(canvas, paint, x + a.w + textSize * .03f, baseline - offset);
                    if (c != null) c.draw(canvas, paint, x + a.w + textSize * .03f, baseline + textSize * .20f);
                    break;
                case LIMITS: {
                    float gap = textSize * .07f;
                    a.draw(canvas, paint, x + (w - a.w) * .5f, baseline);
                    if (b != null)
                        b.draw(canvas, paint, x + (w - b.w) * .5f,
                            baseline - a.asc - gap - b.desc);
                    if (c != null)
                        c.draw(canvas, paint, x + (w - c.w) * .5f,
                            baseline + a.desc + gap + c.asc);
                    break;
                }
                case OVER: {
                    a.draw(canvas, paint, x, baseline);
                    float top = baseline - asc + Math.max(1.2f, textSize * .07f);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(Math.max(1.2f, textSize * .06f));
                    canvas.drawLine(x, top, x + w, top, paint);
                    paint.setStyle(Paint.Style.FILL);
                    break;
                }
            }
        }
    }

    // ---- parsing and layout ---------------------------------------------------------------------

    private static Box parse(String tex, Paint paint, float size) {
        String source = tex == null ? "" : tex;
        if (source.startsWith("$") && source.endsWith("$") && source.length() > 1)
            source = source.substring(1, source.length() - 1);
        int[] pos = {0};
        return row(source, pos, paint, size, '\0');
    }

    private static Box row(String s, int[] pos, Paint p, float size, char end) {
        Box row = new Box(Box.ROW);
        row.kids = new ArrayList<>();
        row.textSize = size;
        StringBuilder plain = new StringBuilder();
        while (pos[0] < s.length()) {
            char ch = s.charAt(pos[0]);
            if (ch == '}' || (end != '\0' && ch == end)) { pos[0]++; break; }
            if (ch == '\\') {
                flush(row, plain, p, size);
                command(s, pos, p, size, row);
                continue;
            }
            if (ch == '^' || ch == '_') {
                flush(row, plain, p, size);
                pos[0]++;
                Box arg = argument(s, pos, p, size, .62f);
                Box last = row.kids.isEmpty() ? null : row.kids.get(row.kids.size() - 1);
                if (last != null && (last.kind == Box.SCRIPT || last.kind == Box.LIMITS)
                        && ((ch == '^' && last.b == null) || (ch == '_' && last.c == null))) {
                    mergeScript(last, ch == '^', arg);
                } else {
                    Box base = last == null
                        ? text("", size, p) : row.kids.remove(row.kids.size() - 1);
                    row.kids.add(script(base, ch == '^' ? arg : null, ch == '_' ? arg : null, size));
                }
                continue;
            }
            if (ch == '{') {
                flush(row, plain, p, size);
                pos[0]++;
                row.kids.add(row(s, pos, p, size, '}'));
                continue;
            }
            if (ch == '$' || ch == '&' || Character.isWhitespace(ch)) { pos[0]++; continue; }
            plain.append(ch);
            pos[0]++;
        }
        flush(row, plain, p, size);
        measureRow(row);
        return row;
    }

    private static void flush(Box row, StringBuilder plain, Paint p, float size) {
        if (plain.length() == 0) return;
        row.kids.add(text(plain.toString(), size, p));
        plain.setLength(0);
    }

    private static Box text(String value, float size, Paint p) {
        Box b = new Box(Box.TEXT);
        b.text = value == null ? "" : value;
        b.textSize = size;
        p.setTextSize(size);
        b.w = p.measureText(b.text);
        Paint.FontMetrics fm = p.getFontMetrics();
        b.asc = -fm.ascent;
        b.desc = fm.descent;
        return b;
    }

    private static Box space(float em, float size) {
        Box b = new Box(Box.SPACE);
        b.textSize = size;
        b.space = em * size;
        b.w = Math.max(0, b.space);
        return b;
    }

    private static Box script(Box base, Box sup, Box sub, float size) {
        Box b = new Box(isBigOperator(base) ? Box.LIMITS : Box.SCRIPT);
        b.a = base;
        b.b = sup;
        b.c = sub;
        b.textSize = size;
        layoutScript(b);
        return b;
    }

    private static boolean isBigOperator(Box base) {
        if (base.kind != Box.TEXT || base.text == null) return false;
        switch (base.text) {
            case "∑": case "∏": case "⋃": case "⋂": case "⨁":
            case "lim": case "max": case "min": case "sup": case "inf":
            case "det": case "gcd":
                return true;
            default:
                return false;
        }
    }

    private static void layoutScript(Box b) {
        float size = b.textSize;
        if (b.kind == Box.LIMITS) {
            float gap = size * .07f;
            b.w = Math.max(b.a.w, Math.max(b.b == null ? 0 : b.b.w, b.c == null ? 0 : b.c.w));
            b.asc = b.a.asc + (b.b == null ? 0 : gap + b.b.asc + b.b.desc);
            b.desc = b.a.desc + (b.c == null ? 0 : gap + b.c.asc + b.c.desc);
            return;
        }
        b.offset = size * .42f;
        float right = Math.max(b.b == null ? 0 : b.b.w, b.c == null ? 0 : b.c.w);
        b.w = b.a.w + (right > 0 ? size * .03f + right : 0);
        b.asc = Math.max(b.a.asc, b.b == null ? 0 : b.offset + b.b.asc);
        b.desc = Math.max(b.a.desc, b.c == null ? 0 : size * .20f + b.c.desc);
    }

    private static void mergeScript(Box script, boolean sup, Box arg) {
        if (sup) script.b = arg; else script.c = arg;
        layoutScript(script);
    }

    private static Box argument(String s, int[] pos, Paint p, float size, float scale) {
        skipSpace(s, pos);
        if (pos[0] >= s.length()) return text("", size * scale, p);
        char ch = s.charAt(pos[0]);
        if (ch == '{') {
            pos[0]++;
            return row(s, pos, p, size * scale, '}');
        }
        if (ch == '\\') {
            Box holder = new Box(Box.ROW);
            holder.kids = new ArrayList<>();
            holder.textSize = size * scale;
            command(s, pos, p, size * scale, holder);
            measureRow(holder);
            if (holder.kids.size() == 1) return holder.kids.get(0);
            return holder;
        }
        pos[0]++;
        return text(String.valueOf(ch), size * scale, p);
    }

    private static void command(String s, int[] pos, Paint p, float size, Box out) {
        pos[0]++;
        int start = pos[0];
        while (pos[0] < s.length() && Character.isLetter(s.charAt(pos[0]))) pos[0]++;
        String name = s.substring(start, pos[0]);
        if (name.isEmpty()) {
            if (pos[0] < s.length()) {
                char escaped = s.charAt(pos[0]++);
                if (escaped != '%' && escaped != '&' && escaped != '$' && escaped != '#')
                    out.kids.add(text(String.valueOf(escaped), size, p));
            }
            return;
        }
        switch (name) {
            case "frac": case "dfrac": case "tfrac": {
                Box num = argument(s, pos, p, size, .74f);
                Box den = argument(s, pos, p, size, .74f);
                Box f = new Box(Box.FRAC);
                f.a = num; f.b = den; f.textSize = size;
                f.offset = size * .30f;
                f.space = size * .08f;
                f.w = Math.max(num.w, den.w) + f.space * 2;
                f.asc = f.offset + size * .09f + num.desc + num.asc;
                f.desc = Math.max(den.desc, den.asc + size * .09f - f.offset);
                out.kids.add(f);
                return;
            }
            case "sqrt": {
                Box index = null;
                skipSpace(s, pos);
                if (pos[0] < s.length() && s.charAt(pos[0]) == '[') {
                    int close = s.indexOf(']', pos[0] + 1);
                    if (close > 0) {
                        index = parse(s.substring(pos[0] + 1, close), p, size * .52f);
                        pos[0] = close + 1;
                    }
                }
                Box body = argument(s, pos, p, size, 1f);
                Box r = new Box(Box.RADICAL);
                r.a = body; r.textSize = size;
                String idx = index == null ? null : firstText(index);
                boolean glyphIndex = "3".equals(idx) || "4".equals(idx);
                r.text = glyphIndex ? idx : null;
                r.b = glyphIndex ? null : index;
                float pad = size * .10f;
                float indexW = r.b == null ? 0 : index.w * .5f;
                String sign = "3".equals(idx) ? "∛" : "4".equals(idx) ? "∜" : "√";
                r.offset = (body.asc + body.desc + 2 * pad) * 1.02f;
                p.setTextSize(r.offset);
                r.space = p.measureText(sign);
                r.w = indexW + r.space * .92f + body.w + pad;
                r.asc = body.asc + pad;
                r.desc = body.desc + pad;
                out.kids.add(r);
                return;
            }
            case "text": case "textrm": case "mathrm": case "operatorname": case "mathbf":
            case "mathit": case "mbox": {
                out.kids.add(text(rawGroup(s, pos), size * .94f, p));
                return;
            }
            case "left": case "right": {
                String delim = delimiter(s, pos);
                if (!delim.isEmpty()) out.kids.add(text(delim, size, p));
                return;
            }
            case "overline": case "bar": {
                Box body = argument(s, pos, p, size, 1f);
                Box o = new Box(Box.OVER);
                o.a = body; o.textSize = size;
                o.w = body.w;
                o.asc = body.asc + size * .10f;
                o.desc = body.desc;
                out.kids.add(o);
                return;
            }
            case "vec": case "hat": case "tilde": case "dot": case "ddot": {
                Box body = argument(s, pos, p, size, 1f);
                String mark = "vec".equals(name) ? "⃗" : "hat".equals(name) ? "̂"
                    : "tilde".equals(name) ? "̃" : "dot".equals(name) ? "̇" : "̈";
                if (body.kind == Box.TEXT) out.kids.add(text(body.text + mark, body.textSize, p));
                else out.kids.add(body);
                return;
            }
            case "quad": out.kids.add(space(1f, size)); return;
            case "qquad": out.kids.add(space(2f, size)); return;
            case ",": case ";": case ":": case "!": case " ": {
                out.kids.add(space("!".equals(name) ? -.15f : .18f, size));
                return;
            }
            case "displaystyle": case "textstyle": case "scriptstyle": case "limits":
            case "nolimits": case "mathstrut": case "relax":
                return;
            case "\\": out.kids.add(space(.6f, size)); return;
            default: break;
        }
        String glyph = MathText.symbolFor(name);
        if (glyph != null) {
            out.kids.add(text(glyph, size, p));
            if (isFunction(name)) out.kids.add(space(.12f, size));
            return;
        }
        if (isFunction(name)) {
            out.kids.add(text(name, size, p));
            if (!isLimitName(name)) out.kids.add(space(.12f, size));
            return;
        }
        out.kids.add(text(name, size, p));
    }

    private static boolean isLimitName(String name) {
        switch (name) {
            case "lim": case "max": case "min": case "sup": case "inf":
            case "det": case "gcd":
                return true;
            default:
                return false;
        }
    }

    private static boolean isFunction(String name) {
        switch (name) {
            case "sin": case "cos": case "tan": case "cot": case "sec": case "csc":
            case "arcsin": case "arccos": case "arctan": case "sinh": case "cosh": case "tanh":
            case "log": case "ln": case "lg": case "exp": case "lim": case "max": case "min":
            case "det": case "gcd": case "deg": case "dim": case "ker": case "hom":
                return true;
            default:
                return false;
        }
    }

    private static String delimiter(String s, int[] pos) {
        skipSpace(s, pos);
        if (pos[0] >= s.length()) return "";
        char ch = s.charAt(pos[0]);
        if (ch == '{' || ch == '}' || ch == '(' || ch == ')' || ch == '[' || ch == ']'
                || ch == '|' || ch == '.') {
            pos[0]++;
            return ch == '.' ? "" : String.valueOf(ch);
        }
        if (ch == '\\') {
            pos[0]++;
            int start = pos[0];
            while (pos[0] < s.length() && Character.isLetter(s.charAt(pos[0]))) pos[0]++;
            String name = s.substring(start, pos[0]);
            switch (name) {
                case "langle": return "⟨";
                case "rangle": return "⟩";
                case "lbrace": return "{";
                case "rbrace": return "}";
                case "vert": case "|": return "|";
                case "Vert": return "‖";
                case "lfloor": return "⌊";
                case "rfloor": return "⌋";
                case "lceil": return "⌈";
                case "rceil": return "⌉";
                default: return "";
            }
        }
        pos[0]++;
        return String.valueOf(ch);
    }

    private static String rawGroup(String s, int[] pos) {
        skipSpace(s, pos);
        if (pos[0] >= s.length()) return "";
        if (s.charAt(pos[0]) != '{') return String.valueOf(s.charAt(pos[0]++));
        int depth = 0;
        int start = pos[0] + 1;
        for (int i = pos[0]; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    pos[0] = i + 1;
                    return s.substring(start, i);
                }
            }
        }
        pos[0] = s.length();
        return s.substring(start);
    }

    private static void skipSpace(String s, int[] pos) {
        while (pos[0] < s.length() && Character.isWhitespace(s.charAt(pos[0]))) pos[0]++;
    }

    private static String firstText(Box box) {
        if (box == null) return null;
        if (box.kind == Box.TEXT) return box.text;
        if (box.kids != null) {
            for (Box kid : box.kids) {
                String text = firstText(kid);
                if (text != null) return text;
            }
        }
        return null;
    }

    private static void measureRow(Box row) {
        if (row.kids == null) return;
        float w = 0, asc = 0, desc = 0;
        for (Box kid : row.kids) {
            w += kid.w;
            asc = Math.max(asc, kid.asc);
            desc = Math.max(desc, kid.desc);
        }
        row.w = w;
        row.asc = Math.max(asc, row.textSize * .8f);
        row.desc = Math.max(desc, row.textSize * .22f);
    }
}
