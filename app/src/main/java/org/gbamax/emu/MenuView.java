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

    public interface Host {
        void play(Game g); void pickDir(); void refresh();
        void videoChanged(); void editLayout(); void importShaders();
    }

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
    Bg bgView;
    Bitmap customBg; // fundo personalizado (GBA-MAX/Personalizar/fundo.png)

    void setCustomBg(Bitmap b) {
        customBg = b;
        if (bgView != null) bgView.invalidate();
    }

    final LruCache<String, Bitmap> cache = new LruCache<>(60);
    final LruCache<String, Bitmap> blur = new LruCache<>(60);

    Bitmap blurOf(String key, Bitmap src) {
        Bitmap b = blur.get(key);
        if (b != null) return b;
        try {
            int w = Math.max(4, src.getWidth() / 20), h = Math.max(4, src.getHeight() / 20);
            Bitmap s1 = Bitmap.createScaledBitmap(src, w, h, true);
            Bitmap s2 = Bitmap.createScaledBitmap(s1, w * 4, h * 4, true);
            if (s1 != s2 && s1 != src) s1.recycle();
            blur.put(key, s2);
            return s2;
        } catch (Throwable t) {
            return src;
        }
    }
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
                new int[]{Tema.get(Tema.VIDRO_TOPO), Tema.get(Tema.VIDRO_BASE)});
        g.setCornerRadius(rad);
        g.setStroke(dp(Tema.grossura), Tema.get(Tema.CONTORNO));
        return g;
    }

    public MenuView(android.content.Context c, Host h) {
        super(c);
        host = h;
        den = c.getResources().getDisplayMetrics().density;

        bgView = new Bg(c);
        addView(bgView, new LayoutParams(-1, -1));

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
            if (getContext() instanceof MainActivity) new GameMenu((MainActivity) getContext()).gameWindow(g); else host.play(g);
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

        empty = new Txt(c);
        empty.setTextColor(0xFF5C6678);
        empty.setTextSize(16);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(32), 0, dp(32), 0);
        addView(empty, new LayoutParams(-1, -2, Gravity.CENTER));

        // ---------- barra superior ----------
        top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), 0, dp(9), 0);
        top.setBackground(glass(dp(23)));
        top.setElevation(dp(8));

        TextView net = new Txt(c);
        net.setText("Internet");
        net.setTextColor(Color.WHITE);
        net.setTextSize(12);
        net.setTypeface(Typeface.DEFAULT_BOLD);
        net.setGravity(Gravity.CENTER);
        net.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable ng = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF5B9BFF, 0xFF1556D6});
        ng.setCornerRadius(dp(15));
        ng.setStroke(dp(1), Tema.get(Tema.LINHA));
        net.setBackground(ng);
        net.setElevation(dp(3));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, dp(30));
        nl.leftMargin = dp(8);

        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(22), dp(22));
        cl.leftMargin = dp(10);
        coins = new Txt(c);
        coins.setTextColor(Tema.get(Tema.TEXTO));
        coins.setTextSize(13);
        coins.setTypeface(Typeface.DEFAULT_BOLD);
        coins.setText("0");
        LinearLayout.LayoutParams ctl = new LinearLayout.LayoutParams(-2, -2);
        ctl.leftMargin = dp(4);

        top.addView(new IconView(c, Icons.CAL, Tema.get(Tema.TEXTO)), new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextClock td = new Rel(c) {
            @Override public void setText(CharSequence t, TextView.BufferType b) {
                super.setText(t == null ? null : t.toString().toUpperCase(java.util.Locale.ROOT), b);
            }
        };
        td.setFormat12Hour("dd/MMM/yyyy");
        td.setFormat24Hour("dd/MMM/yyyy");
        td.setTextSize(19);
        td.setTypeface(Typeface.DEFAULT_BOLD);
        td.setTextColor(Tema.get(Tema.TEXTO));
        td.setSingleLine(true);
        LinearLayout.LayoutParams tdl = new LinearLayout.LayoutParams(-2, -2);
        tdl.leftMargin = dp(6);
        top.addView(td, tdl);
        LinearLayout.LayoutParams ckl = new LinearLayout.LayoutParams(dp(26), dp(26));
        ckl.leftMargin = dp(4);
        top.addView(new IconView(c, Icons.CLOCK, Tema.get(Tema.TEXTO)), ckl);
        TextClock tc = new Rel(c);
        tc.setFormat12Hour("HH:mm");
        tc.setFormat24Hour("HH:mm");
        tc.setTextSize(19);
        tc.setTypeface(Typeface.DEFAULT_BOLD);
        tc.setTextColor(Tema.get(Tema.TEXTO));
        tc.setSingleLine(true);
        LinearLayout.LayoutParams tcl = new LinearLayout.LayoutParams(-2, -2);
        tcl.leftMargin = dp(6);
        top.addView(tc, tcl);
        top.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        final Recarregar rec = new Recarregar(c);
        rec.setOnClickListener(v -> { rec.girar(); h.refresh(); toast("Lista atualizada"); });
        top.addView(rec, new LinearLayout.LayoutParams(dp(38), dp(38)));
        top.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));

        batTxt = new Txt(c);
        batTxt.setTextColor(Tema.get(Tema.TEXTO));
        batTxt.setTextSize(20);
        batTxt.setTypeface(Typeface.DEFAULT_BOLD);
        batTxt.setText("100%");
        LinearLayout.LayoutParams btl = new LinearLayout.LayoutParams(-2, -2);
        btl.leftMargin = dp(3);
        top.addView(batTxt, btl);
        bat = new BatteryView(c);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(50), dp(21));
        bl.leftMargin = dp(9);
        top.addView(bat, bl);

        LayoutParams tl = new LayoutParams(-1, dp(46), Gravity.TOP);
        tl.setMargins(dp(12), dp(6), dp(12), 0);
        addView(top, tl);

        // ---------- dock ----------
        dock = new LinearLayout(c);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setClipChildren(false);
        dock.setPadding(dp(8), 0, dp(8), 0);
        dock.setBackground(glass(dp(30)));
        dock.setElevation(dp(12));
        // mesma sequência e mesmos ícones/cores da tela de Configurações
        int[] ty = {Icons.HOME, Icons.SCREEN, Icons.AUDIO, Icons.PAD, Icons.SAVE, Icons.GEAR};
        int[] bg = Tema.dockBg();
        int[] ic = Tema.dockIc();
        for (int i = 0; i < 6; i++) {
            DockBtn b = new DockBtn(c, ty[i], bg[i], ic[i], i == 0);
            final int k = i;
            b.setOnClickListener(v -> dockAction(k));
            dock.addView(b, new LinearLayout.LayoutParams(0, dp(56), 1f));
        }
        LayoutParams dl = new LayoutParams(-1, dp(60), Gravity.BOTTOM);
        dl.setMargins(dp(14), 0, dp(14), dp(14));
        addView(dock, dl);

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
        grid.setPadding(pad, dp(68), pad, dp(92));
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
        empty.setText(dir ? "Nenhum jogo (.zip/.gba) encontrado.\nColoque seus jogos na pasta GBA-MAX\ndo armazenamento interno."
                : "Toque em Início (ícone branco)\npara escolher a pasta de ROMs.");
        empty.setVisibility(games.isEmpty() ? VISIBLE : GONE);
        adapter.notifyDataSetChanged();
    }

    public void refreshCovers() { adapter.notifyDataSetChanged(); }

    void toast(String s) { Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show(); }
    class GlowFrame extends FrameLayout {
        View hot; GradientDrawable dr; android.animation.ValueAnimator an;
        GlowFrame(Context c, View in) { super(c); addView(in, new LayoutParams(-1, -2)); }
        View hit(android.view.ViewGroup g, float x, float y) { for (int i = g.getChildCount() - 1; i >= 0; i--) { View ch = g.getChildAt(i); if (ch.getVisibility() != VISIBLE) continue; float px = x + g.getScrollX() - ch.getLeft(), py = y + g.getScrollY() - ch.getTop(); if (px < 0 || py < 0 || px > ch.getWidth() || py > ch.getHeight()) continue; if (ch.isClickable() && !(ch instanceof android.widget.SeekBar)) return ch; if (ch instanceof android.view.ViewGroup) { View r = hit((android.view.ViewGroup) ch, px, py); if (r != null) return r; } } return null; }
        void setHot(View v) { if (v == hot) return; if (hot != null) { if (an != null) an.cancel(); hot.getOverlay().remove(dr); hot.animate().cancel(); hot.animate().scaleX(1f).scaleY(1f).translationZ(0).setDuration(140).start(); } hot = v; if (v == null) return; dr = new GradientDrawable(); dr.setCornerRadius(dp(16)); dr.setColor(0x40FFFFFF); dr.setStroke(dp(2), 0xFF4D9BFF); dr.setBounds(0, 0, v.getWidth(), v.getHeight()); v.getOverlay().add(dr); v.animate().cancel(); v.animate().scaleX(1.04f).scaleY(1.04f).translationZ(dp(6)).setDuration(140).start(); final GradientDrawable d2 = dr; an = android.animation.ValueAnimator.ofInt(110, 255); an.setDuration(650); an.setRepeatMode(android.animation.ValueAnimator.REVERSE); an.setRepeatCount(android.animation.ValueAnimator.INFINITE); an.addUpdateListener(x -> d2.setAlpha((int) x.getAnimatedValue())); an.start(); }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); setHot(null); }
        @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) { int ac = e.getActionMasked(); if (ac == android.view.MotionEvent.ACTION_DOWN || ac == android.view.MotionEvent.ACTION_MOVE) setHot(hit(this, e.getX(), e.getY())); else if (ac == android.view.MotionEvent.ACTION_UP || ac == android.view.MotionEvent.ACTION_CANCEL) setHot(null); return super.dispatchTouchEvent(e); }
        @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) { int ac = e.getActionMasked(); if (ac == android.view.MotionEvent.ACTION_HOVER_ENTER || ac == android.view.MotionEvent.ACTION_HOVER_MOVE) setHot(hit(this, e.getX(), e.getY())); else if (ac == android.view.MotionEvent.ACTION_HOVER_EXIT) setHot(null); return super.dispatchGenericMotionEvent(e); }
    }

    void showSettings() {
        final Context c = getContext();
        final android.app.Dialog d = new android.app.Dialog(c);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(10));
        box.setBackground(glass(dp(26)));
        TextView title = new Txt(c);
        title.setText("Configurações");
        title.setTextColor(Tema.get(Tema.TEXTO));
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(6), 0, 0, dp(12));
        box.addView(title);
        String[] tx = {"Configuração de tela", "Configuração de áudio", "Configuração de controle", "Configuração de saves", "Avançado"};
        int[] ty = {Icons.SCREEN, Icons.AUDIO, Icons.PAD, Icons.SAVE, Icons.ADV};
        int[] bg = Tema.dockBg1();
        for (int i = 0; i < 5; i++) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(4), dp(14), dp(4));
            GradientDrawable rg = new GradientDrawable();
            rg.setColor(Tema.get(Tema.LINHA));
            rg.setCornerRadius(dp(18));
            rg.setStroke(dp(Tema.grossura), Tema.get(Tema.CONTORNO));
            row.setBackground(rg);
            row.addView(new DockBtn(c, ty[i], bg[i], Tema.glyph(i + 1), false), new LinearLayout.LayoutParams(dp(52), dp(52)));
            TextView t = new Txt(c);
            t.setText(tx[i]);
            t.setTextColor(Tema.get(Tema.TEXTO));
            t.setTextSize(15);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams tl2 = new LinearLayout.LayoutParams(0, -2, 1f);
            tl2.leftMargin = dp(6);
            row.addView(t, tl2);
            TextView ar = new Txt(c);
            ar.setText("›");
            ar.setTextColor(Tema.get(Tema.TEXTO2));
            ar.setTextSize(22);
            row.addView(ar);
            final int k = i;
            row.setOnClickListener(v -> {
                d.dismiss();
                if (k == 4) {
                    if (c instanceof MainActivity) Personalizar.show((MainActivity) c);
                } else if (k == 0) new VideoMenu(MenuView.this).show();
                else abrirConfig(k); // 1 áudio, 2 controle, 3 saves
            });
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.bottomMargin = dp(8);
            box.addView(row, rl);
        }
        d.setContentView(new GlowFrame(getContext(), box));
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            int sw = getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(dp(340), sw - dp(32)), -2);
        }
        d.show();
    }

    // ================= capas: escolha + janela de download =================
    GradientDrawable pill(int col) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(col);
        g.setCornerRadius(dp(16));
        return g;
    }

    TextView btn(String t, int bgc, int fg) {
        TextView b = new Txt(getContext());
        b.setText(t);
        b.setTextColor(fg);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        b.setBackground(pill(bgc));
        return b;
    }

    android.app.Dialog glassDialog(LinearLayout box) {
        final android.app.Dialog d = new android.app.Dialog(getContext());
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        box.setPadding(dp(18), dp(18), dp(18), dp(14));
        box.setBackground(glass(dp(26)));
        d.setContentView(new GlowFrame(getContext(), box));
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            int sw = getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(dp(320), sw - dp(40)), -2);
        }
        return d;
    }

    TextView label(String t, float sz, int col, boolean bold) {
        TextView v = new Txt(getContext());
        v.setText(t);
        v.setTextSize(sz);
        v.setTextColor(col);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    void showCoverChoice() {
        if (CoverService.running) { showCoverProgress(); return; }
        final Context c = getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(label("Capas dos jogos", 18, Tema.get(Tema.TEXTO), true));
        TextView m = label("Deseja baixar as capas dos seus jogos? O download continua em segundo plano.", 14, 0xFF4A5363, false);
        m.setPadding(0, dp(8), 0, dp(14));
        box.addView(m);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView no = btn("Agora não", 0xFFE3E7EE, Tema.get(Tema.TEXTO));
        TextView yes = btn("Baixar capas", 0xFF2EAD4B, Color.WHITE);
        row.addView(no, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams yl = new LinearLayout.LayoutParams(0, -2, 1f);
        yl.leftMargin = dp(8);
        row.addView(yes, yl);
        box.addView(row);
        final android.app.Dialog d = glassDialog(box);
        no.setOnClickListener(v -> d.dismiss());
        yes.setOnClickListener(v -> {
            d.dismiss();
            if (games.isEmpty()) { toast("Nenhum jogo na lista."); return; }
            if (Build.VERSION.SDK_INT >= 33 && c instanceof android.app.Activity
                    && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                ((android.app.Activity) c).requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
            CoverService.listener = (g, f) -> post(() -> {
                for (Game x : games) if (x.name.equals(g.name)) x.cover = Uri.fromFile(f);
                adapter.notifyDataSetChanged();
            });
            CoverService.start(c.getApplicationContext(), games);
            showCoverProgress();
        });
        d.show();
    }

    static String fmtSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(java.util.Locale.getDefault(), "%.0f KB", b / 1024.0);
        return String.format(java.util.Locale.getDefault(), "%.1f MB", b / 1048576.0);
    }

    void showCoverProgress() {
        final Context c = getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(label("Baixando capas", 18, Tema.get(Tema.TEXTO), true));
        final TextView st = label("Iniciando...", 13, 0xFF4A5363, false);
        st.setPadding(0, dp(8), 0, dp(8));
        st.setSingleLine(true);
        st.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        box.addView(st);
        final android.widget.ProgressBar pb = new android.widget.ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        box.addView(pb, new LinearLayout.LayoutParams(-1, dp(12)));
        final TextView info = label("", 13, Tema.get(Tema.TEXTO), true);
        info.setPadding(0, dp(10), 0, dp(14));
        box.addView(info);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final TextView cancel = btn("Cancelar", 0xFFE3E7EE, Tema.get(Tema.TEXTO));
        final TextView bg = btn("Segundo plano", 0xFF1E6BE6, Color.WHITE);
        row.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -2, 1f);
        bl.leftMargin = dp(8);
        row.addView(bg, bl);
        box.addView(row);
        final android.app.Dialog d = glassDialog(box);
        bg.setOnClickListener(v -> d.dismiss());
        cancel.setOnClickListener(v -> { CoverService.cancel(); });
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (!d.isShowing()) return;
            boolean run = CoverService.running;
            int t = Math.max(CoverService.total, 1), dn = CoverService.done;
            long end = run ? System.currentTimeMillis() : (CoverService.endMs > 0 ? CoverService.endMs : System.currentTimeMillis());
            long secs = Math.max(0, (end - CoverService.startMs) / 1000);
            pb.setProgress(run ? dn * 100 / t : (CoverService.status.equals("Concluído") ? 100 : dn * 100 / t));
            st.setText(run ? dn + "/" + CoverService.total + " · " + CoverService.status : CoverService.status);
            info.setText("Tempo: " + secs + " s   •   Tamanho: " + fmtSize(CoverFetcher.BYTES.get()));
            if (!run) {
                cancel.setVisibility(GONE);
                ((TextView) bg).setText("Fechar");
                return;
            }
            postDelayed(tick[0], 500);
        };
        d.show();
        tick[0].run();
    }

    void dockAction(int k) {
        switch (k) {
            case 0: host.pickDir(); break;
            case 1: new VideoMenu(this).show(); break;
            case 2: abrirConfig(1); break;
            case 3: abrirConfig(2); break;
            case 4: showCoverChoice(); break;
            case 5: showSettings(); break;
            default: break;
        }
    }

    /** 1 = áudio, 2 = controle, 3 = saves (mesmas páginas do menu do jogo, valendo como padrão). */
    void abrirConfig(int tipo) {
        Context cx = getContext();
        if (!(cx instanceof MainActivity)) return;
        GameMenu g = new GameMenu((MainActivity) cx);
        if (tipo == 1) g.audio();
        else if (tipo == 2) g.control();
        else g.saves();
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
            Bitmap cb = customBg;
            if (cb != null && w > 0 && h > 0) {
                float s = Math.max(w / cb.getWidth(), h / cb.getHeight());
                float sw = w / s, sh = h / s;
                android.graphics.Rect src = new android.graphics.Rect((int) ((cb.getWidth() - sw) / 2), (int) ((cb.getHeight() - sh) / 2),
                        (int) ((cb.getWidth() + sw) / 2), (int) ((cb.getHeight() + sh) / 2));
                p.setShader(null);
                p.setStyle(Paint.Style.FILL);
                p.setFilterBitmap(true);
                c.drawBitmap(cb, src, new RectF(0, 0, w, h), p);
                return;
            }
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
            plate.setPadding(dp(6), dp(12), dp(6), dp(6));
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{Tema.get(Tema.PLACA_TOPO), Tema.get(Tema.PLACA_BASE)});
            g.setCornerRadius(dp(14));
            g.setStroke(dp(Tema.grossura), Tema.get(Tema.CONTORNO));
            plate.setBackground(g);
            plate.setElevation(dp(18));

            t1 = new Txt(c);
            t1.setTextColor(Tema.get(Tema.TEXTO));
            t1.setTextSize(11.5f);
            t1.setTypeface(Typeface.DEFAULT_BOLD);
            t1.setSingleLine(false); t1.setMinLines(2); t1.setMaxLines(2);
            t1.setEllipsize(TextUtils.TruncateAt.END);
            t1.setGravity(Gravity.CENTER);
            t2 = new Txt(c);
            t2.setTextColor(Tema.get(Tema.TEXTO2));
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
                fill.setFilterBitmap(true);
                Bitmap bl = blurOf(g.cover.toString(), bm);
                float sb = Math.max(r.width() / bl.getWidth(), r.height() / bl.getHeight());
                mx.setScale(sb, sb);
                mx.postTranslate(r.left + (r.width() - bl.getWidth() * sb) / 2,
                        r.top + (r.height() - bl.getHeight() * sb) / 2);
                BitmapShader shb = new BitmapShader(bl, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                shb.setLocalMatrix(mx);
                fill.setShader(shb);
                c.drawRect(r, fill);
                fill.setShader(null);
                fill.setColor(0x33000000);
                c.drawRect(r, fill);
                float s = Math.min(r.width() / bm.getWidth(), r.height() / bm.getHeight());
                float dw = bm.getWidth() * s, dh = bm.getHeight() * s;
                mx.setScale(s, s);
                mx.postTranslate(r.centerX() - dw / 2, r.centerY() - dh / 2);
                BitmapShader sh = new BitmapShader(bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                sh.setLocalMatrix(mx);
                fill.setShader(sh);
                c.drawRect(r.centerX() - dw / 2, r.centerY() - dh / 2, r.centerX() + dw / 2, r.centerY() + dh / 2, fill);
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
                    tp.setTypeface(Fx.mapear(getContext(), Typeface.DEFAULT_BOLD));
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
            // efeito de vidro por cima: véu fosco, brilho diagonal e borda interna
            fill.setShader(null);
            fill.setColor(0x0AFFFFFF);
            c.drawRect(r, fill);
            fill.setColor(0xFF000000);
            fill.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                    new int[]{0x40FFFFFF, 0x12FFFFFF, 0x00FFFFFF, 0x18FFFFFF},
                    new float[]{0f, .32f, .6f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(r, fill);
            fill.setShader(new LinearGradient(0, r.bottom - r.height() * .3f, 0, r.bottom,
                    0x00000000, 0x22000000, Shader.TileMode.CLAMP));
            c.drawRect(r.left, r.bottom - r.height() * .3f, r.right, r.bottom, fill);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(1));
            line.setColor(0x80FFFFFF);
            c.drawRoundRect(r.left + dp(2), r.top + dp(2), r.right - dp(2), r.bottom - dp(2), rad - dp(2), rad - dp(2), line);
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
        boolean glow;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        DockBtn(Context c, int type, int col, int icol, boolean sel) {
            super(c);
            this.type = type; this.col = col; this.icol = icol; this.sel = sel;
            setElevation(dp(5));
            setOnHoverListener((v, e) -> {
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_HOVER_ENTER) setGlow(true);
                else if (a == android.view.MotionEvent.ACTION_HOVER_EXIT) setGlow(false);
                return false;
            });
            setOnTouchListener((v, e) -> {
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN) setGlow(true);
                else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) setGlow(false);
                return false;
            });
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) {
                    float r = Math.min(v.getWidth(), v.getHeight()) / 2f - dp(5);
                    float cx = v.getWidth() / 2f, cy = v.getHeight() / 2f;
                    o.setOval((int) (cx - r), (int) (cy - r), (int) (cx + r), (int) (cy + r));
                }
            });
        }

        void setGlow(boolean g) { if (glow != g) { glow = g; invalidate(); } }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
            float r = Math.min(w, h) / 2f - dp(5);
            if (glow) {
                float gr = r + dp(5);
                p.setStyle(Paint.Style.FILL);
                p.setShader(new RadialGradient(cx, cy, gr, new int[]{0xFF4D9BFF, 0xFF4D9BFF, 0x004D9BFF},
                        new float[]{0f, (r - dp(1)) / gr, 1f}, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, gr, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, cy - r, 0, cy + r, mix(col, Color.WHITE, .35f),
                    mix(col, Color.BLACK, col == 0xFFFFFFFF ? .06f : .18f), Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
            p.setShader(new LinearGradient(0, cy - r, 0, cy, 0xAAFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawOval(new RectF(cx - r * .72f, cy - r * .94f, cx + r * .72f, cy - r * .05f), p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.2f));
            p.setColor(glow ? 0xFFBBD8FF : 0xCCFFFFFF);
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
            final float den = getHeight() / 14f; float w = getWidth(), h = getHeight(), nub = den * 3;
            RectF body = new RectF(den, den, w - nub - den, h - den);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(den * 1.5f);
            p.setColor(Tema.get(Tema.TEXTO));
            c.drawRoundRect(body, den * 4, den * 4, p);
            int on = pct <= 0 ? 0 : pct <= 25 ? 1 : pct <= 50 ? 2 : pct <= 75 ? 3 : 4;
            int col = pct <= 25 ? 0xFFE53935 : pct <= 50 ? 0xFFFB8C00 : pct <= 75 ? 0xFFFDD835 : 0xFF2EAD4B;
            float l = body.left + den * 2.5f, t = body.top + den * 2.5f, b = body.bottom - den * 2.5f;
            float gap = den * 1.5f, bw = (body.width() - den * 5f - gap * 3) / 4f;
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 4; i++) {
                p.setColor(i < on ? col : 0x261B1F27);
                float x0 = l + i * (bw + gap);
                c.drawRoundRect(x0, t, x0 + bw, b, den * .8f, den * .8f, p);
            }
            p.setColor(Tema.get(Tema.TEXTO));
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
        static final int WIFI = 0, HEAD = 1, HOME = 2, CHAT = 3, BAG = 4, LIB = 5, FACE = 6, PAD = 7, GEAR = 8, COIN = 9, CAL = 10, CLOCK = 11, SCREEN = 12, AUDIO = 13, SAVE = 14, ADV = 15, CHEAT = 16;

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
                case CAL:
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * .18f);
                    q.set(x - .85f * r, y - .72f * r, x + .85f * r, y + .88f * r);
                    c.drawRoundRect(q, r * .22f, r * .22f, p);
                    c.drawLine(x - .85f * r, y - .2f * r, x + .85f * r, y - .2f * r, p);
                    c.drawLine(x - .4f * r, y - .98f * r, x - .4f * r, y - .5f * r, p);
                    c.drawLine(x + .4f * r, y - .98f * r, x + .4f * r, y - .5f * r, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(x - .4f * r, y + .25f * r, r * .12f, p);
                    c.drawCircle(x + .4f * r, y + .25f * r, r * .12f, p);
                    c.drawCircle(x - .4f * r, y + .62f * r, r * .12f, p);
                    break;
                case CLOCK:
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * .18f);
                    c.drawCircle(x, y, r * .88f, p);
                    c.drawLine(x, y, x, y - .5f * r, p);
                    c.drawLine(x, y, x + .38f * r, y + .2f * r, p);
                    break;
                case SCREEN:
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * .18f);
                    q.set(x - .9f * r, y - .75f * r, x + .9f * r, y + .45f * r);
                    c.drawRoundRect(q, r * .2f, r * .2f, p);
                    c.drawLine(x, y + .45f * r, x, y + .8f * r, p);
                    c.drawLine(x - .5f * r, y + .85f * r, x + .5f * r, y + .85f * r, p);
                    break;
                case AUDIO: {
                    p.setStyle(Paint.Style.FILL);
                    Path sp = new Path();
                    sp.moveTo(x - .9f * r, y - .3f * r);
                    sp.lineTo(x - .5f * r, y - .3f * r);
                    sp.lineTo(x - .05f * r, y - .72f * r);
                    sp.lineTo(x - .05f * r, y + .72f * r);
                    sp.lineTo(x - .5f * r, y + .3f * r);
                    sp.lineTo(x - .9f * r, y + .3f * r);
                    sp.close();
                    c.drawPath(sp, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * .17f);
                    q.set(x - .05f * r - .5f * r, y - .5f * r, x - .05f * r + .5f * r, y + .5f * r);
                    c.drawArc(q, -50, 100, false, p);
                    q.set(x - .05f * r - .95f * r, y - .95f * r, x - .05f * r + .95f * r, y + .95f * r);
                    c.drawArc(q, -50, 100, false, p);
                    break;
                }
                case SAVE:
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * .17f);
                    q.set(x - .85f * r, y - .85f * r, x + .85f * r, y + .85f * r);
                    c.drawRoundRect(q, r * .18f, r * .18f, p);
                    q.set(x - .45f * r, y - .85f * r, x + .4f * r, y - .3f * r);
                    c.drawRect(q, p);
                    q.set(x - .5f * r, y + .1f * r, x + .5f * r, y + .85f * r);
                    c.drawRect(q, p);
                    break;
                case ADV:
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(r * .17f);
                    c.drawLine(x - .85f * r, y - .55f * r, x + .85f * r, y - .55f * r, p);
                    c.drawLine(x - .85f * r, y, x + .85f * r, y, p);
                    c.drawLine(x - .85f * r, y + .55f * r, x + .85f * r, y + .55f * r, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(x - .35f * r, y - .55f * r, r * .24f, p);
                    c.drawCircle(x + .4f * r, y, r * .24f, p);
                    c.drawCircle(x - .1f * r, y + .55f * r, r * .24f, p);
                    break;
                case CHEAT:
                    p.setStyle(Paint.Style.FILL_AND_STROKE);
                    p.setStrokeWidth(r * .12f);
                    Path bz = new Path();
                    bz.moveTo(x + .15f * r, y - r);
                    bz.lineTo(x - .65f * r, y + .1f * r);
                    bz.lineTo(x - .05f * r, y + .1f * r);
                    bz.lineTo(x - .2f * r, y + r);
                    bz.lineTo(x + .65f * r, y - .2f * r);
                    bz.lineTo(x + .05f * r, y - .2f * r);
                    bz.close();
                    c.drawPath(bz, p);
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
