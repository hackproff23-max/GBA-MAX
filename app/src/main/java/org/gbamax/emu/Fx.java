package org.gbamax.emu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.widget.TextClock;
import android.widget.TextView;

/** Fonte BebasKai (assets) e contorno (Tema.txtGrossura / Tema.TXT_CONTORNO) em todos os textos do app. */
final class Fx {
    static boolean trocando;
    static Typeface base;
    static boolean tentou;

    static Typeface fonte(Context c) {
        if (!tentou) {
            tentou = true;
            try { base = Typeface.createFromAsset(c.getApplicationContext().getAssets(), "BebasKai.ttf"); }
            catch (Throwable t) { base = null; }
        }
        return base;
    }

    /** Troca a fonte do sistema pela BebasKai mantendo o estilo (normal/negrito). */
    static Typeface mapear(Context c, Typeface tf) {
        Typeface b = fonte(c);
        if (b == null) return tf;
        return Typeface.create(b, tf == null ? Typeface.NORMAL : tf.getStyle());
    }

    /** Desenha o texto duas vezes: primeiro o contorno, depois o preenchimento por cima. */
    static void desenhar(TextView v, Canvas c, Runnable normal) {
        float w = Tema.txtGrossura;
        int cc = Tema.get(Tema.TXT_CONTORNO);
        if (w <= 0f || (cc >>> 24) == 0 || trocando) { normal.run(); return; }
        TextPaint p = v.getPaint();
        int cor = v.getCurrentTextColor();
        Paint.Style est = p.getStyle();
        float sw = p.getStrokeWidth();
        Paint.Join jn = p.getStrokeJoin();
        trocando = true;
        try {
            v.setTextColor(cc);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f * w * v.getResources().getDisplayMetrics().density);
            p.setStrokeJoin(Paint.Join.ROUND);
            normal.run();
            v.setTextColor(cor);
            p.setStyle(est);
            p.setStrokeWidth(sw);
            p.setStrokeJoin(jn);
        } finally { trocando = false; }
        normal.run();
    }
}

class Txt extends TextView {
    Txt(Context c) { super(c); setTypeface(getTypeface()); }
    @Override public void setTypeface(Typeface tf) { super.setTypeface(Fx.mapear(getContext(), tf)); }
    @Override protected void onDraw(Canvas c) { Fx.desenhar(this, c, () -> super.onDraw(c)); }
    @Override public void invalidate() { if (!Fx.trocando) super.invalidate(); }
}

class Rel extends TextClock {
    Rel(Context c) { super(c); setTypeface(getTypeface()); }
    @Override public void setTypeface(Typeface tf) { super.setTypeface(Fx.mapear(getContext(), tf)); }
    @Override protected void onDraw(Canvas c) { Fx.desenhar(this, c, () -> super.onDraw(c)); }
    @Override public void invalidate() { if (!Fx.trocando) super.invalidate(); }
}
