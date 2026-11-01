package org.gbamax.emu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.io.InputStream;

/** Abertura: GIF esticado na tela toda e o logo um pouco acima do centro. Toque para pular. */
class Intro extends View {
    static final int LOGO_T = 400, LOGO_D = 800, SEGURA = 3600, SAIDA = 450;

    Movie movie;
    Bitmap logo;
    final Paint bm = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final RectF r = new RectF();
    long t0;
    boolean fim;

    Intro(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // Movie (GIF) só desenha em software
        setClickable(true);
        setOnClickListener(v -> fechar());
        movie = GifBg.carregar(c);
        try {
            int id = c.getResources().getIdentifier("intro_logo", "raw", c.getPackageName());
            if (id != 0) try (InputStream in = c.getResources().openRawResource(id)) { logo = BitmapFactory.decodeStream(in); }
        } catch (Throwable t) { logo = null; }
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
        GifBg.desenhar(c, movie, el, w, h);
        c.drawColor(0x30000000);

        if (logo != null && el >= LOGO_T) {
            float k = ease((el - LOGO_T) / (float) LOGO_D);
            float lwid = w * .84f, lh = lwid * logo.getHeight() / logo.getWidth();
            float s = .6f + .4f * k, cx = w / 2f, cy = h * .44f; // um pouco acima do centro
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
