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
    Movie movie; boolean custom; long stamp = -2, ultima;
    final View menu;
    final long t0 = SystemClock.uptimeMillis();

    GifBg(Context c, View menu) {
        super(c);
        this.menu = menu;
        setLayerType(LAYER_TYPE_SOFTWARE, null); // Movie (GIF) só desenha em software
        movie = carregar(c);
        if (menu instanceof MenuView) ScrollKeeper.install((MenuView) menu); // lista de jogos mantém a posição
    }

    static Movie carregar(Context c) {
        try {
            int id = c.getResources().getIdentifier("intro_fundo", "raw", c.getPackageName());
            if (id == 0) return null;
            try (InputStream in = c.getResources().openRawResource(id)) { return Movie.decodeStream(in); }
        } catch (Throwable t) { return null; }
    }

    static Movie carregarArquivo(java.io.File f) { try (InputStream in = new java.io.FileInputStream(f)) { byte[] b = new byte[(int) f.length()]; int off = 0, k; while (off < b.length && (k = in.read(b, off, b.length - off)) > 0) off += k; return Movie.decodeByteArray(b, 0, off); } catch (Throwable t) { return null; } }
    void checar() { long ag = SystemClock.uptimeMillis(); if (stamp != -2 && ag - ultima < 500) return; ultima = ag; long s = -1; java.io.File f = null; try { f = Personalizar.fundoGif(getContext()); if (f != null) s = f.lastModified() + f.length(); } catch (Throwable t) { } if (s == stamp) return; stamp = s; Movie m = f != null ? carregarArquivo(f) : null; custom = m != null; movie = m != null ? m : carregar(getContext()); }
    static void desenhar(Canvas c, Movie m, long el, float w, float h, boolean cobrir) { if (!cobrir) { desenhar(c, m, el, w, h); return; } if (m == null || m.width() <= 0 || m.height() <= 0 || w <= 0) return; int dur = m.duration() > 0 ? m.duration() : 1000; m.setTime((int) (el % dur)); float s = Math.max(w / m.width(), h / m.height()); c.save(); c.translate((w - m.width() * s) / 2f, (h - m.height() * s) / 2f); c.scale(s, s); m.draw(c, 0, 0); c.restore(); }
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
        checar(); desenhar(c, movie, SystemClock.uptimeMillis() - t0, getWidth(), getHeight(), custom);
        // dentro do jogo o fundo fica parado (economiza bateria)
        if (menu != null && menu.getVisibility() == VISIBLE) postInvalidateOnAnimation();
        else postInvalidateDelayed(300);
    }
}
