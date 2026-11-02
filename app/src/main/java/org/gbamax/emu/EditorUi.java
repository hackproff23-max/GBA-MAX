package org.gbamax.emu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.widget.Toast;

import java.util.Arrays;

/**
 * Editor de layout do GameView.
 * Na tela fica só o ícone de engrenagem; opacidade, tamanho, formato, ocultar, padrão etc.
 * ficam na janela dele, valendo para o botão selecionado ou para todos.
 * Um botão só move quando o dedo começa em cima dele (ou no modo "Mover todos").
 */
final class EditorUi {
    static final int N = GameView.N;
    // cells: 0 sel, 1 todos, 2 opacidade, 3 tamanho, 4 formato, 5 ocultar, 6 padrão, 7 mover todos, 8 tela cheia, 9 trocar, 10 salvar
    static final RectF[] cell = new RectF[11];
    static final RectF panelR = new RectF(), gear = new RectF(), pill = new RectF();
    static { for (int i = 0; i < cell.length; i++) cell[i] = new RectF(); }

    /** Opacidade própria de cada item (0..14 botões, 15 analógico, 17 D-pad); -1 = usa a global. */
    static final int[] AL = new int[N];
    static boolean loaded, snapped;
    static final int[] sAL = new int[N], sShp = new int[15];
    static int sGlobal;

    static boolean open, scopeAll = true, dragging, moved, padDown;
    static float lx, ly, dx0, dy0;
    static int repCell = -1, repDir;
    static GameView cur;

    static final Runnable REP = new Runnable() {
        @Override public void run() {
            GameView g = cur;
            if (g == null || repDir == 0 || repCell < 0) return;
            step(g, repCell, repDir);
            g.postDelayed(this, 70);
        }
    };

    // ================= opacidade por item =================
    static void load(Context c) {
        if (loaded) return;
        loaded = true;
        Arrays.fill(AL, -1);
        try {
            String s = c.getSharedPreferences("gbamax_video", 0).getString("btnAlpha", null);
            if (s == null) return;
            String[] t = s.split(",");
            for (int i = 0; i < N && i < t.length; i++) AL[i] = Integer.parseInt(t[i].trim());
        } catch (Throwable t) { }
    }

    static void saveAlpha(Context c) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < N; i++) { if (i > 0) sb.append(','); sb.append(AL[i]); }
            c.getSharedPreferences("gbamax_video", 0).edit().putString("btnAlpha", sb.toString()).apply();
        } catch (Throwable t) { }
    }

    /** Opacidade (10..100) do item i: a própria, ou a global. */
    static int baseOf(GameView g, int i, boolean ed) {
        load(g.getContext());
        int a = i >= 0 && i < N && AL[i] >= 0 ? AL[i] : VideoSettings.ctlAlpha;
        return Math.max(ed ? 15 : 10, Math.min(100, a));
    }

    // ================= ciclo da edição =================
    static void begin(GameView g) {
        cur = g;
        load(g.getContext());
        System.arraycopy(AL, 0, sAL, 0, N);
        System.arraycopy(g.shp, 0, sShp, 0, 15);
        sGlobal = VideoSettings.ctlAlpha;
        snapped = true;
        open = false;
        scopeAll = true;
        dragging = false;
        stopRep(g);
    }

    /** Cancelar/voltar: desfaz opacidade e formatos mexidos nesta edição. */
    static void cancel(GameView g) {
        stopRep(g);
        open = false;
        if (!snapped) return;
        snapped = false;
        System.arraycopy(sAL, 0, AL, 0, N);
        System.arraycopy(sShp, 0, g.shp, 0, 15);
        VideoSettings.ctlAlpha = sGlobal;
    }

    static void save(GameView g) {
        stopRep(g);
        Context c = g.getContext();
        g.saveLayout();
        saveAlpha(c);
        g.saveShapes();
        VideoSettings.save(c);
        snapped = false;
        open = false;
        g.editing = false;
        g.pop = false;
        g.panel = 0;
        g.moveAll = false;
        if (g.onEditDone != null) g.onEditDone.run();
    }

    static void stopRep(GameView g) {
        repDir = 0;
        repCell = -1;
        if (g != null) g.removeCallbacks(REP);
    }

    // ================= utilidades =================
    static int cl(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    static boolean all(GameView g) { return scopeAll || g.sel < 0; }

    static String name(int i) {
        if (i >= 0 && i < 15) return GameView.LAB[i];
        if (i == GameView.DPAD) return "Analógico";
        if (i == GameView.DPC) return "D-pad";
        if (i == GameView.SCREEN) return "Tela";
        return "";
    }

    static float cyOf(GameView g, int i) {
        if (i >= 0 && i < 15) return g.py[i];
        if (i == GameView.DPAD) return g.dcy;
        if (i == GameView.DPC) return g.ccy;
        return g.scr.centerY();
    }

    /** Toque preciso: só vale em cima do botão (visíveis primeiro, depois os ocultos), depois a tela. */
    static int hit(GameView g, float x, float y) {
        float slack = 4 * g.den;
        for (int pass = 0; pass < 2; pass++) {
            boolean wantHidden = pass == 1;
            int best = -1;
            float bs = 1e9f;
            if (g.isHid(GameView.DPAD) == wantHidden) {
                float d = (float) Math.hypot(x - g.dcx, y - g.dcy);
                if (d <= g.dr + slack && d / g.dr < bs) { bs = d / g.dr; best = GameView.DPAD; }
            }
            if (g.isHid(GameView.DPC) == wantHidden) {
                float d = (float) Math.hypot(x - g.ccx, y - g.ccy);
                if (d <= g.cr + slack && d / g.cr < bs) { bs = d / g.cr; best = GameView.DPC; }
            }
            for (int j = 0; j < 15; j++) {
                if (g.isHid(j) != wantHidden) continue;
                float d = (float) Math.hypot(x - g.px[j], y - g.py[j]);
                if (d <= g.pr[j] + slack && d / g.pr[j] < bs) { bs = d / g.pr[j]; best = j; }
            }
            if (best >= 0) return best;
        }
        return g.scr.contains(x, y) ? GameView.SCREEN : -1;
    }

    // ================= geometria da janela =================
    static void layout(GameView g) {
        float den = g.den;
        float gx = g.vw - 14 * den - 22 * den, gy = 14 * den + 22 * den;
        gear.set(gx - 27 * den, gy - 27 * den, gx + 27 * den, gy + 27 * den);
        pill.set(g.vw / 2f - 100 * den, 16 * den, g.vw / 2f + 100 * den, 52 * den);

        float pw = Math.min(g.vw - 24 * den, 340 * den), gap = 6 * den, pad = 10 * den, title = 26 * den;
        float ch = Math.max(28 * den, Math.min(38 * den, (g.vh - 24 * den - title - 2 * pad - 5 * gap - 56 * den) / 6f));
        float ph = 2 * pad + title + 6 * ch + 5 * gap;
        float left = (g.vw - pw) / 2f;
        boolean bottom = g.sel >= 0 && cyOf(g, g.sel) < g.vh * 0.5f;
        float top = bottom ? g.vh - ph - 12 * den : 62 * den;
        top = Math.max(8 * den, Math.min(top, g.vh - ph - 8 * den));
        panelR.set(left, top, left + pw, top + ph);
        float cw = (pw - 2 * pad - gap) / 2f, x0 = left + pad, x1 = x0 + cw + gap, y0 = top + pad + title;
        for (int r = 0; r < 5; r++) {
            float y = y0 + r * (ch + gap);
            cell[r * 2].set(x0, y, x0 + cw, y + ch);
            cell[r * 2 + 1].set(x1, y, x1 + cw, y + ch);
        }
        float y5 = y0 + 5 * (ch + gap);
        cell[10].set(x0, y5, x1 + cw, y5 + ch);
    }

    // ================= toque =================
    static boolean touch(GameView g, MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                cur = g;
                stopRep(g);
                dragging = false;
                moved = false;
                padDown = false;
                layout(g);
                if (gear.contains(x, y)) {
                    open = !open;
                    if (open) scopeAll = g.sel < 0;
                    padDown = true;
                    g.invalidate();
                    return true;
                }
                if (g.moveAll) {
                    if (pill.contains(x, y)) { g.moveAll = false; padDown = true; g.invalidate(); return true; }
                    lx = x; ly = y; dx0 = x; dy0 = y;
                    dragging = true;
                    return true;
                }
                if (open && panelR.contains(x, y)) {
                    padDown = true;
                    for (int i = 0; i < cell.length; i++) if (cell[i].contains(x, y)) { act(g, i, x); break; }
                    g.invalidate();
                    return true;
                }
                int h = hit(g, x, y);
                g.sel = h;
                lx = x; ly = y; dx0 = x; dy0 = y;
                dragging = h >= 0 && !(h == GameView.SCREEN && g.sfh > 0);
                g.invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (padDown || !dragging) return true;
                if (!moved) {
                    if (Math.hypot(x - dx0, y - dy0) < g.slop) return true;
                    moved = true;
                }
                if (g.moveAll) {
                    g.shiftAll((x - lx) / g.vw, (y - ly) / g.vh);
                } else if (g.sel >= 0) {
                    g.ax[g.sel] = Math.max(0f, Math.min(1f, g.ax[g.sel] + (x - lx) / g.vw));
                    g.ay[g.sel] = Math.max(0f, Math.min(1f, g.ay[g.sel] + (y - ly) / g.vh));
                }
                lx = x; ly = y;
                g.recalc();
                g.invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                stopRep(g);
                dragging = false;
                padDown = false;
                g.invalidate();
                return true;
        }
        return true;
    }

    static void act(GameView g, int i, float x) {
        boolean selOk = g.sel >= 0 && g.sel != GameView.SCREEN;
        switch (i) {
            case 0: if (g.sel >= 0) scopeAll = false; break;
            case 1: scopeAll = true; break;
            case 2: case 3: case 4: {
                RectF r = cell[i];
                float w = r.width();
                int dir = x < r.left + w * 0.32f ? -1 : x > r.right - w * 0.32f ? 1 : 0;
                if (dir != 0) {
                    step(g, i, dir);
                    repCell = i;
                    repDir = dir;
                    g.postDelayed(REP, 380);
                }
                break;
            }
            case 5:
                if (selOk) g.hidden ^= 1 << g.sel;
                else toast(g, "Toque num botão antes");
                break;
            case 6: reset(g); break;
            case 7: g.moveAll = true; open = false; break;
            case 8: g.toggleFullscreen(); break;
            case 9: g.swapDirectional(); break;
            case 10: save(g); break;
        }
    }

    static void toast(GameView g, String s) {
        Toast.makeText(g.getContext(), s, Toast.LENGTH_SHORT).show();
    }

    /** dir = -1 (−) ou +1 (+) para opacidade (2), tamanho (3) ou formato (4). */
    static void step(GameView g, int i, int dir) {
        boolean all = all(g);
        int s = g.sel;
        if (i == 2) {
            if (all) {
                VideoSettings.ctlAlpha = cl(VideoSettings.ctlAlpha + dir * 5, 10, 100);
                Arrays.fill(AL, -1);
            } else if (s != GameView.SCREEN) {
                int cur0 = AL[s] >= 0 ? AL[s] : VideoSettings.ctlAlpha;
                AL[s] = cl(cur0 + dir * 5, 10, 100);
            }
        } else if (i == 3) {
            float f = dir > 0 ? 1.06f : 0.94f;
            if (all) {
                g.scaleAll(f);
            } else {
                boolean dp = s == GameView.DPAD || s == GameView.DPC;
                float lo = s == GameView.SCREEN ? .3f : dp ? .06f : .025f;
                float hi = s == GameView.SCREEN ? 1f : dp ? .4f : .25f;
                if (s == GameView.SCREEN) g.sfh = 0;
                g.ar[s] = Math.max(lo, Math.min(hi, g.ar[s] * f));
                g.recalc();
            }
        } else {
            int n = GameView.SHAPES.length;
            if (all) {
                int b = s >= 0 && s < 15 ? g.shp[s] : g.shp[0];
                int v = (b + dir + n) % n;
                for (int k = 0; k < 15; k++) g.shp[k] = v;
            } else if (s < 15) {
                g.shp[s] = (g.shp[s] + dir + n) % n;
            }
        }
        g.invalidate();
    }

    static void reset(GameView g) {
        int s = g.sel;
        if (all(g)) {
            g.sel = -1;
            g.toolbar(2); // layout padrão de tudo e apaga o salvo
            g.sel = s;
            VideoSettings.ctlAlpha = 70;
            Arrays.fill(AL, -1);
            Arrays.fill(g.shp, 0);
        } else {
            g.toolbar(2); // só o item selecionado
            if (s < 15) g.shp[s] = 0;
            if (s != GameView.SCREEN) AL[s] = -1;
        }
        g.invalidate();
    }

    // ================= desenho =================
    /** Fundo do editor (embaixo dos botões): área da tela e anel do selecionado. */
    static void draw(GameView g, Canvas c) {
        layout(g);
        Paint p = g.p;
        float den = g.den;
        int sel = g.sel;
        c.drawColor(0x55000000);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x33FFFFFF);
        c.drawRect(g.scr, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3 * den);
        p.setColor(sel == GameView.SCREEN ? 0xFFFFD54F : 0xCCFFFFFF);
        c.drawRect(g.scr, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xCCFFFFFF);
        p.setTextSize(16 * den);
        c.drawText(g.sfh > 0 ? "TELA CHEIA" : "TELA", g.scr.centerX(), g.scr.centerY(), p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4 * den);
        p.setColor(0xFFFFD54F);
        if (sel == GameView.DPAD) c.drawCircle(g.dcx, g.dcy, g.dr + 4 * den, p);
        else if (sel == GameView.DPC) c.drawCircle(g.ccx, g.ccy, g.cr + 4 * den, p);
        else if (sel >= 0 && sel < 15) c.drawCircle(g.px[sel], g.py[sel], g.pr[sel] + 4 * den, p);
    }

    /** Por cima de tudo: botão "Concluir" do modo mover todos, engrenagem e a janela. */
    static void drawTop(GameView g, Canvas c) {
        layout(g);
        Paint p = g.p;
        float den = g.den;
        if (g.moveAll) {
            box(g, c, pill, "Concluir mover todos", 0xFFFFA000, false, false);
        }
        gearIcon(g, c, gear.centerX(), gear.centerY(), 22 * den, open);
        if (!open || g.moveAll) return;

        p.setStyle(Paint.Style.FILL);
        p.setColor(0xF01B212C);
        c.drawRoundRect(panelR, 16 * den, 16 * den, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * den);
        p.setColor(0x88FFFFFF);
        c.drawRoundRect(panelR, 16 * den, 16 * den, p);

        boolean all = all(g);
        int s = g.sel;
        boolean selOk = s >= 0 && s != GameView.SCREEN;
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        p.setFakeBoldText(true);
        p.setTextSize(14 * den);
        c.drawText("Configurações · " + (all ? "todos os botões" : name(s)), panelR.centerX(), panelR.top + 10 * den + 17 * den, p);
        p.setFakeBoldText(false);

        box(g, c, cell[0], s >= 0 ? "Selecionado: " + name(s) : "Nenhum selecionado", 0xFF2C3442, !all, s < 0);
        box(g, c, cell[1], "Todos", 0xFF2C3442, all, false);

        int alpha = all ? VideoSettings.ctlAlpha : s == GameView.SCREEN ? -1 : (AL[s] >= 0 ? AL[s] : VideoSettings.ctlAlpha);
        stepper(g, c, cell[2], "Opacidade " + (alpha < 0 ? "—" : alpha + "%"), !all && s == GameView.SCREEN);

        float sz = 0;
        if (!all) sz = (s == GameView.DPAD ? g.dr : s == GameView.DPC ? g.cr : s == GameView.SCREEN ? g.scr.width() / 2f : g.pr[s]) / den;
        stepper(g, c, cell[3], all ? "Tamanho" : "Tamanho " + Math.round(sz), false);

        int ref = !all && s >= 0 && s < 15 ? g.shp[s] : g.shp[0];
        boolean noShape = !all && s >= 15;
        stepper(g, c, cell[4], noShape ? "Formato —" : GameView.SHAPES[ref], noShape);

        box(g, c, cell[5], selOk && g.isHid(s) ? "Mostrar" : "Ocultar", 0xFF2C3442, false, !selOk);
        box(g, c, cell[6], all ? "Padrão (todos)" : "Padrão", 0xFF2C3442, false, false);
        box(g, c, cell[7], "Mover todos", 0xFF7E57C2, false, false);
        box(g, c, cell[8], g.sfh > 0 ? "Sair da tela cheia" : "Tela cheia", 0xFF2C3442, false, false);
        box(g, c, cell[9], "D-pad / Analógico", 0xFF2C3442, false, false);
        box(g, c, cell[10], "Salvar e sair", 0xFF2EAD4B, false, false);
    }

    static void box(GameView g, Canvas c, RectF r, String t, int fill, boolean ring, boolean dim) {
        Paint p = g.p;
        float den = g.den;
        p.setStyle(Paint.Style.FILL);
        p.setColor(ring ? 0xFF1E6BE6 : fill);
        c.drawRoundRect(r, 10 * den, 10 * den, p);
        if (ring) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * den);
            p.setColor(0xFFFFD54F);
            c.drawRoundRect(r, 10 * den, 10 * den, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(dim ? 0x66FFFFFF : Color.WHITE);
        p.setTextSize(12 * den);
        c.drawText(t, r.centerX(), r.centerY() + 4.5f * den, p);
    }

    static void stepper(GameView g, Canvas c, RectF r, String t, boolean dim) {
        box(g, c, r, t, 0xFF2C3442, false, dim);
        Paint p = g.p;
        float den = g.den;
        p.setStyle(Paint.Style.FILL);
        p.setColor(dim ? 0x66FFFFFF : 0xFFFFD54F);
        p.setFakeBoldText(true);
        p.setTextSize(20 * den);
        c.drawText("−", r.left + r.width() * 0.16f, r.centerY() + 7 * den, p);
        c.drawText("+", r.right - r.width() * 0.16f, r.centerY() + 7 * den, p);
        p.setFakeBoldText(false);
    }

    static void gearIcon(GameView g, Canvas c, float cx, float cy, float r, boolean on) {
        Paint p = g.p;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xF0222833);
        c.drawCircle(cx, cy, r, p);
        p.setColor(on ? 0xFFFFD54F : Color.WHITE);
        float R = r * 0.52f, tw = r * 0.14f, th = r * 0.24f;
        for (int k = 0; k < 8; k++) {
            c.save();
            c.rotate(k * 45f, cx, cy);
            c.drawRect(cx - tw, cy - R - th, cx + tw, cy - R + th * 0.5f, p);
            c.restore();
        }
        c.drawCircle(cx, cy, R, p);
        p.setColor(0xF0222833);
        c.drawCircle(cx, cy, R * 0.42f, p);
    }
}
