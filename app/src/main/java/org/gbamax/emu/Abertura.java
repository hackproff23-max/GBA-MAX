package org.gbamax.emu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.net.Uri;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.VideoView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;

import java.io.File;
import java.io.FileInputStream;

/**
 * Tela de abertura do app: imagem (3 s), GIF ou vídeo, no máximo 10 segundos.
 * Um toque pula. Vídeo: tenta o ExoPlayer (com decodificador de reserva), depois o player do
 * Android; se nada tocar, a abertura é pulada sem erro.
 */
final class Abertura extends FrameLayout {
    static final long MAX = 10000, IMG = 3000, GIF_MIN = 3000;

    final Context ctx;
    final File arq;
    boolean done, triedVv, triedImg;
    ExoPlayer exo;
    SurfaceView sv;
    VideoView vv;
    final Runnable cut = new Runnable() { @Override public void run() { finish(); } };

    Abertura(Context c, File f) {
        super(c);
        ctx = c;
        arq = f;
        setBackgroundColor(Color.BLACK);
        setClickable(true);
        setOnClickListener(v -> finish());
        boolean ok;
        try {
            String t = Personalizar.tipo(f);
            if (t.equals("video")) ok = iniciarVideo();
            else if (t.equals("gif")) ok = iniciarGif();
            else ok = iniciarImagem();
        } catch (Throwable e) {
            ok = false;
        }
        if (!ok) { post(cut); return; }
        postDelayed(cut, MAX);
    }

    boolean iniciarImagem() {
        Bitmap b = Personalizar.decodeMax(arq, 2000);
        if (b == null) return false;
        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageBitmap(b);
        addView(iv, new LayoutParams(-1, -1));
        postDelayed(cut, IMG);
        return true;
    }

    boolean iniciarGif() {
        Movie m;
        try (FileInputStream in = new FileInputStream(arq)) {
            m = Movie.decodeStream(in);
        } catch (Throwable e) {
            m = null;
        }
        if (m == null || m.width() <= 0 || m.height() <= 0) return iniciarImagem(); // 1º quadro
        addView(new GifView(ctx, m), new LayoutParams(-1, -1));
        return true;
    }

    // ================= vídeo =================
    boolean iniciarVideo() {
        try {
            sv = new SurfaceView(ctx);
            addView(sv, new LayoutParams(-1, -1, Gravity.CENTER));
            // decodificador de reserva: se o principal não aguenta o vídeo, tenta o por software
            DefaultRenderersFactory rf = new DefaultRenderersFactory(ctx).setEnableDecoderFallback(true);
            exo = new ExoPlayer.Builder(ctx, rf).build();
            exo.setVideoSurfaceView(sv);
            exo.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int st) {
                    if (st == Player.STATE_ENDED) finish();
                }

                @Override public void onPlayerError(PlaybackException e) { videoFalhou(); }

                @Override public void onVideoSizeChanged(VideoSize vs) { ajustar(vs); }
            });
            exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(arq)));
            exo.setPlayWhenReady(true);
            exo.prepare();
            return true;
        } catch (Throwable t) {
            liberarExo();
            return iniciarVideoView();
        }
    }

    /** Encaixa o vídeo na tela mantendo a proporção. */
    void ajustar(VideoSize vs) {
        if (sv == null || vs.width <= 0 || vs.height <= 0) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int W = getWidth() > 0 ? getWidth() : dm.widthPixels;
        int H = getHeight() > 0 ? getHeight() : dm.heightPixels;
        float ar = vs.width * Math.max(vs.pixelWidthHeightRatio, 0.01f) / vs.height;
        int w = W, h = (int) (W / ar);
        if (h > H) { h = H; w = (int) (H * ar); }
        sv.setLayoutParams(new LayoutParams(w, h, Gravity.CENTER));
    }

    boolean iniciarVideoView() {
        if (triedVv) return false;
        triedVv = true;
        try {
            if (sv != null) { removeView(sv); sv = null; }
            vv = new VideoView(ctx);
            addView(vv, new LayoutParams(-1, -1, Gravity.CENTER));
            vv.setOnCompletionListener(mp -> finish());
            vv.setOnErrorListener((mp, what, extra) -> { videoFalhou(); return true; });
            vv.setVideoPath(arq.getAbsolutePath());
            vv.start();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** ExoPlayer -> player do Android -> imagem -> pula. */
    void videoFalhou() {
        if (done) return;
        liberarExo();
        if (!triedVv) {
            if (iniciarVideoView()) return;
        }
        if (!triedImg) {
            triedImg = true;
            try { if (vv != null) { vv.stopPlayback(); removeView(vv); vv = null; } } catch (Throwable t) { }
            if (iniciarImagem()) return;
        }
        finish();
    }

    void liberarExo() {
        try { if (exo != null) { exo.release(); exo = null; } } catch (Throwable t) { exo = null; }
    }

    void finish() {
        if (done) return;
        done = true;
        removeCallbacks(cut);
        liberarExo();
        try { if (vv != null) vv.stopPlayback(); } catch (Throwable t) { }
        ViewParent p = getParent();
        if (p instanceof ViewGroup) ((ViewGroup) p).removeView(this);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        finish();
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
