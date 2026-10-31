package org.gbamax.emu;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Menu estilo launcher premium: barra superior em vidro, grade de 3 colunas e dock suspensa. */
public class MenuView extends FrameLayout {

    public interface Host { void play(Game g); void pickDir(); void refresh(); }

    public static class Game { public String name; public Uri uri, cover; }

    final Host host;
    final float den;
    final List<Game> games = new ArrayList<>();
    String selected;
    boolean hasDir;
    int cols = 3, cw = 360;

    GridView grid;
    BaseAdapter adapter;
    TextView empty, coins, batTxt;
    DotsView dots;
    BatteryView bat;
    LinearLayout top, dock;

    final LruCache<String, Bitmap> cache = new LruCache<>(60);
    final Set<String> busy = Collections.synchronizedSet(new HashSet<String>());
    final Set<String> failed = Collections.synchronizedSet(new HashSet<String>());
    final ExecutorService ex = Executors.newFixedThreadPool(2);

    final BroadcastReceiver br = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int lv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean ch = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            int pct = sc > 0 && lv >= 0 ? lv * 100 / sc : 0;
            bat.set(pct, ch);
            batTxt.setText(pct + "%");
        }
    };

    int dp(float v) { return (int) (v * den + .5f); }

    static int mix(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) * (1 - t) + Color.red(b) * t),
                (int) (Color.green(a) * (1 - t) + Color.green(b) * t),
                (int) (Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    GradientDrawable glass(float rad) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF5FFFFFF, 0xE6E4EAF3});
        g.setCornerRadius(rad);
        g.setStroke(dp(1.5f), 0xFFFFFFFF);
        return g;
    }

    public MenuView(android.content.Context c, Host h) {
        super(c);
        host = h;
        den = c.getResources().getDisplayMetrics().density;

        addView(new Bg(c), new LayoutParams(-1, -1));

        // ---------- biblioteca ----------
        grid = new GridView(c);
        grid.setVerticalSpacing(dp(12));
        grid.setHorizontalSpacing(0);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setClipToPadding(false);
        grid.setSelector(new ColorDrawable(0));
        grid.setOverScrollMode(OVER_SCROLL_NEVER);
        grid.setVerticalScrollBarEnabled(true);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return games.size(); }
            @Override public Object getItem(int i) { return games.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, android.view.ViewGroup p) {
                Cell cell = v instanceof Cell ? (Cell) v : new Cell(getContext());
                Game g = games.get(i);
                cell.bind(g, g.name.equals(selected));
                return cell;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, i, id) -> {
            Game g = games.get(i);
            selected = g.name;
            adapter.notifyDataSetChanged();
            host.play(g);
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) { }
            @Override public void onScroll(AbsListView v, int first, int vis, int total) {
                int idx = 0;
                if (total > vis) idx = Math.round(first * 4f / Math.max(1, total - vis));
                if (dots != null) dots.set(Math.max(0, Math.min(4, idx)));
            }
        });
        addView(grid, new LayoutParams(-1, -1));

        empty = new TextView(c);
        empty.setTextColor(0xFF5C6678);
        empty.setTextSize(16);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(32), 0, dp(32), 0);
        addView(empty, new LayoutParams(-1, -2, Gravity.CENTER));

        // ---------- barra superior ----------
        top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), 0, dp(16), 0);
        top.setBackground(glass(dp(27)));
        top.setElevation(dp(8));

        top.addView(new IconView(c, Icons.WIFI, 0xFF1E6BE6), new LinearLayout.LayoutParams(dp(22), dp(22)));

        TextView net = new TextView(c);
        net.setText("Internet");
        net.setTextColor(Color.WHITE);
        net.setTextSize(12);
        net.setTypeface(Typeface.DEFAULT_BOLD);
        net.setGravity(Gravity.CENTER);
        net.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable ng = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF5B9BFF, 0xFF1556D6});
        ng.setCornerRadius(dp(15));
        ng.setStroke(dp(1), 0x99FFFFFF);
        net.setBackground(ng);
        net.setElevation(dp(3));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, dp(30));
        nl.leftMargin = dp(8);
        top.addView(net, nl);

        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(22), dp(22));
        cl.leftMargin = dp(10);
        top.addView(new IconView(c, Icons.COIN, 0xFFFFB300), cl);
        coins = new TextView(c);
        coins.setTextColor(0xFF1B1F27);
        coins.setTextSize(13);
        coins.setTypeface(Typeface.DEFAULT_BOLD);
        coins.setText("0");
        LinearLayout.LayoutParams ctl = new LinearLayout.LayoutParams(-2, -2);
        ctl.leftMargin = dp(4);
        top.addView(coins, ctl);

        top.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        TextClock tc = new TextClock(c);
        tc.setFormat12Hour("MMM d, yyyy\nh:mm a");
        tc.setFormat24Hour("MMM d, yyyy\nHH:mm");
        tc.setTextSize(11);
        tc.setTypeface(Typeface.DEFAULT_BOLD);
        tc.setTextColor(0xFF1B1F27);
        tc.setGravity(Gravity.CENTER);
        top.addView(tc, new LinearLayout.LayoutParams(-2, -2));
        top.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));

        top.addView(new IconView(c, Icons.HEAD, 0xFF1B1F27), new LinearLayout.LayoutParams(dp(22), dp(22)));
        batTxt = new TextView(c);
        batTxt.setTextColor(0xFF1B1F27);
        batTxt.setTextSize(12);
        batTxt.setTypeface(Typeface.DEFAULT_BOLD);
        batTxt.setText("100%");
        LinearLayout.LayoutParams btl = new LinearLayout.LayoutParams(-2, -2);
        btl.leftMargin = dp(6);
        top.addView(batTxt, btl);
        bat = new BatteryView(c);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(28), dp(14));
        bl.leftMargin = dp(5);
        top.addView(bat, bl);

        LayoutParams tl = new LayoutParams(-1, dp(54), Gravity.TOP);
        tl.setMargins(dp(12), dp(12), dp(12), 0);
        addView(top, tl);

        // ---------- dock ----------
        dock = new LinearLayout(c);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setClipChildren(false);
        dock.setPadding(dp(8), 0, dp(8), 0);
        dock.setBackground(glass(dp(34)));
        dock.setElevation(dp(12));
        int[] ty = {Icons.HOME, Icons.CHAT, Icons.BAG, Icons.LIB, Icons.FACE, Icons.PAD, Icons.GEAR};
        int[] bg = {0xFFFFFFFF, 0xFFE53935, 0xFFFFA000, 0xFF1E6BE6, 0xFF2EAD4B, 0xFFF2F4F8, 0xFF3A3C42};
        int[] ic = {0xFF111111, Color.WHITE, Color.WHITE, Color.WHITE, Color.WHITE, 0xFF2A2F3A, Color.WHITE};
        for (int i = 0; i < 7; i++) {
            DockBtn b = new DockBtn(c, ty[i], bg[i], ic[i], i == 0);
            final int k = i;
            b.setOnClickListener(v -> dockAction(k));
            dock.addView(b, new LinearLayout.LayoutParams(0, dp(56), 1f));
        }
        LayoutParams dl = new LayoutParams(-1, dp(68), Gravity.BOTTOM);
        dl.setMargins(dp(14), 0, dp(14), dp(36));
        addView(dock, dl);

        dots = new DotsView(c);
        LayoutParams pl = new LayoutParams(dp(72), dp(14), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pl.bottomMargin = dp(12);
        addView(dots, pl);

        relayout();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent s;
        if (Build.VERSION.SDK_INT >= 33) s = getContext().registerReceiver(br, f, Context.RECEIVER_NOT_EXPORTED);
        else s = getContext().registerReceiver(br, f);
        if (s != null) br.onReceive(getContext(), s);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        try { getContext().unregisterReceiver(br); } catch (Exception e) { }
    }

    public void relayout() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean land = dm.widthPixels > dm.heightPixels;
        cols = land ? 5 : 3;
        int pad = dp(10);
        cw = (dm.widthPixels - 2 * pad) / cols;
        grid.setNumColumns(cols);
        grid.setPadding(pad, dp(82), pad, dp(124));
        LayoutParams tl = (LayoutParams) top.getLayoutParams();
        tl.width = land ? dp(640) : -1;
        tl.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        top.setLayoutParams(tl);
        LayoutParams dl = (LayoutParams) dock.getLayoutParams();
        dl.width = land ? dp(520) : -1;
        dl.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        dock.setLayoutParams(dl);
        adapter.notifyDataSetChanged();
    }

    public void setGames(List<Game> l, String sel, boolean dir) {
        games.clear();
        games.addAll(l);
        hasDir = dir;
        selected = sel;
        if (selected == null && !games.isEmpty()) selected = games.get(0).name;
        coins.setText(String.valueOf(games.size()));
        empty.setText(dir ? "Nenhum jogo .zip encontrado.\nColoque seus jogos na pasta GBA-MAX\ndo armazenamento interno."
                : "Toque em Biblioteca (ícone azul)\npara escolher a pasta de ROMs.");
        empty.setVisibility(games.isEmpty() ? VISIBLE : GONE);
        adapter.notifyDataSetChanged();
    }

    void toast(String s) { Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show(); }

    void dockAction(int k) {
        switch (k) {
            case 0: grid.smoothScrollToPosition(0); break;
            case 3: host.pickDir(); break;
            case 6:
                new AlertDialog.Builder(getContext())
                        .setTitle("GBA-MAX")
                        .setItems(new String[]{"Selecionar pasta de ROMs", "Atualizar lista"}, (d, w) -> {
                            if (w == 0) host.pickDir(); else host.refresh();
                        }).show();
                break;
            default: toast("Em breve");
        }
    }

    void load(Uri u) {
        if (u == null) return;
        final String k = u.toString();
        if (cache.get(k) != null || failed.contains(k) || !busy.add(k)) return;
        ex.execute(() -> {
            Bitmap b = decode(u);
            if (b != null) cache.put(k, b); else failed.add(k);
            busy.remove(k);
            if (b != null) post(() -> adapter.notifyDataSetChanged());
        });
    }

    Bitmap decode(Uri u) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContext().getContentResolver().openInputStream(u)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int s = 1;
            while (o.outWidth / (s * 2) >= cw) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            try (InputStream in = getContext().getContentResolver().openInputStream(u)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception e) { return null; }
    }

    // ================= fundo =================
    class Bg extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bg(Context c) { super(c); }

        void sw(Canvas c, int col, float wd, float x0, float y0, float a, float b, float x1, float y1, float x2, float y2) {
            Path path = new Path();
            path.moveTo(x0, y0);
            path.cubicTo(a, b, x1, y1, x2, y2);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(wd);
            p.setColor(col);
            c.drawPath(path, p);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, 0, w * .4f, h, 0xFFF8FAFD, 0xFFDCE3EE, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            sw(c, 0x408FA3C4, w * .16f, -.1f * w, .14f * h, .3f * w, .18f * h, .6f * w, .05f * h, .9f * w, -.05f * h);
            sw(c, 0x552F63DB, w * .12f, -.1f * w, .62f * h, .1f * w, .62f * h, .3f * w, .52f * h, .45f * w, .40f * h);
            sw(c, 0x662F63DB, w * .10f, 1.1f * w, .10f * h, .9f * w, .12f * h, .8f * w, .20f * h, .7f * w, .30f * h);
            sw(c, 0x408FA3C4, w * .12f, 1.1f * w, .78f * h, .9f * w, .80f * h, .8f * w, .88f * h, .65f * w, .98f * h);
            sw(c, 0x338FA3C4, w * .14f, -.1f * w, .85f * h, .2f * w, .82f * h, .3f * w, .90f * h, .45f * w, 1.05f * h);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(w * .5f, h * .18f, w * .9f, 0xB3FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
        }
    }

    // ================= célula do jogo =================
    class Cell extends LinearLayout {
        final CoverView cv;
        final TextView t1, t2;
        final LinearLayout plate;

        Cell(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setClipChildren(false);
            cv = new CoverView(c);
            addView(cv, new LayoutParams(-1, dp(120)));

            plate = new LinearLayout(c);
            plate.setOrientation(VERTICAL);
            plate.setGravity(Gravity.CENTER);
            plate.setPadding(dp(6), dp(5), dp(6), dp(6));
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFAFFFFFF, 0xF2E6ECF5});
            g.setCornerRadius(dp(14));
            g.setStroke(dp(1), 0xCCFFFFFF);
            plate.setBackground(g);
            plate.setElevation(dp(4));

            t1 = new TextView(c);
            t1.setTextColor(0xFF1B1F27);
            t1.setTextSize(12);
            t1.setTypeface(Typeface.DEFAULT_BOLD);
            t1.setSingleLine(true);
            t1.setEllipsize(TextUtils.TruncateAt.END);
            t1.setGravity(Gravity.CENTER);
            t2 = new TextView(c);
            t2.setTextColor(0xFF8A93A3);
            t2.setTextSize(10.5f);
            t2.setSingleLine(true);
            t2.setGravity(Gravity.CENTER);
            t2.setText("Game Boy Advance");
            plate.addView(t1, new LayoutParams(-1, -2));
            plate.addView(t2, new LayoutParams(-1, -2));

            LayoutParams pl = new LayoutParams(-1, -2);
            pl.setMargins(dp(10), -dp(14), dp(10), dp(4));
            addView(plate, pl);
        }

        void bind(Game g, boolean sel) {
            LayoutParams lp = (LayoutParams) cv.getLayoutParams();
            int hh = (int) (cw * .88f);
            if (lp.height != hh) { lp.height = hh; cv.setLayoutParams(lp); }
            t1.setText(g.name);
            cv.bind(g, sel);
        }
    }

    class CoverView extends View {
        Game g;
        Bitmap bm;
        boolean sel;
        StaticLayout sl;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        final Path rr = new Path();
        final Matrix mx = new Matrix();

        CoverView(Context c) {
            super(c);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) {
                    int in = dp(6);
                    o.setRoundRect(in, in, v.getWidth() - in, v.getHeight() - in, dp(18));
                }
            });
        }

        void bind(Game g, boolean s) {
            this.g = g;
            sel = s;
            sl = null;
            bm = g.cover == null ? null : cache.get(g.cover.toString());
            if (bm == null) load(g.cover);
            setElevation(dp(s ? 16 : 8));
            setTranslationY(s ? -dp(3) : 0);
            if (Build.VERSION.SDK_INT >= 28) {
                setOutlineSpotShadowColor(s ? 0xFF2F7BFF : 0xFF2B3A55);
                setOutlineAmbientShadowColor(0xFF2B3A55);
            }
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { sl = null; }

        @Override protected void onDraw(Canvas c) {
            if (g == null) return;
            float w = getWidth(), h = getHeight(), in = dp(6), rad = dp(18);
            r.set(in, in, w - in, h - in);
            if (sel) {
                line.setStyle(Paint.Style.STROKE);
                int[] ws = {12, 9, 6, 3};
                int[] al = {22, 40, 70, 110};
                for (int k = 0; k < 4; k++) {
                    line.setStrokeWidth(dp(ws[k]));
                    line.setColor(Color.argb(al[k], 47, 123, 255));
                    c.drawRoundRect(r, rad, rad, line);
                }
            }
            rr.reset();
            rr.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.save();
            c.clipPath(rr);
            fill.setStyle(Paint.Style.FILL);
            if (bm != null) {
                float s = Math.max(r.width() / bm.getWidth(), r.height() / bm.getHeight());
                mx.setScale(s, s);
                mx.postTranslate(r.left + (r.width() - bm.getWidth() * s) / 2,
                        r.top + (r.height() - bm.getHeight() * s) / 2);
                BitmapShader sh = new BitmapShader(bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                sh.setLocalMatrix(mx);
                fill.setShader(sh);
                c.drawRect(r, fill);
            } else {
                int hh = (g.name.hashCode() & 0x7fffffff) % 360;
                float[] hsv = {hh, .6f, .95f};
                int c1 = Color.HSVToColor(hsv);
                hsv[0] = (hh + 45) % 360;
                hsv[1] = .85f;
                hsv[2] = .5f;
                int c2 = Color.HSVToColor(hsv);
                fill.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, c1, c2, Shader.TileMode.CLAMP));
                c.drawRect(r, fill);
                if (sl == null) {
                    String t = g.name.replaceAll("\\s*[\\(\\[].*?[\\)\\]]", "").trim();
                    if (t.isEmpty()) t = g.name;
                    TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                    tp.setColor(Color.WHITE);
                    tp.setTypeface(Typeface.DEFAULT_BOLD);
                    tp.setTextSize(r.width() * .14f);
                    tp.setShadowLayer(4, 0, 2, 0x88000000);
                    sl = StaticLayout.Builder.obtain(t, 0, t.length(), tp, Math.max(10, (int) (r.width() - dp(16))))
                            .setAlignment(Layout.Alignment.ALIGN_CENTER)
                            .setMaxLines(4)
                            .setEllipsize(TextUtils.TruncateAt.END)
                            .build();
                }
                c.save();
                c.translate(r.left + dp(8), r.centerY() - sl.getHeight() / 2f);
                sl.draw(c);
                c.restore();
            }
            // reflexo de luz no topo
            fill.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * .5f,
                    0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(r.left, r.top, r.right, r.top + r.height() * .5f, fill);
            fill.setShader(null);
            c.restore();
            // moldura
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(sel ? 3 : 2));
            line.setColor(sel ? 0xFF2F7BFF : 0xCCFFFFFF);
            c.drawRoundRect(r, rad, rad, line);
            if (sel) {
                line.setStrokeWidth(dp(1));
                line.setColor(0xAAFFFFFF);
                c.drawRoundRect(r.left + dp(3), r.top + dp(3), r.right - dp(3), r.bottom - dp(3), rad - dp(3), rad - dp(3), line);
            }
        }
    }

    // ================= dock =================
    class DockBtn extends View {
        final int type, col, icol;
        final boolean sel;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        DockBtn(Context c, int type, int col, int icol, boolean sel) {
            super(c);
            this.type = type; this.col = col; this.icol = icol; this.sel = sel;
            setElevation(dp(sel ? 10 : 5));
            if (sel) { setScaleX(1.1f); setScaleY(1.1f); }
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) {
                    float r = Math.min(v.getWidth(), v.getHeight()) / 2f - dp(3);
                    float cx = v.getWidth() / 2f, cy = v.getHeight() / 2f;
                    o.setOval((int) (cx - r), (int) (cy - r), (int) (cx + r), (int) (cy + r));
                }
            });
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
            float r = Math.min(w, h) / 2f - dp(3);
            if (sel) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(3));
                p.setColor(0x802F7BFF);
                c.drawCircle(cx, cy, r + dp(2), p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, cy - r, 0, cy + r, mix(col, Color.WHITE, .35f),
                    mix(col, Color.BLACK, .18f), Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
            p.setShader(new LinearGradient(0, cy - r, 0, cy, 0xAAFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawOval(new RectF(cx - r * .72f, cy - r * .94f, cx + r * .72f, cy - r * .05f), p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.2f));
            p.setColor(0xCCFFFFFF);
            c.drawCircle(cx, cy, r - dp(.6f), p);
            Icons.draw(c, type, cx, cy, r * .5f, icol, p);
        }
    }

    class DotsView extends View {
        int idx;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        DotsView(Context c) { super(c); }
        void set(int i) { if (i != idx) { idx = i; invalidate(); } }
        @Override protected void onDraw(Canvas c) {
            float step = getWidth() / 5f;
            for (int i = 0; i < 5; i++) {
                p.setColor(i == idx ? 0xFF263042 : 0xFFB7BFCC);
                c.drawCircle((i + .5f) * step, getHeight() / 2f, dp(i == idx ? 5 : 4), p);
            }
        }
    }

    class BatteryView extends View {
        int pct = 100;
        boolean chg;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        BatteryView(Context c) { super(c); }
        void set(int a, boolean b) { pct = a; chg = b; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), nub = den * 3;
            RectF body = new RectF(den, den, w - nub - den, h - den);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(den * 1.5f);
            p.setColor(0xFF1B1F27);
            c.drawRoundRect(body, den * 4, den * 4, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(chg ? 0xFF2EAD4B : pct <= 15 ? 0xFFE53935 : 0xFF1B1F27);
            float l = body.left + den * 2.5f, t = body.top + den * 2.5f, b = body.bottom - den * 2.5f;
            float rgt = l + (body.width() - den * 5f) * Math.max(3, pct) / 100f;
            c.drawRoundRect(l, t, rgt, b, den * 2, den * 2, p);
            c.drawRoundRect(w - nub, h * .3f, w, h * .7f, den * 1.5f, den * 1.5f, p);
        }
    }

    class IconView extends View {
        final int type, col;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        IconView(Context c, int type, int col) { super(c); this.type = type; this.col = col; }
        @Override protected void onDraw(Canvas c) {
            Icons.draw(c, type, getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) / 2f * .9f, col, p);
        }
    }

    // ================= ícones desenhados em código =================
    static class Icons {
        static final int WIFI = 0, HEAD = 1, HOME = 2, CHAT = 3, BAG = 4, LIB = 5, FACE = 6, PAD = 7, GEAR = 8, COIN = 9;

        static void draw(Canvas c, int t, float x, float y, float r, int col, Paint p) {
            p.setShader(null);
            p.setColor(col);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            RectF q = new RectF();
            switch (t) {
                case WIFI:
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * .24f);
                    for (int k = 1; k <= 3; k++) {
                        float rr = r * .42f * k;
                        q.set(x - rr, y + r * .65f - rr, x + rr, y + r * .65f + rr);
                        c.drawArc(q, -135, 90, false, p);
                    }
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(x, y + r * .65f, r * .16f, p);
                    break;
                case HEAD:
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * .2f);
                    q.set(x - .78f * r, y - .9f * r, x + .78f * r, y + .66f * r);
                    c.drawArc(q, 180, 180, false, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawRoundRect(x - .98f * r, y - .02f * r, x - .5f * r, y + .8f * r, r * .2f, r * .2f, p);
                    c.drawRoundRect(x + .5f * r, y - .02f * r, x + .98f * r, y + .8f * r, r * .2f, r * .2f, p);
                    break;
                case HOME: {
                    Path h = new Path();
                    h.moveTo(x, y - .95f * r);
                    h.lineTo(x + r, y - .05f * r);
                    h.lineTo(x + .78f * r, y - .05f * r);
                    h.lineTo(x + .78f * r, y + .85f * r);
                    h.lineTo(x + .22f * r, y + .85f * r);
                    h.lineTo(x + .22f * r, y + .3f * r);
                    h.lineTo(x - .22f * r, y + .3f * r);
                    h.lineTo(x - .22f * r, y + .85f * r);
                    h.lineTo(x - .78f * r, y + .85f * r);
                    h.lineTo(x - .78f * r, y - .05f * r);
                    h.lineTo(x - r, y - .05f * r);
                    h.close();
                    p.setStyle(Paint.Style.FILL_AND_STROKE);
                    p.setStrokeWidth(r * .14f);
                    c.drawPath(h, p);
                    break;
                }
                case CHAT: {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * .15f);
                    c.drawRoundRect(x - .9f * r, y - .8f * r, x + .9f * r, y + .5f * r, r * .3f, r * .3f, p);
                    Path tl = new Path();
                    tl.moveTo(x - .35f * r, y + .5f * r);
                    tl.lineTo(x - .45f * r, y + .95f * r);
                    tl.lineTo(x + .1f * r, y + .5f * r);
                    c.drawPath(tl, p);
                    c.drawLine(x - .45f * r, y - .35f * r, x + .45f * r, y - .35f * r, p);
                    c.drawLine(x - .45f * r, y, x + .2f * r, y, p);
                    break;
                }
                case BAG:
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * .15f);
                    c.drawRoundRect(x - .8f * r, y - .3f * r, x + .8f * r, y + .85f * r, r * .22f, r * .22f, p);
                    q.set(x - .45f * r, y - .78f * r, x + .45f * r, y + .12f * r);
                    c.drawArc(q, 180, 180, false, p);
                    break;
                case LIB:
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * .15f);
                    c.drawRoundRect(x - .85f * r, y - .85f * r, x + .85f * r, y + .85f * r, r * .25f, r * .25f, p);
                    c.drawLine(x - .4f * r, y + .5f * r, x - .4f * r, y - .2f * r, p);
                    c.drawLine(x, y + .5f * r, x, y - .5f * r, p);
                    c.drawLine(x + .4f * r, y + .5f * r, x + .4f * r, y - .2f * r, p);
                    break;
                case FACE:
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * .15f);
                    c.drawRoundRect(x - .85f * r, y - .85f * r, x + .85f * r, y + .85f * r, r * .3f, r * .3f, p);
                    q.set(x - .45f * r, y - .35f * r, x + .45f * r, y + .5f * r);
                    c.drawArc(q, 25, 130, false, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(x - .3f * r, y - .25f * r, r * .1f, p);
                    c.drawCircle(x + .3f * r, y - .25f * r, r * .1f, p);
                    break;
                case PAD:
                    c.save();
                    c.rotate(-40, x, y);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * .15f);
                    c.drawRoundRect(x - r, y - .5f * r, x + r, y + .5f * r, r * .5f, r * .5f, p);
                    c.drawLine(x - .65f * r, y, x - .15f * r, y, p);
                    c.drawLine(x - .4f * r, y - .25f * r, x - .4f * r, y + .25f * r, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(x + .5f * r, y, r * .13f, p);
                    c.restore();
                    break;
                case GEAR:
                    p.setStyle(Paint.Style.FILL);
                    for (int i = 0; i < 8; i++) {
                        c.save();
                        c.rotate(i * 45, x, y);
                        c.drawRoundRect(x - .17f * r, y - r, x + .17f * r, y - .5f * r, r * .08f, r * .08f, p);
                        c.restore();
                    }
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * .34f);
                    c.drawCircle(x, y, r * .62f, p);
                    break;
                case COIN:
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(x, y, r, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * .16f);
                    p.setColor(0xFFE08A00);
                    c.drawCircle(x, y, r * .72f, p);
                    break;
            }
        }
    }
}
