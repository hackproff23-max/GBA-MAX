package org.gbamax.emu;

import android.view.ViewTreeObserver;
import android.widget.GridView;

/**
 * Lembra onde a lista de jogos estava (linha + deslocamento) e devolve a lista para o mesmo
 * lugar quando o menu volta (sair do jogo, girar a tela, mudar o número de colunas).
 */
final class ScrollKeeper implements ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnPreDrawListener {
    static int posS = -1, offS; // static: sobrevive se o menu for recriado

    final MenuView mv;
    final GridView g;
    boolean wasShown, restoring, pending;
    int lastCols;

    static void install(MenuView mv) { if (mv != null && mv.grid != null) new ScrollKeeper(mv); }

    private ScrollKeeper(MenuView m) {
        mv = m;
        g = m.grid;
        lastCols = g.getNumColumns();
        ViewTreeObserver o = m.getViewTreeObserver();
        o.addOnGlobalLayoutListener(this);
        o.addOnPreDrawListener(this);
    }

    /** A cada quadro com o menu visível, guarda a posição da lista. */
    @Override public boolean onPreDraw() {
        if (!restoring && mv.isShown() && g.getWidth() > 0 && g.getChildCount() > 0) {
            posS = g.getFirstVisiblePosition();
            offS = g.getChildAt(0).getTop();
        }
        return true;
    }

    @Override public void onGlobalLayout() {
        boolean shown = mv.isShown() && g.getWidth() > 0;
        int cols = g.getNumColumns();
        if (shown && (!wasShown || cols != lastCols)) pending = true; // menu voltou ou mudou de colunas
        wasShown = shown;
        if (shown) lastCols = cols;
        if (!pending) return;
        if (posS < 0) { pending = false; return; }
        if (shown && !restoring && !mv.games.isEmpty()) { pending = false; restore(); }
    }

    private void restore() {
        final int pos = Math.max(0, Math.min(posS, mv.games.size() - 1)), off = offS;
        restoring = true;
        g.setSelection(pos);
        g.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            int tries;
            @Override public boolean onPreDraw() {
                // espera o novo layout (pula este quadro); desiste depois de algumas tentativas
                if (g.isLayoutRequested() && mv.isShown() && ++tries < 4) return false;
                ViewTreeObserver o = g.getViewTreeObserver();
                if (o.isAlive()) o.removeOnPreDrawListener(this);
                if (mv.isShown()) {
                    android.view.View c = g.getChildAt(pos - g.getFirstVisiblePosition());
                    if (c != null) {
                        int d = c.getTop() - off;
                        if (d != 0) g.scrollListBy(d);
                    }
                }
                restoring = false;
                return true;
            }
        });
    }
}
