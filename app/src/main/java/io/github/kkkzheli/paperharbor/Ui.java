/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation, not affiliated with Goodnotes.
 */
package io.github.kkkzheli.paperharbor;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shared visual language: warm paper, deep green ink, terracotta accents, icon-first controls.
 *  The palette is installed per activity so the light and dark themes share one code path. */
final class Ui {
  static final int MODE_SYSTEM = 0, MODE_LIGHT = 1, MODE_DARK = 2;

  // Persisted appearance tokens. Written by install() before any screen is built.
  static boolean dark;
  static int BG = 0xfff3eee4;
  static int SURFACE = 0xfffffcf5;
  static int SURFACE_SOFT = 0xffece5d6;
  static int INK = 0xff17322c;
  static int ON_INK = 0xffffffff;
  static int MUTED = 0xff6f7a73;
  static int HINT = 0xff9b9486;
  static int LINE = 0xffe0d7c5;
  static int ACCENT = 0xffcf613b;
  static int ACCENT_SOFT = 0xffffe6da;
  static int DANGER = 0xffb4452f;
  static int CANVAS = 0xffe7e0d3;
  static int SHADOW = 0xffd9d0bf;
  static final int WHITE = 0xffffffff;
  /** The page itself always stays paper-coloured, in both themes. */
  static final int PAPER = 0xfffffdf8;
  /** Desk colour behind the page. The page itself stays paper in both themes. */
  static int DESK = 0xffe7e0d3;
  private static final int[] COVER = {
    0xff365a4f, 0xff7a4b42, 0xff3f5b76, 0xff7b6440, 0xff5d4f6b, 0xff4c6b6a
  };
  private static final int[] COVER_DARK = {
    0xff2c4a42, 0xff5f3a33, 0xff33495e, 0xff5f4d31, 0xff4a4057, 0xff3b5453
  };
  /** Accent families: light accent, light soft, dark accent, dark soft. */
  static final int[][] ACCENTS = {
    {0xffcf613b, 0xffffe6da, 0xffe4815b, 0xff43291e},
    {0xff3c6f8a, 0xffdfeaf2, 0xff6fb2cf, 0xff1e2f3a},
    {0xff4e7a5a, 0xffe2ede2, 0xff7fc292, 0xff1f3226},
    {0xff8a5a7f, 0xffefe2ec, 0xffc48ab7, 0xff33202f},
    {0xffa87722, 0xfff5ebd6, 0xffe0b25a, 0xff3a2c14},
    {0xff5a6570, 0xffe6e9ec, 0xff9aa8b4, 0xff262b31}
  };

  private Ui() {}

  /** Chooses light or dark from the stored preference and the system setting. */
  static void install(Context c, int mode) { install(c, mode, 0); }

  static void install(Context c, int mode, int accent) {
    boolean systemDark = (c.getResources().getConfiguration().uiMode
        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    dark = mode == MODE_DARK || (mode == MODE_SYSTEM && systemDark);
    int index = accent < 0 || accent >= ACCENTS.length ? 0 : accent;
    if (dark) {
      BG = 0xff15130f;
      SURFACE = 0xff211e19;
      SURFACE_SOFT = 0xff2b2721;
      INK = 0xfff2ebdd;
      ON_INK = 0xff1b1916;
      MUTED = 0xffa89e8d;
      HINT = 0xff82796a;
      LINE = 0xff393329;
      ACCENT = 0xffe4815b;
      ACCENT_SOFT = 0xff43291e;
      DANGER = 0xffe07a63;
      CANVAS = 0xff191611;
      SHADOW = 0xff4a4237;
      DESK = 0xff191611;
    } else {
      BG = 0xfff3eee4;
      SURFACE = 0xfffffcf5;
      SURFACE_SOFT = 0xffece5d6;
      INK = 0xff17322c;
      ON_INK = 0xffffffff;
      MUTED = 0xff6f7a73;
      HINT = 0xff9b9486;
      LINE = 0xffe0d7c5;
      ACCENT = 0xffcf613b;
      ACCENT_SOFT = 0xffffe6da;
      DANGER = 0xffb4452f;
      CANVAS = 0xffe7e0d3;
      SHADOW = 0xffd9d0bf;
      DESK = 0xffe7e0d3;
    }
    ACCENT = dark ? ACCENTS[index][2] : ACCENTS[index][0];
    ACCENT_SOFT = dark ? ACCENTS[index][3] : ACCENTS[index][1];
  }

  static int dp(Context c, float value) {
    return Math.round(TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, c.getResources().getDisplayMetrics()));
  }

  static int screenWidth(Context c) { return c.getResources().getDisplayMetrics().widthPixels; }
  static int screenHeight(Context c) { return c.getResources().getDisplayMetrics().heightPixels; }

  static Typeface serif(Context c) {
    Typeface t = Typeface.create("serif", Typeface.NORMAL);
    return t == null ? Typeface.SERIF : t;
  }

  static TextView text(Context c, CharSequence value, float sp, int color) {
    TextView t = new TextView(c);
    t.setText(Str.of(value));
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    t.setTextColor(color);
    return t;
  }

  static TextView label(Context c, CharSequence value, float sp, int color) {
    TextView t = text(c, value, sp, color);
    t.setLetterSpacing(.08f);
    return t;
  }

  static TextView serifTitle(Context c, CharSequence value, float sp, int color) {
    TextView t = text(c, value, sp, color);
    t.setTypeface(serif(c));
    return t;
  }

  static GradientDrawable round(Context c, int color, float radiusDp) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(c, radiusDp));
    return d;
  }

  static GradientDrawable roundStroke(Context c, int color, int stroke, float radiusDp, float strokeDp) {
    GradientDrawable d = round(c, color, radiusDp);
    d.setStroke(dp(c, strokeDp), stroke);
    return d;
  }

  static Drawable circle(int color) {
    GradientDrawable d = new GradientDrawable();
    d.setShape(GradientDrawable.OVAL);
    d.setColor(color);
    return d;
  }

  static GradientDrawable roundTop(Context c, int color, float radiusDp) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadii(new float[] {
        dp(c, radiusDp), dp(c, radiusDp), dp(c, radiusDp), dp(c, radiusDp), 0, 0, 0, 0
    });
    return d;
  }

  static RippleDrawable ripple(Drawable content) {
    return new RippleDrawable(ColorStateList.valueOf(0x2417322c), content, null);
  }

  static RippleDrawable ripple(Drawable content, float radiusDp, Context c) {
    return new RippleDrawable(ColorStateList.valueOf(dark ? 0x30f2ebdd : 0x2417322c), content, null);
  }

  static RippleDrawable ripple(int contentColor, float radiusDp, Context c) {
    return ripple(round(c, contentColor, radiusDp));
  }

  static ImageButton icon(Context c, int res, String desc, int boxDp, int tint, Runnable click) {
    ImageButton b = new ImageButton(c);
    int box = dp(c, boxDp);
    b.setLayoutParams(new ViewGroup.LayoutParams(box, box));
    b.setImageResource(res);
    b.setColorFilter(tint);
    b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    b.setPadding(dp(c, boxDp * .25f), dp(c, boxDp * .25f), dp(c, boxDp * .25f), dp(c, boxDp * .25f));
    b.setBackground(ripple(new GradientDrawable(), boxDp / 2f, c));
    // A dialog closing hands focus back to the button that opened it, and Android then paints a
    // focus ring around it. These are touch controls: the selected tool has its own filled
    // highlight, so the extra frame is only noise.
    b.setFocusable(false);
    b.setDefaultFocusHighlightEnabled(false);
    b.setContentDescription(Str.of(desc));
    b.setMinimumWidth(0);
    b.setMinimumHeight(0);
    if (click != null) b.setOnClickListener(v -> click.run());
    return b;
  }

  static void activate(ImageButton b, boolean active) {
    Context c = b.getContext();
    b.setBackground(ripple(active ? INK : Color.TRANSPARENT, 22, c));
    b.setColorFilter(active ? ON_INK : INK);
    b.setSelected(active);
  }

  static View spacer(Context c, int widthDp, int heightDp) {
    View v = new View(c);
    v.setLayoutParams(new ViewGroup.LayoutParams(dp(c, widthDp), dp(c, heightDp)));
    return v;
  }

  static View divider(Context c) {
    View v = new View(c);
    v.setBackgroundColor(LINE);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, .6f)));
    v.setLayoutParams(lp);
    return v;
  }

  static View verticalDivider(Context c) {
    View v = new View(c);
    v.setBackgroundColor(LINE);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.max(1, dp(c, .8f)), dp(c, 22));
    lp.setMargins(dp(c, 4), 0, dp(c, 4), 0);
    v.setLayoutParams(lp);
    return v;
  }

  static LinearLayout row(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  static LinearLayout column(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  static void pad(View v, Context c, float hDp, float vDp) {
    int h = dp(c, hDp), w = dp(c, vDp);
    v.setPadding(h, w, h, w);
  }

  static int coverColor(String seed) {
    int h = seed == null ? 0 : seed.hashCode();
    int[] palette = dark ? COVER_DARK : COVER;
    return palette[Math.floorMod(h, palette.length)];
  }

  /** A circular color chip with a selection ring. */
  static final class ColorChip extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int color = Color.BLACK;
    private boolean selected;

    ColorChip(Context c) {
      super(c);
      setClickable(true);
      setFocusable(true);
    }

    void setColor(int argb) { color = argb; invalidate(); }
    int color() { return color; }
    void setSelectedChip(boolean on) { selected = on; invalidate(); }

    @Override protected void onDraw(Canvas c) {
      float d = density();
      float pad = Math.max(d * (selected ? 3 : 2), 2);
      rect.set(pad, pad, getWidth() - pad, getHeight() - pad);
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(color);
      c.drawOval(rect, paint);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(d * 1.2f);
      paint.setColor(selected ? ACCENT : (dark ? 0xff4c463b : 0xffcfc6b5));
      float inset = pad + d * .6f;
      rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
      c.drawOval(rect, paint);
      if (selected) {
        paint.setStrokeWidth(d * 1.1f);
        paint.setColor(ACCENT);
        rect.set(0.5f * d, 0.5f * d, getWidth() - 0.5f * d, getHeight() - 0.5f * d);
        c.drawOval(rect, paint);
      }
    }

    private float density() { return getResources().getDisplayMetrics().density; }
  }

  static LayerDrawable sliderTrack(Context c, int background, int fill) {
    GradientDrawable bg = round(c, background, 3);
    GradientDrawable fg = round(c, fill, 3);
    ClipDrawable clip = new ClipDrawable(fg, Gravity.START, ClipDrawable.HORIZONTAL);
    int inset = dp(c, 2);
    LayerDrawable layer = new LayerDrawable(new Drawable[] {
        new InsetDrawable(bg, 0, inset, 0, inset),
        new InsetDrawable(clip, 0, inset, 0, inset)
    });
    layer.setId(0, android.R.id.background);
    layer.setId(1, android.R.id.progress);
    return layer;
  }

  static View handle(Context c) {
    View v = new View(c);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 38), dp(c, 4));
    lp.gravity = Gravity.CENTER_HORIZONTAL;
    lp.topMargin = dp(c, 10);
    v.setLayoutParams(lp);
    v.setBackground(round(c, dark ? 0xff4a4338 : 0xffd6cdbb, 2));
    return v;
  }

  static TextView sheetTitle(Context c, CharSequence title) {
    TextView t = serifTitle(c, title, 20, INK);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    t.setLayoutParams(lp);
    pad(t, c, 4, 0);
    return t;
  }

  static LinearLayout sheetHeader(Context c, CharSequence title, Runnable close) {
    LinearLayout head = row(c);
    head.setPadding(dp(c, 20), dp(c, 4), dp(c, 8), dp(c, 6));
    head.addView(sheetTitle(c, title));
    if (close != null) head.addView(icon(c, R.drawable.ic_close, "关闭", 40, MUTED, close));
    return head;
  }

  /** Bottom sheet dialog with rounded top, dim behind, bottom slide and IME resize. */
  static Dialog sheet(Activity a, View content, boolean wrapScroll) {
    Dialog d = new Dialog(a);
    d.requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout body = column(a);
    body.setBackground(roundTop(a, SURFACE, 26));
    body.addView(handle(a));
    View contentView = content;
    int navInset = 0;
    int navId = a.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
    if (navId > 0) navInset = a.getResources().getDimensionPixelSize(navId);
    if (wrapScroll) {
      // Leave room for the grabber above and the navigation bar below so nothing is clipped.
      final int max = Math.max(dp(a, 240), screenHeight(a) - navInset - dp(a, 96));
      ScrollView scroll = new ScrollView(a) {
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
          int cap = MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST);
          super.onMeasure(widthSpec, cap);
        }
      };
      scroll.setFillViewport(true);
      scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
      scroll.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      contentView = scroll;
    }
    body.addView(contentView);
    body.setPadding(0, 0, 0, navInset);
    d.setContentView(body);
    Window w = d.getWindow();
    if (w != null) {
      w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      // Tablets and foldables get a centred sheet instead of a metre-wide one.
      int wide = dp(a, 620);
      boolean capped = screenWidth(a) > wide;
      w.setLayout(capped ? wide : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      w.setGravity(capped ? (Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL) : Gravity.BOTTOM);
      w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      w.setDimAmount(.38f);
      w.setWindowAnimations(R.style.SheetAnimation);
      if (Build.VERSION.SDK_INT >= 29) w.setNavigationBarColor(SURFACE);
      w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
      w.getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
        int bottom = insets.getSystemWindowInsetBottom();
        v.setPadding(0, 0, 0, bottom);
        return insets;
      });
    }
    d.setCanceledOnTouchOutside(true);
    return d;
  }

  static void applySystemBars(Activity a) {
    Window w = a.getWindow();
    w.setStatusBarColor(BG);
    w.setNavigationBarColor(BG);
    View decor = w.getDecorView();
    int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
    if (Build.VERSION.SDK_INT >= 26 && !dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    decor.setSystemUiVisibility(flags);
  }

  static View iconTile(Context c, int res, String label, boolean danger, Runnable click) {
    LinearLayout tile = column(c);
    tile.setGravity(Gravity.CENTER);
    tile.setBackground(ripple(new GradientDrawable(), 18, c));
    tile.setClickable(true);
    tile.setFocusable(false);
    tile.setDefaultFocusHighlightEnabled(false);
    ImageView icon = new ImageView(c);
    icon.setImageResource(res);
    icon.setColorFilter(danger ? DANGER : INK);
    int box = dp(c, 46);
    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(box, box);
    ilp.gravity = Gravity.CENTER_HORIZONTAL;
    icon.setLayoutParams(ilp);
    icon.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10));
    icon.setBackground(round(c, danger ? (dark ? 0xff3b1f18 : 0xfff8e3dc) : SURFACE_SOFT, 16));
    tile.addView(icon);
    TextView text = Ui.text(c, label, 11.5f, danger ? DANGER : INK);
    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    tlp.topMargin = dp(c, 6);
    tlp.gravity = Gravity.CENTER_HORIZONTAL;
    tile.addView(text, tlp);
    tile.setContentDescription(Str.of(label));
    tile.setOnClickListener(v -> click.run());
    return tile;
  }

  /**
   * A tool choice whose selection lives on the icon chip itself: the chosen button turns into a
   * solid accent square, and nothing is drawn around the whole cell.
   */
  static LinearLayout choiceIcon(Context c, int res, String label, boolean selected, Runnable click) {
    LinearLayout cell = column(c);
    cell.setGravity(Gravity.CENTER);
    cell.setBackground(ripple(new GradientDrawable(), 16, c));
    cell.setClickable(true);
    cell.setFocusable(false);
    cell.setDefaultFocusHighlightEnabled(false);
    cell.setPadding(dp(c, 4), dp(c, 6), dp(c, 4), dp(c, 4));
    ImageView icon = new ImageView(c);
    icon.setImageResource(res);
    icon.setColorFilter(selected ? ON_INK : INK);
    int box = dp(c, 46);
    icon.setLayoutParams(new LinearLayout.LayoutParams(box, box));
    icon.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10));
    icon.setBackground(round(c, selected ? ACCENT : SURFACE_SOFT, 16));
    cell.addView(icon);
    TextView name = text(c, label, 11.5f, selected ? ACCENT : INK);
    name.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    tlp.topMargin = dp(c, 6);
    cell.addView(name, tlp);
    cell.setContentDescription(Str.of(label));
    cell.setOnClickListener(v -> click.run());
    return cell;
  }

  static LinearLayout iconRow(Context c, int res, String label, boolean danger, Runnable click) {
    LinearLayout r = row(c);
    r.setBackground(ripple(new GradientDrawable(), 14, c));
    r.setClickable(true);
    r.setFocusable(true);
    r.setPadding(dp(c, 8), dp(c, 8), dp(c, 12), dp(c, 8));
    ImageView icon = new ImageView(c);
    icon.setImageResource(res);
    icon.setColorFilter(danger ? DANGER : INK);
    int box = dp(c, 38);
    icon.setLayoutParams(new LinearLayout.LayoutParams(box, box));
    icon.setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8));
    icon.setBackground(round(c, danger ? (dark ? 0xff3b1f18 : 0xfff8e3dc) : SURFACE_SOFT, 12));
    r.addView(icon);
    TextView t = text(c, label, 15, danger ? DANGER : INK);
    LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    tl.leftMargin = dp(c, 12);
    r.addView(t, tl);
    ImageView chevron = new ImageView(c);
    chevron.setImageResource(R.drawable.ic_back);
    chevron.setRotation(180);
    chevron.setColorFilter(MUTED);
    chevron.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)));
    r.addView(chevron);
    r.setContentDescription(Str.of(label));
    r.setOnClickListener(v -> click.run());
    return r;
  }

  static View pillButton(Context c, int res, String label, int fill, int tint, Runnable click) {
    LinearLayout b = row(c);
    b.setGravity(Gravity.CENTER);
    b.setBackground(ripple(fill, 24, c));
    b.setClickable(true);
    b.setFocusable(true);
    b.setPadding(dp(c, 20), dp(c, 13), dp(c, 22), dp(c, 13));
    if (res != 0) {
      ImageView icon = new ImageView(c);
      icon.setImageResource(res);
      icon.setColorFilter(tint);
      icon.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)));
      b.addView(icon);
      TextView t = text(c, label, 14.5f, tint);
      LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      tl.leftMargin = dp(c, 8);
      b.addView(t, tl);
    } else {
      b.addView(text(c, label, 14.5f, tint));
    }
    b.setContentDescription(Str.of(label));
    b.setOnClickListener(v -> click.run());
    return b;
  }

  /**
   * A pill built to share a row evenly with its neighbours. The label stays on one line in both
   * languages, which the normal pill cannot promise for longer English words.
   */
  static View compactPill(Context c, int res, String label, int fill, int tint, Runnable click) {
    LinearLayout b = row(c);
    b.setGravity(Gravity.CENTER);
    b.setBackground(ripple(fill, 22, c));
    b.setClickable(true);
    b.setFocusable(false);
    b.setDefaultFocusHighlightEnabled(false);
    b.setPadding(dp(c, 6), dp(c, 10), dp(c, 6), dp(c, 10));
    b.setMinimumHeight(dp(c, 44));
    if (res != 0) {
      ImageView icon = new ImageView(c);
      icon.setImageResource(res);
      icon.setColorFilter(tint);
      icon.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 16), dp(c, 16)));
      b.addView(icon);
      TextView t = text(c, label, 12.5f, tint);
      t.setSingleLine(true);
      LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      tl.leftMargin = dp(c, 5);
      b.addView(t, tl);
    } else {
      TextView t = text(c, label, 13, tint);
      t.setSingleLine(true);
      b.addView(t);
    }
    b.setContentDescription(Str.of(label));
    b.setOnClickListener(v -> click.run());
    return b;
  }

  /** A selectable card used by the tool and theme choosers. */
  static LinearLayout choiceTile(Context c, View graphic, String label, String desc) {
    LinearLayout cell = column(c);
    cell.setGravity(Gravity.CENTER);
    cell.setClickable(true);
    cell.setFocusable(true);
    cell.setBackground(ripple(new GradientDrawable(), 18, c));
    cell.setPadding(dp(c, 6), dp(c, 8), dp(c, 6), dp(c, 8));
    if (graphic != null) {
      LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      cell.addView(graphic, glp);
    }
    TextView name = text(c, label, 12.5f, INK);
    name.setGravity(Gravity.CENTER);
    name.setPadding(0, dp(c, 7), 0, 0);
    cell.addView(name);
    if (desc != null) {
      TextView sub = text(c, desc, 10, MUTED);
      sub.setGravity(Gravity.CENTER);
      sub.setPadding(0, dp(c, 2), 0, 0);
      cell.addView(sub);
    }
    cell.setContentDescription(Str.of(label));
    return cell;
  }

  static void setChosen(View tile, boolean chosen) {
    tile.setBackground(ripple(chosen ? ACCENT_SOFT : SURFACE_SOFT, 18, tile.getContext()));
    tile.setSelected(chosen);
  }

  /** Local callback for the switch rows; avoids dragging a widget listener into the callers. */
  interface Toggle { void onChanged(boolean checked); }

  static View switchRow(Context c, int res, String title, String subtitle, boolean checked,
                        Toggle onChange) {
    LinearLayout r = row(c);
    r.setBackground(ripple(new GradientDrawable(), 16, c));
    r.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
    if (res != 0) {
      ImageView icon = new ImageView(c);
      icon.setImageResource(res);
      icon.setColorFilter(INK);
      icon.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 36), dp(c, 36)));
      icon.setPadding(dp(c, 7), dp(c, 7), dp(c, 7), dp(c, 7));
      icon.setBackground(round(c, SURFACE_SOFT, 11));
      r.addView(icon);
    }
    LinearLayout labels = column(c);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    lp.leftMargin = dp(c, 12);
    labels.setLayoutParams(lp);
    labels.addView(text(c, title, 15, INK));
    if (subtitle != null) {
      TextView sub = text(c, subtitle, 11.5f, MUTED);
      sub.setPadding(0, dp(c, 2), 0, 0);
      labels.addView(sub);
    }
    r.addView(labels);
    android.widget.Switch toggle = new android.widget.Switch(c);
    toggle.setChecked(checked);
    toggle.setButtonTintList(null);
    toggle.setThumbTintList(ColorStateList.valueOf(checked ? ACCENT : MUTED));
    toggle.setTrackTintList(ColorStateList.valueOf(dark ? 0xff4a4338 : 0xffd8d0bf));
    toggle.setBackground(null);
    toggle.setPadding(0, 0, 0, 0);
    final Toggle callback = onChange;
    toggle.setOnCheckedChangeListener((button, value) -> {
      toggle.setThumbTintList(ColorStateList.valueOf(value ? ACCENT : MUTED));
      if (callback != null) callback.onChanged(value);
    });
    r.addView(toggle);
    return r;
  }

  static TextView sectionLabel(Context c, CharSequence value) {
    TextView t = label(c, value, 10, MUTED);
    t.setPadding(dp(c, 6), dp(c, 18), 0, dp(c, 8));
    return t;
  }

  /**
   * A grid whose column count follows its own width, so phones, foldables and tablets each get
   * cards that stay a sensible size, including after a rotation without rebuilding the activity.
   */
  static android.widget.GridView grid(Context c, final int minCellDp, final int minColumns, final int maxColumns) {
    return new android.widget.GridView(c) {
      @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int cell = Math.max(1, dp(getContext(), minCellDp));
        int columns = Math.max(minColumns, Math.min(maxColumns, Math.max(1, w / cell)));
        if (columns != getNumColumns()) setNumColumns(columns);
        // Without an explicit column width the grid sizes columns from its first child, which
        // shrank the shelf cards to the width of their placeholder. Fill the row instead.
        int available = Math.max(1, w - getPaddingLeft() - getPaddingRight());
        int width = Math.max(1, available / Math.max(1, columns));
        if (Math.abs(width - getColumnWidth()) > 1) setColumnWidth(width);
      }
    };
  }
}
