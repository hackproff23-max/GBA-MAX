package org.gbamax.emu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.io.InputStream;

/**
 * Abertura: GIF de fundo esticado na tela toda (ignora a proporção), o nome GBA-MAX
 * aparecendo letra por letra e depois o logo. Toque na tela para pular.
 */
class Intro extends View {
    static final String NOME = "GBA-MAX";
    static final int INI = 500, LETRA = 230, FIM_LETRAS = INI + 7 * LETRA;
    static final int LOGO_T = FIM_LETRAS + 150, LOGO_D = 800, SEGURA = 4700, SAIDA = 450;

    Movie movie;
    Bitmap logo;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint bm = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final RectF r = new RectF();
    long t0;
    boolean fim;

    Intro(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // Movie (GIF) só desenha em software
        setClickable(true);
        setOnClickListener(v -> fechar());
        try {
            int id = c.getResources().getIdentifier("intro_fundo", "raw", c.getPackageName());
            if (id != 0) try (InputStream in = c.getResources().openRawResource(id)) { movie = Movie.decodeStream(in); }
        } catch (Throwable t) { movie = null; }
        try {
            int id = c.getResources().getIdentifier("intro_logo", "raw", c.getPackageName());
            if (id != 0) try (InputStream in = c.getResources().openRawResource(id)) { logo = BitmapFactory.decodeStream(in); }
        } catch (Throwable t) { logo = null; }
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC));
    }

    static float ease(float t) { t = Math.max(0f, Math.min(1f, t)); return 1f - (1f - t) * (1f - t) * (1f - t); }

    @Override protected void onDraw(Canvas c) {
        if (fim) return;
        long now = SystemClock.uptimeMillis();
        if (t0 == 0) t0 = now;
        long el = now - t0;
        float w = getWidth(), h = getHeight();
        if (el > SEGURA + SAIDA) { fechar(); return; }
        setAlpha(el > SEGURA ? 1f - (el - SEGURA) / (float) SAIDA : 1f);

        c.drawColor(Color.BLACK);
        if (movie != null && movie.width() > 0 && movie.height() > 0 && w > 0) {
            int dur = movie.duration() > 0 ? movie.duration() : 1000;
            movie.setTime((int) (el % dur));
            c.save();
            c.scale(w / movie.width(), h / movie.height()); // estica: ignora a resolução/proporção
            movie.draw(c, 0, 0);
            c.restore();
        }
        c.drawColor(0x40000000);

        // nome, letra por letra
        float ts = w * .17f;
        p.setTextSize(ts);
        float tot = 0;
        float[] lw = new float[NOME.length()];
        for (int i = 0; i < lw.length; i++) { lw[i] = p.measureText(NOME, i, i + 1); tot += lw[i]; }
        float x = (w - tot) / 2f, base = h * .33f;
        for (int i = 0; i < lw.length; i++) {
            float k = ease((el - INI - i * LETRA) / (float) LETRA);
            if (k > 0) {
                boolean max = i >= 3;
                p.setColor(max ? 0xFFB46BFF : 0xFFEAEEF8);
                p.setShadowLayer(w * .03f, 0, 0, max ? 0xFF8A3DFF : 0xFF4D7BFF);
                p.setAlpha((int) (255 * k));
                float s = 1.5f - .5f * k;
                c.save();
                c.scale(s, s, x + lw[i] / 2f, base - ts * .35f);
                c.drawText(NOME, i, i + 1, x, base + (1f - k) * h * .03f, p);
                c.restore();
            }
            x += lw[i];
        }

        // logo
        if (logo != null && el >= LOGO_T) {
            float k = ease((el - LOGO_T) / (float) LOGO_D);
            float lwid = w * .84f, lh = lwid * logo.getHeight() / logo.getWidth();
            float s = .6f + .4f * k, cx = w / 2f, cy = h * .60f;
            r.set(cx - lwid * s / 2f, cy - lh * s / 2f, cx + lwid * s / 2f, cy + lh * s / 2f);
            bm.setAlpha((int) (255 * k));
            c.drawBitmap(logo, null, r, bm);
        }
        postInvalidateOnAnimation();
    }

    void fechar() {
        if (fim) return;
        fim = true;
        try {
            ViewGroup g = (ViewGroup) getParent();
            if (g != null) g.removeView(this);
        } catch (Throwable t) { }
    }
}
