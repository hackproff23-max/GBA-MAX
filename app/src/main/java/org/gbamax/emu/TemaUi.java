package org.gbamax.emu;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Janelas para editar as cores do app. */
final class TemaUi {
    static boolean pendente;
    static void lista(final MainActivity a) {
        final MenuView mv = a.mv;
        LinearLayout box = Personalizar.caixa(mv, a, "Cores do app");
        final Dialog d = Personalizar.janela(mv, a, box);
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < Tema.N; i++) {
            final int k = i;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(mv.dp(12), mv.dp(8), mv.dp(12), mv.dp(8));
            GradientDrawable rg = new GradientDrawable();
            rg.setColor(Tema.get(Tema.LINHA));
            rg.setCornerRadius(mv.dp(16));
            rg.setStroke(mv.dp(1), 0xCCFFFFFF);
            row.setBackground(rg);
            row.addView(swatch(a, mv, Tema.get(i), 30), new LinearLayout.LayoutParams(mv.dp(30), mv.dp(30)));
            TextView t = new Txt(a);
            t.setText(Tema.NOME[i]);
            t.setTextColor(Tema.get(Tema.TEXTO));
            t.setTextSize(14);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
            tl.leftMargin = mv.dp(12);
            row.addView(t, tl);
            row.setOnClickListener(v -> editar(a, k, () -> { row.removeViewAt(0); row.addView(swatch(a, mv, Tema.get(k), 30), 0, new LinearLayout.LayoutParams(mv.dp(30), mv.dp(30))); }));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.bottomMargin = mv.dp(6);
            l.addView(row, rl);
        }
        final LinearLayout gr = new LinearLayout(a); gr.setOrientation(LinearLayout.HORIZONTAL); gr.setGravity(Gravity.CENTER_VERTICAL); gr.setPadding(mv.dp(12), mv.dp(12), mv.dp(12), mv.dp(12)); GradientDrawable gg = new GradientDrawable(); gg.setColor(Tema.get(Tema.LINHA)); gg.setCornerRadius(mv.dp(16)); gg.setStroke(mv.dp(1), 0xCCFFFFFF); gr.setBackground(gg); final TextView gt = new Txt(a); gt.setText("Contorno das janelas: grossura (" + fmt(Tema.grossura) + ")"); gt.setTextColor(Tema.get(Tema.TEXTO)); gt.setTextSize(14); gt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); gr.addView(gt, new LinearLayout.LayoutParams(-1, -2)); gr.setOnClickListener(v -> grossura(a, () -> gt.setText("Contorno das janelas: grossura (" + fmt(Tema.grossura) + ")"))); LinearLayout.LayoutParams grl = new LinearLayout.LayoutParams(-1, -2); grl.bottomMargin = mv.dp(6); l.addView(gr, grl);
        final LinearLayout tr = new LinearLayout(a); tr.setOrientation(LinearLayout.HORIZONTAL); tr.setGravity(Gravity.CENTER_VERTICAL); tr.setPadding(mv.dp(12), mv.dp(12), mv.dp(12), mv.dp(12)); GradientDrawable tg = new GradientDrawable(); tg.setColor(Tema.get(Tema.LINHA)); tg.setCornerRadius(mv.dp(16)); tg.setStroke(mv.dp(1), 0xCCFFFFFF); tr.setBackground(tg); final TextView tt = new Txt(a); tt.setText("Contorno dos textos: grossura (" + fmt(Tema.txtGrossura) + ")"); tt.setTextColor(Tema.get(Tema.TEXTO)); tt.setTextSize(14); tt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); tr.addView(tt, new LinearLayout.LayoutParams(-1, -2)); tr.setOnClickListener(v -> grossuraTexto(a, () -> tt.setText("Contorno dos textos: grossura (" + fmt(Tema.txtGrossura) + ")"))); LinearLayout.LayoutParams trl = new LinearLayout.LayoutParams(-1, -2); trl.bottomMargin = mv.dp(6); l.addView(tr, trl);
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(l);
        int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * .6f);
        box.addView(sv, new LinearLayout.LayoutParams(-1, maxH));
        TextView rest = mv.btn("Restaurar todas as cores", 0xFFE3E7EE, 0xFF1B1F27);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = mv.dp(8);
        box.addView(rest, fl);
        TextView fim = mv.btn("Concluir", 0xFF2EAD4B, Color.WHITE); LinearLayout.LayoutParams fl2 = new LinearLayout.LayoutParams(-1, -2); fl2.topMargin = mv.dp(8); box.addView(fim, fl2); fim.setOnClickListener(v -> d.dismiss()); d.setOnDismissListener(x -> { if (pendente) { pendente = false; a.recreate(); } });
        rest.setOnClickListener(v -> { pendente = false; d.dismiss(); Tema.resetAll(a); a.recreate(); });
        d.show();
    }

    /** Bolinha de cor sobre fundo cinza (para ver a transparência). */
    static View swatch(MainActivity a, MenuView mv, int cor, int dp) {
        FrameLayout f = new FrameLayout(a);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF666666);
        bg.setCornerRadius(mv.dp(10));
        bg.setStroke(mv.dp(1), 0xFF999999);
        f.setBackground(bg);
        View in = new View(a);
        GradientDrawable g = new GradientDrawable();
        g.setColor(cor);
        g.setCornerRadius(mv.dp(10));
        in.setBackground(g);
        f.addView(in, new FrameLayout.LayoutParams(-1, -1));
        return f;
    }

    static void editar(final MainActivity a, final int idx) { editar(a, idx, null); } static void editar(final MainActivity a, final int idx, final Runnable depois) {
        final MenuView mv = a.mv;
        LinearLayout box = Personalizar.caixa(mv, a, Tema.NOME[idx]);
        final Dialog d = Personalizar.janela(mv, a, box);
        final int ini = Tema.get(idx);
        final float[] hsv = new float[3];
        Color.colorToHSV(ini, hsv);
        final int[] alfa = {Color.alpha(ini)};
        final int[] cor = {ini};

        final GradientDrawable pg = new GradientDrawable();
        pg.setColor(ini);
        pg.setCornerRadius(mv.dp(12));
        FrameLayout pf = new FrameLayout(a);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(0xFF666666);
        pbg.setCornerRadius(mv.dp(12));
        pf.setBackground(pbg);
        View pv = new View(a);
        pv.setBackground(pg);
        pf.addView(pv, new FrameLayout.LayoutParams(-1, -1));
        box.addView(pf, new LinearLayout.LayoutParams(-1, mv.dp(56)));

        final Runnable upd = () -> {
            cor[0] = Color.HSVToColor(alfa[0], hsv);
            pg.setColor(cor[0]);
        };
        slider(a, mv, box, "Matiz", 360, (int) hsv[0], v -> { hsv[0] = v; upd.run(); });
        slider(a, mv, box, "Intensidade", 100, (int) (hsv[1] * 100), v -> { hsv[1] = v / 100f; upd.run(); });
        slider(a, mv, box, "Brilho", 100, (int) (hsv[2] * 100), v -> { hsv[2] = v / 100f; upd.run(); });
        slider(a, mv, box, "Transparência (0 = invisível)", 255, alfa[0], v -> { alfa[0] = v; upd.run(); });

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = mv.btn("Cancelar", 0xFFE3E7EE, 0xFF1B1F27);
        TextView pad = mv.btn("Padrão", 0xFFE3E7EE, 0xFF1B1F27);
        TextView ok = mv.btn("Aplicar", 0xFF2EAD4B, Color.WHITE);
        row.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, -2, 1f);
        l2.leftMargin = mv.dp(6);
        row.addView(pad, l2);
        LinearLayout.LayoutParams l3 = new LinearLayout.LayoutParams(0, -2, 1f);
        l3.leftMargin = mv.dp(6);
        row.addView(ok, l3);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = mv.dp(12);
        box.addView(row, rl);

        cancel.setOnClickListener(v -> d.dismiss());
        pad.setOnClickListener(v -> {
            d.dismiss();
            Tema.reset(a, idx);
            if (depois == null) a.recreate(); else { pendente = true; depois.run(); }
        });
        ok.setOnClickListener(v -> {
            d.dismiss();
            Tema.set(a, idx, cor[0]);
            if (depois == null) a.recreate(); else { pendente = true; depois.run(); }
        });
        d.show();
    }

    interface Val { void on(int v); }
    static String fmt(float x) { return String.format(java.util.Locale.getDefault(), "%.1f dp", x); }
    static void grossuraTexto(final MainActivity a, final Runnable depois) { final MenuView mv = a.mv; LinearLayout box = Personalizar.caixa(mv, a, "Contorno dos textos"); final Dialog d = Personalizar.janela(mv, a, box); final float[] g = {Tema.txtGrossura}; final TextView val = mv.label(fmt(g[0]), 14, Tema.get(Tema.TEXTO), true); box.addView(val); slider(a, mv, box, "Grossura (0 = sem contorno)", 16, Math.round(g[0] * 2), v -> { g[0] = v / 2f; val.setText(fmt(g[0])); }); LinearLayout row = new LinearLayout(a); row.setOrientation(LinearLayout.HORIZONTAL); TextView cancel = mv.btn("Cancelar", 0xFFE3E7EE, 0xFF1B1F27); TextView ok = mv.btn("Aplicar", 0xFF2EAD4B, Color.WHITE); row.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1f)); LinearLayout.LayoutParams l3 = new LinearLayout.LayoutParams(0, -2, 1f); l3.leftMargin = mv.dp(6); row.addView(ok, l3); LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = mv.dp(12); box.addView(row, rl); cancel.setOnClickListener(v -> d.dismiss()); ok.setOnClickListener(v -> { d.dismiss(); Tema.setTxtGrossura(a, g[0]); pendente = true; depois.run(); }); d.show(); }
    static void grossura(final MainActivity a, final Runnable depois) {
        final MenuView mv = a.mv;
        LinearLayout box = Personalizar.caixa(mv, a, "Grossura do contorno");
        final Dialog d = Personalizar.janela(mv, a, box);
        final float[] g = {Tema.grossura};
        final TextView val = mv.label(fmt(g[0]), 14, Tema.get(Tema.TEXTO), true);
        box.addView(val);
        slider(a, mv, box, "Grossura (0 = sem contorno)", 16, Math.round(g[0] * 2), v -> { g[0] = v / 2f; val.setText(fmt(g[0])); });
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = mv.btn("Cancelar", 0xFFE3E7EE, 0xFF1B1F27);
        TextView ok = mv.btn("Aplicar", 0xFF2EAD4B, Color.WHITE);
        row.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams l3 = new LinearLayout.LayoutParams(0, -2, 1f);
        l3.leftMargin = mv.dp(6);
        row.addView(ok, l3);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = mv.dp(12);
        box.addView(row, rl);
        cancel.setOnClickListener(v -> d.dismiss());
        ok.setOnClickListener(v -> { d.dismiss(); Tema.setGrossura(a, g[0]); pendente = true; depois.run(); });
        d.show();
    }

    static void slider(MainActivity a, MenuView mv, LinearLayout box, String nome, int max, int ini, final Val cb) {
        TextView t = mv.label(nome, 12, Tema.get(Tema.TEXTO2), true);
        t.setPadding(mv.dp(4), mv.dp(10), 0, 0);
        box.addView(t);
        SeekBar s = new SeekBar(a);
        s.setMax(max);
        s.setProgress(Math.max(0, Math.min(max, ini)));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean u) { if (u) cb.on(p); }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        box.addView(s, new LinearLayout.LayoutParams(-1, -2));
    }
}
