package org.gbamax.emu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Camada de controles (transparente). A imagem do jogo é desenhada pelo GlScreen por baixo. */
public class GameView extends View {
    // bits no padrão libretro
    static final int B = 1, SEL = 4, ST = 8, UP = 16, DN = 32, LF = 64, RT = 128, A = 256, L = 1024, R = 2048;
    static final int TA = -1, TB = -2, FF = -3, ROM = -4;
    static final String[] LAB = {"L", "L+A", "L+B", "R+B", "R+A", "R", "A", "B", "A+B", "TA", "TB", "SEL", "START", "FF", "MENU"};
    static final int[] COD = {L, L | A, L | B, R | B, R | A, R, A, B, A | B, TA, TB, SEL, ST, FF, ROM};
    // 8 direções: direita, baixo-dir, baixo, baixo-esq, esquerda, cima-esq, cima, cima-dir
    static final int[] DIRS = {RT, DN | RT, DN, DN | LF, LF, UP | LF, UP, UP | RT};
    // retrato: x%largura, y%área de controles, raio%largura
    static final float[][] P = {
        {9, 7, 6.5f}, {25, 7, 6.5f}, {41, 7, 6.5f}, {59, 7, 6.5f}, {75, 7, 6.5f}, {91, 7, 6.5f},
        {87, 34, 9}, {65, 52, 9}, {87, 50, 6}, {87, 66, 7}, {65, 76, 7},
        {38, 88, 5.5f}, {54, 88, 5.5f}, {80, 88, 6}, {50, 24, 5}};
    static final float[] PD = {24, 52, 17};
    // paisagem: x%largura, y%altura, raio%altura
    static final float[][] LS = {
        {5, 11, 8}, {13, 11, 8}, {21, 11, 8}, {79, 11, 8}, {87, 11, 8}, {95, 11, 8},
        {94, 52, 11}, {85, 72, 11}, {79, 50, 8}, {95, 85, 8}, {77, 85, 8},
        {40, 92, 6}, {50, 92, 6}, {60, 92, 6}, {50, 6, 5}};
    static final float[] LD = {13, 68, 24};

    // itens do layout: 0..14 botões, 15 direcional, 16 tela
    static final int N = 17, DPAD = 15, SCREEN = 16;

    volatile int touch;
    volatile boolean ta, tb, ff;
    Runnable onRom;
    GlScreen gl;

    // layout em frações da view: x/largura, y/altura, raio/min(largura,altura) (tela: largura/largura)
    final float[] ax = new float[N], ay = new float[N], ar = new float[N];
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF scr = new RectF();
    final float[] px = new float[15], py = new float[15], pr = new float[15];
    float dcx, dcy, dr, kx, ky;
    boolean knob, land;
    int pressed, vw, vh;
    final float den;

    // modo de edição
    volatile boolean editing;
    Runnable onEditDone;
    int sel = -1;
    float lx, ly;
    final RectF[] tb4 = {new RectF(), new RectF(), new RectF(), new RectF()};
    static final String[] TBL = {"Menor", "Maior", "Padrão", "Salvar"};

    public GameView(Context c) {
        super(c);
        den = c.getResources().getDisplayMetrics().density;
        p.setTextAlign(Paint.Align.CENTER);
    }

    int mask(int frame) {
        int m = touch;
        boolean on = ((frame >> 1) & 1) == 0; // turbo ~15 toques/s
        if (ta && on) m |= A;
        if (tb && on) m |= B;
        return m;
    }

    // ================= layout =================
    void defaults(int w, int h) {
        float mn = Math.min(w, h);
        if (h > w) {
            float sh = w * 2f / 3, ch = h - sh;
            for (int i = 0; i < 15; i++) {
                ax[i] = P[i][0] / 100f;
                ay[i] = (sh + P[i][1] * ch / 100) / h;
                ar[i] = P[i][2] * w / 100 / mn;
            }
            ax[DPAD] = PD[0] / 100f; ay[DPAD] = (sh + PD[1] * ch / 100) / h; ar[DPAD] = PD[2] * w / 100 / mn;
            ax[SCREEN] = .5f; ay[SCREEN] = sh / 2 / h; ar[SCREEN] = 1f;
        } else {
            for (int i = 0; i < 15; i++) {
                ax[i] = LS[i][0] / 100f;
                ay[i] = LS[i][1] / 100f;
                ar[i] = LS[i][2] / 100f;
            }
            ax[DPAD] = LD[0] / 100f; ay[DPAD] = LD[1] / 100f; ar[DPAD] = LD[2] / 100f;
            float sw = Math.min(w, h * 1.5f);
            ax[SCREEN] = .5f; ay[SCREEN] = .5f; ar[SCREEN] = sw / w;
        }
    }

    boolean loadSaved() {
        String s = VideoSettings.getLayout(getContext(), land);
        if (s == null) return false;
        try {
            String[] t = s.split(",");
            if (t.length != N * 3) return false;
            float[] a = new float[N * 3];
            for (int i = 0; i < a.length; i++) a[i] = Float.parseFloat(t[i]);
            for (int i = 0; i < N; i++) { ax[i] = a[i * 3]; ay[i] = a[i * 3 + 1]; ar[i] = a[i * 3 + 2]; }
            return true;
        } catch (Exception e) { return false; }
    }

    void saveLayout() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < N; i++) {
            if (i > 0) sb.append(',');
            sb.append(ax[i]).append(',').append(ay[i]).append(',').append(ar[i]);
        }
        VideoSettings.setLayout(getContext(), land, sb.toString());
    }

    void reloadLayout() {
        if (vw <= 0) return;
        defaults(vw, vh);
        loadSaved();
        recalc();
    }

    void recalc() {
        float mn = Math.min(vw, vh);
        for (int i = 0; i < 15; i++) { px[i] = ax[i] * vw; py[i] = ay[i] * vh; pr[i] = ar[i] * mn; }
        dcx = ax[DPAD] * vw; dcy = ay[DPAD] * vh; dr = ar[DPAD] * mn;
        float sw = ar[SCREEN] * vw, sh = sw / 1.5f, cx = ax[SCREEN] * vw, cy = ay[SCREEN] * vh;
        scr.set(cx - sw / 2, cy - sh / 2, cx + sw / 2, cy + sh / 2);
        if (gl != null) gl.setRect(scr.left, scr.top, scr.right, scr.bottom);
        float bw = Math.min(vw / 4.4f, 96 * den), bh = 40 * den, gap = 6 * den;
        float tot = bw * 4 + gap * 3, x0 = (vw - tot) / 2, y0 = 8 * den;
        for (int i = 0; i < 4; i++) tb4[i].set(x0 + i * (bw + gap), y0, x0 + i * (bw + gap) + bw, y0 + bh);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        vw = w; vh = h;
        land = w > h;
        reloadLayout();
    }

    // ================= edição =================
    public void startEdit(Runnable done) {
        editing = true;
        onEditDone = done;
        sel = -1;
        touch = 0; ta = false; tb = false; pressed = 0; knob = false;
        invalidate();
    }

    public void cancelEdit() {
        editing = false;
        reloadLayout();
        invalidate();
    }

    int pick(float x, float y) {
        if (Math.hypot(x - dcx, y - dcy) < dr * 1.1) return DPAD;
        int best = -1;
        float bd = 1e9f;
        for (int j = 0; j < 15; j++) {
            float dd = (float) Math.hypot(x - px[j], y - py[j]);
            if (dd < pr[j] * 1.2f && dd < bd) { bd = dd; best = j; }
        }
        if (best >= 0) return best;
        return scr.contains(x, y) ? SCREEN : -1;
    }

    boolean editTouch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                for (int i = 0; i < 4; i++) if (tb4[i].contains(x, y)) { toolbar(i); return true; }
                sel = pick(x, y);
                lx = x; ly = y;
                break;
            case MotionEvent.ACTION_MOVE:
                if (sel >= 0) {
                    ax[sel] = Math.max(0f, Math.min(1f, ax[sel] + (x - lx) / vw));
                    ay[sel] = Math.max(0f, Math.min(1f, ay[sel] + (y - ly) / vh));
                    lx = x; ly = y;
                    recalc();
                }
                break;
        }
        invalidate();
        return true;
    }

    void toolbar(int i) {
        if (i == 3) {
            saveLayout();
            editing = false;
            if (onEditDone != null) onEditDone.run();
            return;
        }
        if (i == 2) {
            if (sel < 0) {
                defaults(vw, vh);
                VideoSettings.setLayout(getContext(), land, null);
            } else {
                float[] oa = ax.clone(), oy = ay.clone(), orr = ar.clone();
                defaults(vw, vh);
                float dx = ax[sel], dy = ay[sel], dRad = ar[sel];
                System.arraycopy(oa, 0, ax, 0, N);
                System.arraycopy(oy, 0, ay, 0, N);
                System.arraycopy(orr, 0, ar, 0, N);
                ax[sel] = dx; ay[sel] = dy; ar[sel] = dRad;
            }
            recalc();
            invalidate();
            return;
        }
        if (sel < 0) return;
        float f = i == 0 ? 0.92f : 1.08f;
        float lo = sel == SCREEN ? .3f : sel == DPAD ? .06f : .025f;
        float hi = sel == SCREEN ? 1f : sel == DPAD ? .4f : .25f;
        ar[sel] = Math.max(lo, Math.min(hi, ar[sel] * f));
        recalc();
        invalidate();
    }

    void drawEdit(Canvas c) {
        c.drawColor(0x55000000);
        // área da tela do jogo
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x33FFFFFF);
        c.drawRect(scr, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3 * den);
        p.setColor(sel == SCREEN ? 0xFFFFD54F : 0xCCFFFFFF);
        c.drawRect(scr, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xCCFFFFFF);
        p.setTextSize(16 * den);
        c.drawText("TELA", scr.centerX(), scr.centerY(), p);
        if (sel == DPAD) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4 * den);
            p.setColor(0xFFFFD54F);
            c.drawCircle(dcx, dcy, dr + 4 * den, p);
        } else if (sel >= 0 && sel < 15) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4 * den);
            p.setColor(0xFFFFD54F);
            c.drawCircle(px[sel], py[sel], pr[sel] + 4 * den, p);
        }
        p.setTextSize(13 * den);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xEEFFFFFF);
        c.drawText(sel < 0 ? "Toque num item e arraste" : "Arraste ou use Menor/Maior", vw / 2f, tb4[0].bottom + 22 * den, p);
        for (int i = 0; i < 4; i++) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(i == 3 ? 0xFF2EAD4B : 0xFF1E6BE6);
            c.drawRoundRect(tb4[i], 12 * den, 12 * den, p);
            p.setColor(Color.WHITE);
            p.setTextSize(14 * den);
            c.drawText(TBL[i], tb4[i].centerX(), tb4[i].centerY() + 5 * den, p);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (editing) drawEdit(c);
        p.setStrokeWidth(3);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(0x88FFFFFF);
        c.drawCircle(dcx, dcy, dr, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(knob ? 0x88FFFFFF : 0x33FFFFFF);
        c.drawCircle(knob ? kx : dcx, knob ? ky : dcy, dr * 0.4f, p);
        for (int i = 0; i < 15; i++) {
            boolean on = ((pressed >> i) & 1) != 0 || (COD[i] == FF && ff);
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? 0x88FFFFFF : 0x33FFFFFF);
            c.drawCircle(px[i], py[i], pr[i], p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(0x88FFFFFF);
            c.drawCircle(px[i], py[i], pr[i], p);
            float ts = pr[i] * 1.1f / Math.max(1f, LAB[i].length() * 0.6f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xDDFFFFFF);
            p.setTextSize(ts);
            c.drawText(LAB[i], px[i], py[i] + ts * 0.35f, p);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (editing) return editTouch(e);
        int act = e.getActionMasked(), ai = e.getActionIndex();
        int m = 0, pr2 = 0;
        boolean t1 = false, t2 = false, k = false;
        if (act != MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                if (act == MotionEvent.ACTION_UP || (act == MotionEvent.ACTION_POINTER_UP && i == ai)) continue;
                float x = e.getX(i), y = e.getY(i);
                float dx = x - dcx, dy = y - dcy;
                double d = Math.hypot(dx, dy);
                if (d < dr * 1.5) {
                    if (d > dr * 0.25) {
                        int s = (int) Math.round(Math.atan2(dy, dx) / (Math.PI / 4));
                        m |= DIRS[(s + 8) % 8];
                    }
                    double q = Math.min(d, dr);
                    k = true;
                    kx = d > 0 ? (float) (dcx + dx / d * q) : dcx;
                    ky = d > 0 ? (float) (dcy + dy / d * q) : dcy;
                    continue;
                }
                int best = -1;
                float bd = 1e9f;
                for (int j = 0; j < 15; j++) {
                    float dd = (float) Math.hypot(x - px[j], y - py[j]);
                    if (dd < pr[j] * 1.2f && dd < bd) { bd = dd; best = j; }
                }
                if (best < 0) continue;
                pr2 |= 1 << best;
                int cd = COD[best];
                if (cd > 0) m |= cd;
                else if (cd == TA) t1 = true;
                else if (cd == TB) t2 = true;
                else if ((act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN) && i == ai) {
                    if (cd == FF) ff = !ff;
                    else if (cd == ROM && onRom != null) onRom.run();
                }
            }
        }
        touch = m; ta = t1; tb = t2; knob = k; pressed = pr2;
        invalidate();
        return true;
    }
}
