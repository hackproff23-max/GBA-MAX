package org.gbamax.emu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.VideoView;

import java.io.File;
import java.io.FileInputStream;

/**
 * Tela de abertura do app: imagem (3 s), GIF ou vídeo, no máximo 10 segundos.
 * Um toque pula. Se o arquivo não puder ser exibido, a abertura é pulada sem erro.
 */
final class Abertura extends FrameLayout {
    static final long MAX = 10000, IMG = 3000, GIF_MIN = 3000;

    boolean done;
    VideoView vv;
    final Runnable cut = new Runnable() { @Override public void run() { finish(); } };

    Abertura(Context c, File f) {
        super(c);
        setBackgroundColor(Color.BLACK);
        setClickable(true);
        setOnClickListener(v -> finish());
        boolean ok;
        try {
            String t = Personalizar.tipo(f);
            if (t.equals("video")) ok = iniciarVideo(c, f);
            else if (t.equals("gif")) ok = iniciarGif(c, f);
            else ok = iniciarImagem(c, f);
        } catch (Throwable e) {
            ok = false;
        }
        if (!ok) { post(cut); return; }
        postDelayed(cut, MAX);
    }

    boolean iniciarImagem(Context c, File f) {
        Bitmap b = Personalizar.decodeMax(f, 2000);
        if (b == null) return false;
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageBitmap(b);
        addView(iv, new LayoutParams(-1, -1));
        postDelayed(cut, IMG);
        return true;
    }

    boolean iniciarGif(Context c, File f) {
        Movie m;
        try (FileInputStream in = new FileInputStream(f)) {
            m = Movie.decodeStream(in);
        } catch (Throwable e) {
            m = null;
        }
        if (m == null || m.width() <= 0 || m.height() <= 0) return iniciarImagem(c, f); // 1º quadro
        addView(new GifView(c, m), new LayoutParams(-1, -1));
        return true;
    }

    boolean iniciarVideo(Context c, File f) {
        vv = new VideoView(c);
        addView(vv, new LayoutParams(-1, -1, Gravity.CENTER));
        vv.setOnCompletionListener(mp -> finish());
        vv.setOnErrorListener((mp, what, extra) -> { finish(); return true; });
        vv.setVideoPath(f.getAbsolutePath());
        vv.start();
        return true;
    }

    void finish() {
        if (done) return;
        done = true;
        removeCallbacks(cut);
        try { if (vv != null) vv.stopPlayback(); } catch (Throwable t) { }
        ViewParent p = getParent();
        if (p instanceof ViewGroup) ((ViewGroup) p).removeView(this);
    }

    /** GIF animado (android.graphics.Movie funciona em qualquer versão do Android). */
    final class GifView extends View {
        final Movie m;
        final long ini = SystemClock.uptimeMillis();
        final int dur;
        final long total;

        GifView(Context c, Movie mv) {
            super(c);
            m = mv;
            setLayerType(LAYER_TYPE_SOFTWARE, null); // Movie só desenha em software
            dur = Math.max(m.duration(), 100);
            // GIFs curtos repetem até uns 3 s; longos tocam uma vez, até 10 s
            total = dur >= GIF_MIN ? Math.min(dur, MAX) : (long) dur * (long) Math.ceil(GIF_MIN / (double) dur);
        }

        @Override protected void onDraw(Canvas c) {
            long el = SystemClock.uptimeMillis() - ini;
            if (el >= total) { post(cut); return; }
            m.setTime((int) (el % dur));
            float w = getWidth(), h = getHeight();
            float s = Math.min(w / m.width(), h / m.height());
            c.save();
            c.translate((w - m.width() * s) / 2f, (h - m.height() * s) / 2f);
            c.scale(s, s);
            m.draw(c, 0, 0);
            c.restore();
            postInvalidateOnAnimation();
        }
    }
}
