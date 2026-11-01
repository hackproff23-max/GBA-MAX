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
            TextView t = new TextView(a);
            t.setText(Tema.NOME[i]);
            t.setTextColor(Tema.get(Tema.TEXTO));
            t.setTextSize(14);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
            tl.leftMargin = mv.dp(12);
            row.addView(t, tl);
            row.setOnClickListener(v -> { d.dismiss(); editar(a, k); });
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.bottomMargin = mv.dp(6);
            l.addView(row, rl);
        }
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(l);
        int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * .6f);
        box.addView(sv, new LinearLayout.LayoutParams(-1, maxH));
        TextView rest = mv.btn("Restaurar todas as cores", 0xFFE3E7EE, 0xFF1B1F27);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = mv.dp(8);
        box.addView(rest, fl);
        rest.setOnClickListener(v -> { d.dismiss(); Tema.resetAll(a); a.recreate(); });
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

    static void editar(final MainActivity a, final int idx) {
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
            if (!Tema.legivel(idx, Tema.PADRAO[idx])) { a.toast("Essa cor deixaria o texto ilegível"); return; }
            d.dismiss();
            Tema.reset(a, idx);
            a.recreate();
        });
        ok.setOnClickListener(v -> {
            if (!Tema.legivel(idx, cor[0])) {
                a.toast("Letra e fundo ficariam parecidos demais e você não conseguiria ler. Escolha outra cor.");
                return;
            }
            d.dismiss();
            Tema.set(a, idx, cor[0]);
            a.recreate();
        });
        d.show();
    }

    interface Val { void on(int v); }

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
