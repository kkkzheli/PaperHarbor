/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation, not affiliated with Goodnotes.
 */
package io.github.kkkzheli.paperharbor;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** Small UI preferences: theme, palette slots, recent colors, per-tool widths, pen behaviour. */
final class Prefs {
  static final int[] DEFAULT_SLOTS = {
    0xff1c2b27, 0xff2c5a86, 0xffb0463a, 0xff2f6b4f,
    0xffdc9f2c, 0xff8a4f7d, 0xff3e6f73, 0xff59606b
  };
  private static final int SLOTS = 8;
  private static final int RECENTS = 8;
  private final SharedPreferences p;

  Prefs(Context c) {
    p = c.getSharedPreferences("paperharbor-ui", Context.MODE_PRIVATE);
  }

  int theme() {
    int value = p.getInt("theme", Ui.MODE_SYSTEM);
    return value < Ui.MODE_SYSTEM || value > Ui.MODE_DARK ? Ui.MODE_SYSTEM : value;
  }

  void setTheme(int mode) { p.edit().putInt("theme", mode).apply(); }

  /** true when the soft brush was the last pen kind. */
  boolean softPen() { return p.getBoolean("pen.soft", false); }

  void setSoftPen(boolean soft) { p.edit().putBoolean("pen.soft", soft).apply(); }

  /** 0 = erase whole strokes, 1 = erase only the swept area. */
  int eraseMode() { return p.getInt("erase.mode", 0) == 1 ? 1 : 0; }

  void setEraseMode(int mode) { p.edit().putInt("erase.mode", mode).apply(); }

  String shape() {
    String value = p.getString("shape", "line");
    return InkStroke.shapeKind(value) ? value : "line";
  }

  void setShape(String kind) { p.edit().putString("shape", kind).apply(); }

  boolean stylusOnly() { return p.getBoolean("stylus.only", false); }

  void setStylusOnly(boolean value) { p.edit().putBoolean("stylus.only", value).apply(); }

  /** 0.5 = light touch reaches full width sooner, 1.5 = needs more force. */
  float pressure() {
    float value = p.getFloat("pressure", 1f);
    return value < .5f || value > 1.5f ? 1f : value;
  }

  void setPressure(float value) { p.edit().putFloat("pressure", value).apply(); }

  /** 0 = last modified, 1 = title, 2 = newest first. */
  int sort() {
    int value = p.getInt("sort", 0);
    return value < 0 || value > 2 ? 0 : value;
  }

  void setSort(int value) { p.edit().putInt("sort", value).apply(); }

  String paper() {
    String value = p.getString("paper", "ruled");
    return "grid".equals(value) || "plain".equals(value) || "ruled".equals(value) ? value : "ruled";
  }

  void setPaper(String paper) { p.edit().putString("paper", paper).apply(); }

  int[] slots() {
    String raw = p.getString("slots", null);
    int[] out = DEFAULT_SLOTS.clone();
    if (raw == null) return out;
    String[] parts = raw.split(",");
    for (int i = 0; i < SLOTS && i < parts.length; i++) {
      try { out[i] = (int) Long.parseLong(parts[i], 16); } catch (NumberFormatException ignored) { }
    }
    return out;
  }

  void setSlot(int index, int color) {
    if (index < 0 || index >= SLOTS) return;
    int[] values = slots();
    values[index] = color;
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < values.length; i++) {
      if (i > 0) b.append(',');
      b.append(Integer.toHexString(values[i]));
    }
    p.edit().putString("slots", b.toString()).apply();
  }

  int[] recents() {
    String raw = p.getString("recents", "");
    List<Integer> colors = new ArrayList<>();
    if (!raw.isEmpty()) for (String part : raw.split(",")) {
      try { colors.add((int) Long.parseLong(part, 16)); } catch (NumberFormatException ignored) { }
    }
    int[] out = new int[colors.size()];
    for (int i = 0; i < out.length; i++) out[i] = colors.get(i);
    return out;
  }

  void pushRecent(int color) {
    List<Integer> colors = new ArrayList<>();
    colors.add(color);
    for (int existing : recents()) if (existing != color && colors.size() < RECENTS) colors.add(existing);
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < colors.size(); i++) {
      if (i > 0) b.append(',');
      b.append(Integer.toHexString(colors.get(i)));
    }
    p.edit().putString("recents", b.toString()).apply();
  }

  void clearRecents() { p.edit().remove("recents").apply(); }

  float width(String tool) {
    float fallback = "marker".equals(tool) ? 12 : "erase".equals(tool) ? 40 : "brush".equals(tool) ? 6 : 3;
    return p.getFloat("width." + tool, fallback);
  }

  void setWidth(String tool, float width) {
    p.edit().putFloat("width." + tool, width).apply();
  }

  int color() { return p.getInt("color", 0xff1c2b27); }
  void setColor(int color) { p.edit().putInt("color", color).apply(); }

  String tool() { return p.getString("tool", "pen"); }
  void setTool(String tool) { p.edit().putString("tool", tool).apply(); }
}
