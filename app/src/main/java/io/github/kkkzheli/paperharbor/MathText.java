/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation.
 */
package io.github.kkkzheli.paperharbor;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns a small LaTeX subset into ordinary Unicode text.
 *
 * <p>This is a notation converter, not a typesetter: {@code \frac{a}{b}} becomes {@code (a)⁄(b)},
 * {@code x^2} becomes {@code x²}, and Greek names and operators take their real glyphs. It runs
 * entirely on the device and needs no formula font, which keeps a note readable everywhere — in
 * the editor, in a PDF export and in a search for the same text.
 */
final class MathText {
    private static final Map<String, String> SYMBOLS = new HashMap<>();
    private static final Map<Character, Character> SUP = new HashMap<>();
    private static final Map<Character, Character> SUB = new HashMap<>();

    private MathText() {}

    static String toUnicode(String input) { return convert(input == null ? "" : input, 0); }

    private static String convert(String input, int depth) {
        if (depth > 8) return input;
        String s = input;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '\\') {
                int j = i + 1;
                while (j < s.length() && Character.isLetter(s.charAt(j))) j++;
                if (j == i + 1) {                       // escaped punctuation such as \{ or \%
                    if (i + 1 < s.length()) { out.append(s.charAt(i + 1)); i += 2; }
                    else i++;
                    continue;
                }
                String name = s.substring(i + 1, j);
                if (name.equals("frac")) {
                    Group a = group(s, j);
                    Group b = a == null ? null : group(s, a.end);
                    if (a != null && b != null) {
                        out.append(wrap(convert(a.body, depth + 1)))
                            .append('⁄')
                            .append(wrap(convert(b.body, depth + 1)));
                        i = b.end;
                        continue;
                    }
                }
                if (name.equals("sqrt")) {
                    Group a = group(s, j);
                    if (a != null) {
                        out.append('√').append(wrap(convert(a.body, depth + 1)));
                        i = a.end;
                        continue;
                    }
                }
                if (name.equals("quad") || name.equals(",")) { out.append(' '); i = j; continue; }
                String mapped = SYMBOLS.get(name);
                out.append(mapped == null ? name : mapped);
                i = j;
                continue;
            }
            if (ch == '^' || ch == '_') {
                int j = i + 1;
                String body;
                if (j < s.length() && s.charAt(j) == '{') {
                    Group g = group(s, j);
                    body = g == null ? "" : convert(g.body, depth + 1);
                    j = g == null ? j + 1 : g.end;
                } else if (j < s.length()) {
                    body = convert(String.valueOf(s.charAt(j)), depth + 1);
                    j++;
                } else body = "";
                out.append(ch == '^' ? superscript(body) : subscript(body));
                i = j;
                continue;
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    /** Reads a {...} group, or one following character. */
    private static Group group(String s, int start) {
        int i = start;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= s.length()) return null;
        if (s.charAt(i) == '{') {
            int depth = 0;
            for (int j = i; j < s.length(); j++) {
                char c = s.charAt(j);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return new Group(s.substring(i + 1, j), j + 1);
                }
            }
            return null;
        }
        return new Group(String.valueOf(s.charAt(i)), i + 1);
    }

    private static String wrap(String body) {
        return body.length() > 1 ? "(" + body + ")" : body;
    }

    private static String superscript(String body) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            Character mapped = SUP.get(body.charAt(i));
            if (mapped == null) return "^" + body;
            out.append(mapped);
        }
        return out.toString();
    }

    private static String subscript(String body) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            Character mapped = SUB.get(body.charAt(i));
            if (mapped == null) return "_" + body;
            out.append(mapped);
        }
        return out.toString();
    }

    private static final class Group {
        final String body;
        final int end;
        Group(String body, int end) { this.body = body; this.end = end; }
    }

    private static void symbol(String name, String glyph) { SYMBOLS.put(name, glyph); }

    private static void pair(Map<Character, Character> map, String from, String to) {
        for (int i = 0; i < from.length() && i < to.length(); i++) map.put(from.charAt(i), to.charAt(i));
    }

    static {
        symbol("alpha", "α"); symbol("beta", "β"); symbol("gamma", "γ"); symbol("delta", "δ");
        symbol("epsilon", "ε"); symbol("varepsilon", "ε"); symbol("zeta", "ζ"); symbol("eta", "η");
        symbol("theta", "θ"); symbol("iota", "ι"); symbol("kappa", "κ"); symbol("lambda", "λ");
        symbol("mu", "μ"); symbol("nu", "ν"); symbol("xi", "ξ"); symbol("pi", "π");
        symbol("rho", "ρ"); symbol("sigma", "σ"); symbol("tau", "τ"); symbol("upsilon", "υ");
        symbol("phi", "φ"); symbol("varphi", "φ"); symbol("chi", "χ"); symbol("psi", "ψ");
        symbol("omega", "ω");
        symbol("Gamma", "Γ"); symbol("Delta", "Δ"); symbol("Theta", "Θ"); symbol("Lambda", "Λ");
        symbol("Xi", "Ξ"); symbol("Pi", "Π"); symbol("Sigma", "Σ"); symbol("Phi", "Φ");
        symbol("Psi", "Ψ"); symbol("Omega", "Ω");
        symbol("times", "×"); symbol("div", "÷"); symbol("cdot", "·"); symbol("pm", "±");
        symbol("mp", "∓"); symbol("le", "≤"); symbol("leq", "≤"); symbol("ge", "≥");
        symbol("geq", "≥"); symbol("ne", "≠"); symbol("neq", "≠"); symbol("approx", "≈");
        symbol("equiv", "≡"); symbol("infty", "∞"); symbol("sum", "∑"); symbol("prod", "∏");
        symbol("int", "∫"); symbol("partial", "∂"); symbol("nabla", "∇"); symbol("to", "→");
        symbol("rightarrow", "→"); symbol("leftarrow", "←"); symbol("in", "∈"); symbol("notin", "∉");
        symbol("subset", "⊂"); symbol("cup", "∪"); symbol("cap", "∩"); symbol("angle", "∠");
        symbol("degree", "°"); symbol("prime", "′"); symbol("therefore", "∴"); symbol("since", "∵");
        symbol("propto", "∝"); symbol("sim", "∼"); symbol("forall", "∀"); symbol("exists", "∃");
        symbol("emptyset", "∅"); symbol("perp", "⊥"); symbol("parallel", "∥"); symbol("ldots", "…");
        symbol("cdots", "⋯"); symbol("dots", "…"); symbol("alpha ", "α");

        pair(SUP, "0123456789+-=()niabcxyzkmt", "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱᵃᵇᶜˣʸᶻᵏᵐᵗ");
        pair(SUB, "0123456789+-=()aeioxnkmpshtl", "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₐₑᵢₒₓₙₖₘₚₛₕₜₗ");
    }
}
