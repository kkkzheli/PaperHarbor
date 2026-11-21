package app.paperharbor.notes;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
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

/** Shared visual language: warm paper, deep green ink, terracotta accents, icon-first controls. */
final class Ui {
  static final int BG = 0xfff3eee4;
  static final int SURFACE = 0xfffffcf5;
  static final int SURFACE_SOFT = 0xffece5d6;
  static final int INK = 0xff17322c;
  static final int MUTED = 0xff6f7a73;
  static final int LINE = 0xffe0d7c5;
  static final int ACCENT = 0xffcf613b;
  static final int ACCENT_SOFT = 0xffffe6da;
  static final int DANGER = 0xffb4452f;
  static final int CANVAS = 0xffe7e0d3;
  static final int WHITE = 0xffffffff;
  private static final int[] COVER = {
    0xff365a4f, 0xff7a4b42, 0xff3f5b76, 0xff7b6440, 0xff5d4f6b, 0xff4c6b6a
  };
  private Ui() {}

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
    t.setText(value);
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
    return new RippleDrawable(ColorStateList.valueOf(0x2417322c), content, null);
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
    b.setContentDescription(desc);
    b.setMinimumWidth(0);
    b.setMinimumHeight(0);
    if (click != null) b.setOnClickListener(v -> click.run());
    return b;
  }

  static void activate(ImageButton b, boolean active) {
    Context c = b.getContext();
    b.setBackground(ripple(active ? INK : Color.TRANSPARENT, 22, c));
    b.setColorFilter(active ? WHITE : INK);
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
    return COVER[Math.floorMod(h, COVER.length)];
  }

  /** A circular color chip with a selection ring. */
  static final class ColorChip extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int color = Color.BLACK;
    private boolean selected;
    private boolean light;
    private float ring = 2;

    ColorChip(Context c) {
      super(c);
      setClickable(true);
      setFocusable(true);
    }

    void setColor(int argb) { color = argb; invalidate(); }
    int color() { return color; }
    void setSelectedChip(boolean on) { selected = on; invalidate(); }

    @Override protected void onDraw(Canvas c) {
      float pad = Math.max(density() * (selected ? 3 : 2), ring);
      rect.set(pad, pad, getWidth() - pad, getHeight() - pad);
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(color);
      c.drawOval(rect, paint);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(density() * 1.2f);
      paint.setColor(selected ? Ui.ACCENT : (0xffcfc6b5));
      float inset = pad + density() * .6f;
      rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
      c.drawOval(rect, paint);
      if (selected) {
        paint.setStrokeWidth(density() * 1.1f);
        paint.setColor(Ui.ACCENT);
        rect.set(0.5f * density(), 0.5f * density(), getWidth() - 0.5f * density(), getHeight() - 0.5f * density());
        c.drawOval(rect, paint);
      }
      if (light) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(0x33000000);
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

  static View handle(Context c) {    View v = new View(c);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 38), dp(c, 4));
    lp.gravity = Gravity.CENTER_HORIZONTAL;
    lp.topMargin = dp(c, 10);
    v.setLayoutParams(lp);
    v.setBackground(round(c, 0xffd6cdbb, 2));
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
    if (wrapScroll) {
      final int max = (int) (screenHeight(a) * .90f);
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
    int nav = 0;
    int navId = a.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
    if (navId > 0) nav = a.getResources().getDimensionPixelSize(navId);
    body.setPadding(0, 0, 0, nav);
    d.setContentView(body);
    Window w = d.getWindow();
    if (w != null) {
      w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      w.setGravity(Gravity.BOTTOM);
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
    int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
    if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    decor.setSystemUiVisibility(flags);
  }

  static View iconTile(Context c, int res, String label, boolean danger, Runnable click) {
    LinearLayout tile = column(c);
    tile.setGravity(Gravity.CENTER);
    tile.setBackground(ripple(new GradientDrawable(), 18, c));
    tile.setClickable(true);
    tile.setFocusable(true);
    ImageView icon = new ImageView(c);
    icon.setImageResource(res);
    icon.setColorFilter(danger ? DANGER : INK);
    int box = dp(c, 46);
    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(box, box);
    ilp.gravity = Gravity.CENTER_HORIZONTAL;
    icon.setLayoutParams(ilp);
    icon.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10));
    icon.setBackground(round(c, danger ? 0xfff8e3dc : SURFACE_SOFT, 16));
    tile.addView(icon);
    TextView text = Ui.text(c, label, 11.5f, danger ? DANGER : INK);
    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    tlp.topMargin = dp(c, 6);
    tlp.gravity = Gravity.CENTER_HORIZONTAL;
    tile.addView(text, tlp);
    tile.setContentDescription(label);
    tile.setOnClickListener(v -> click.run());
    return tile;
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
    icon.setBackground(round(c, danger ? 0xfff8e3dc : SURFACE_SOFT, 12));
    r.addView(icon);
    TextView t = text(c, label, 15, danger ? DANGER : INK);
    LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    tl.leftMargin = dp(c, 12);
    r.addView(t, tl);
    ImageView chevron = new ImageView(c);
    chevron.setImageResource(R.drawable.ic_back);
    chevron.setRotation(180);
    chevron.setColorFilter(0xff9b9486);
    chevron.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)));
    r.addView(chevron);
    r.setContentDescription(label);
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
    b.setContentDescription(label);
    b.setOnClickListener(v -> click.run());
    return b;
  }
}
