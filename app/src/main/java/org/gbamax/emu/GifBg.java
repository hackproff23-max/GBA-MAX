package org.gbamax.emu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.os.SystemClock;
import android.view.View;

import java.io.InputStream;

/** Fundo animado do app: o GIF esticado na tela toda (ignora a proporção). */
class GifBg extends View {
    final Movie movie;
    final View menu;
    final long t0 = SystemClock.uptimeMillis();

    GifBg(Context c, View menu) {
        super(c);
        this.menu = menu;
        setLayerType(LAYER_TYPE_SOFTWARE, null); // Movie (GIF) só desenha em software
        movie = carregar(c);
    }

    static Movie carregar(Context c) {
        try {
            int id = c.getResources().getIdentifier("intro_fundo", "raw", c.getPackageName());
            if (id == 0) return null;
            try (InputStream in = c.getResources().openRawResource(id)) { return Movie.decodeStream(in); }
        } catch (Throwable t) { return null; }
    }

    static void desenhar(Canvas c, Movie m, long el, float w, float h) {
        if (m == null || m.width() <= 0 || m.height() <= 0 || w <= 0) return;
        int dur = m.duration() > 0 ? m.duration() : 1000;
        m.setTime((int) (el % dur));
        c.save();
        c.scale(w / m.width(), h / m.height()); // estica: ignora a resolução/proporção
        m.draw(c, 0, 0);
        c.restore();
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(Color.BLACK);
        desenhar(c, movie, SystemClock.uptimeMillis() - t0, getWidth(), getHeight());
        // dentro do jogo o fundo fica parado (economiza bateria)
        if (menu != null && menu.getVisibility() == VISIBLE) postInvalidateOnAnimation();
        else postInvalidateDelayed(300);
    }
}
