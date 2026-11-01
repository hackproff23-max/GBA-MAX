package org.gbamax.emu;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.VideoView;

/** Abertura do app: toca o vídeo embutido (res/raw/abertura.mp4) em tela cheia. Toque na tela para pular. */
class Intro extends FrameLayout {
    final VideoView vv;
    boolean fim;

    Intro(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        setClickable(true);
        vv = new VideoView(c);
        addView(vv, new LayoutParams(-1, -1, Gravity.CENTER));
        int id = c.getResources().getIdentifier("abertura", "raw", c.getPackageName());
        if (id == 0) { post(this::fechar); return; }
        vv.setVideoURI(Uri.parse("android.resource://" + c.getPackageName() + "/" + id));
        vv.setOnPreparedListener(mp -> { mp.setLooping(false); vv.start(); });
        vv.setOnCompletionListener(mp -> fechar());
        vv.setOnErrorListener((mp, w, e) -> { fechar(); return true; });
        setOnClickListener(v -> fechar());
    }

    void fechar() {
        if (fim) return;
        fim = true;
        try { vv.stopPlayback(); } catch (Throwable t) { }
        try {
            ViewGroup p = (ViewGroup) getParent();
            if (p != null) p.removeView(this);
        } catch (Throwable t) { }
    }
}
