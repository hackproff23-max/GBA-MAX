package org.gbamax.emu;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;

/**
 * Grade de jogos com o mesmo efeito de seleção da janela de Configurações:
 * ao encostar e deslizar o dedo por cima dos jogos, o jogo sob o dedo ganha contorno azul pulsando e cresce um pouco.
 * A rolagem e o toque para abrir continuam iguais.
 */
final class HoverGrid extends GridView {
    final float den;
    View hot;
    GradientDrawable dr;
    ValueAnimator an;

    HoverGrid(Context c) {
        super(c);
        den = c.getResources().getDisplayMetrics().density;
    }

    int dp(float v) { return (int) (v * den + .5f); }

    View cellAt(float x, float y) {
        int p = pointToPosition((int) x, (int) y);
        if (p == INVALID_POSITION) return null;
        return getChildAt(p - getFirstVisiblePosition());
    }

    void setHot(View v) {
        if (v == hot) return;
        if (hot != null) {
            if (an != null) an.cancel();
            if (dr != null) hot.getOverlay().remove(dr);
            hot.animate().cancel();
            hot.animate().scaleX(1f).scaleY(1f).translationZ(0).setDuration(140).start();
        }
        hot = v;
        if (v == null) return;
        // contorno em volta da capa (primeiro filho da célula); sem filhos, a célula inteira
        int l = 0, t = 0, r = v.getWidth(), b = v.getHeight();
        if (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0) {
            View cv = ((ViewGroup) v).getChildAt(0);
            int in = dp(6);
            l = cv.getLeft() + in; t = cv.getTop() + in; r = cv.getRight() - in; b = cv.getBottom() - in;
        }
        dr = new GradientDrawable();
        dr.setCornerRadius(dp(6));
        dr.setColor(0x40FFFFFF);
        dr.setStroke(dp(2), 0xFF4D9BFF);
        dr.setBounds(l, t, r, b);
        v.getOverlay().add(dr);
        v.animate().cancel();
        v.animate().scaleX(1.05f).scaleY(1.05f).translationZ(dp(6)).setDuration(140).start();
        final GradientDrawable d2 = dr;
        an = ValueAnimator.ofInt(110, 255);
        an.setDuration(650);
        an.setRepeatMode(ValueAnimator.REVERSE);
        an.setRepeatCount(ValueAnimator.INFINITE);
        an.addUpdateListener(x -> d2.setAlpha((int) x.getAnimatedValue()));
        an.start();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        int ac = e.getActionMasked();
        if (ac == MotionEvent.ACTION_DOWN || ac == MotionEvent.ACTION_MOVE) setHot(cellAt(e.getX(), e.getY()));
        else if (ac == MotionEvent.ACTION_UP || ac == MotionEvent.ACTION_CANCEL) setHot(null);
        return super.dispatchTouchEvent(e);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        int ac = e.getActionMasked();
        if (ac == MotionEvent.ACTION_HOVER_ENTER || ac == MotionEvent.ACTION_HOVER_MOVE) setHot(cellAt(e.getX(), e.getY()));
        else if (ac == MotionEvent.ACTION_HOVER_EXIT) setHot(null);
        return super.dispatchGenericMotionEvent(e);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        setHot(null);
    }
}
