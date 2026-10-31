package org.gbamax.emu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

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

    // itens do layout: 0..14 botões, 15 direcional analógico, 16 tela, 17 D-pad (cruzeta)
    static final int N = 18, DPAD = 15, SCREEN = 16, DPC = 17;
    // formato salvo: 17 itens x3 (formato antigo) | + altura da tela, itens ocultos, D-pad x3
    static final int OLD_LEN = 51, NEW_LEN = 56;

    volatile int touch;
    volatile boolean ta, tb, ff;
    Runnable onRom;
    GlScreen gl;

    // layout em frações da view: x/largura, y/altura, raio/min(largura,altura) (tela: largura/largura)
    final float[] ax = new float[N], ay = new float[N], ar = new float[N];
    // altura da tela em fração da altura da view (0 = proporção normal 3:2; >0 = esticada, ex.: tela cheia)
    float sfh;
    // bits dos itens ocultos (bit i = item i)
    int hidden;
    // posição da tela antes de ativar a tela cheia
    float psx, psy, psr;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    // imagens personalizadas dos botões (GBA-MAX/Personalizar/Botoes); null = desenho padrão
    final Bitmap[] skB = new Bitmap[15], skOn = new Bitmap[15];
    Bitmap skDpad, skAnalog, skKnob;
    final Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final RectF sr = new RectF();
    final Path path = new Path();
    final RectF scr = new RectF();
    final RectF tmp = new RectF();
    final float[] px = new float[15], py = new float[15], pr = new float[15];
    float dcx, dcy, dr, kx, ky;
    float ccx, ccy, cr;
    int crossM;
    boolean knob, land;
    int pressed, vw, vh;
    final float den;
    final int slop;

    // modo de edição
    volatile boolean editing;
    Runnable onEditDone;
    int sel = -1;
    float lx, ly, dx0, dy0;
    boolean moved, lpDone, pop;
    final RectF[] tbr = {new RectF(), new RectF(), new RectF(), new RectF(), new RectF()};
    final RectF[] pb = {new RectF(), new RectF()};
    static final String[] TBL = {"Menor", "Maior", "Padrão", "Ocultar", "Salvar"};

    final Runnable lpr = new Runnable() {
        @Override public void run() {
            if (!editing || lpDone || moved) return;
            lpDone = true;
            pop = true;
            layoutPopup(dx0, dy0);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            invalidate();
        }
    };

    public GameView(Context c) {
        super(c);
        den = c.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        p.setTextAlign(Paint.Align.CENTER);
        try { loadSkin(c); } catch (Throwable t) { }
    }

    /** Carrega as imagens personalizadas dos botões (se houver). */
    void loadSkin(Context c) {
        java.io.File d = Personalizar.botoes(c);
        for (int i = 0; i < 15; i++) {
            skB[i] = Personalizar.bmp(d, Personalizar.KEYS[i]);
            skOn[i] = Personalizar.bmp(d, Personalizar.KEYS[i] + "_on");
        }
        skDpad = Personalizar.bmp(d, Personalizar.DPAD);
        skAnalog = Personalizar.bmp(d, Personalizar.ANALOG);
        skKnob = Personalizar.bmp(d, Personalizar.KNOB);
        postInvalidate();
    }

    int mask(int frame) {
        int m = touch;
        boolean on = ((frame >> 1) & 1) == 0; // turbo ~15 toques/s
        if (ta && on) m |= A;
        if (tb && on) m |= B;
        return m;
    }

    boolean isHid(int i) { return ((hidden >> i) & 1) != 0; }

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
        // D-pad (cruzeta) começa oculto, no mesmo lugar do analógico
        ax[DPC] = ax[DPAD]; ay[DPC] = ay[DPAD]; ar[DPC] = ar[DPAD];
        sfh = 0;
        hidden = 1 << DPC;
    }

    boolean loadSaved() {
        String s = VideoSettings.getLayout(getContext(), land);
        if (s == null) return false;
        try {
            String[] t = s.split(",");
            if (t.length != OLD_LEN && t.length != NEW_LEN) return false;
            float[] a = new float[t.length];
            for (int i = 0; i < a.length; i++) a[i] = Float.parseFloat(t[i]);
            for (int i = 0; i <= SCREEN; i++) { ax[i] = a[i * 3]; ay[i] = a[i * 3 + 1]; ar[i] = a[i * 3 + 2]; }
            if (t.length == NEW_LEN) {
                sfh = Math.max(0f, Math.min(1f, a[51]));
                hidden = ((int) a[52]) & ~(1 << SCREEN);
                ax[DPC] = a[53]; ay[DPC] = a[54]; ar[DPC] = a[55];
            }
            return true;
        } catch (Exception e) { return false; }
    }

    void saveLayout() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= SCREEN; i++) {
            if (i > 0) sb.append(',');
            sb.append(ax[i]).append(',').append(ay[i]).append(',').append(ar[i]);
        }
        sb.append(',').append(sfh).append(',').append(hidden);
        sb.append(',').append(ax[DPC]).append(',').append(ay[DPC]).append(',').append(ar[DPC]);
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
        ccx = ax[DPC] * vw; ccy = ay[DPC] * vh; cr = ar[DPC] * mn;
        float sw = ar[SCREEN] * vw, sh = sfh > 0 ? sfh * vh : sw / 1.5f;
        float cx = ax[SCREEN] * vw, cy = ay[SCREEN] * vh;
        scr.set(cx - sw / 2, cy - sh / 2, cx + sw / 2, cy + sh / 2);
        if (gl != null) gl.setRect(scr.left, scr.top, scr.right, scr.bottom);
        float bw = Math.min(vw / 5.4f, 84 * den), bh = 40 * den, gap = 6 * den;
        float tot = bw * 5 + gap * 4, x0 = (vw - tot) / 2, y0 = 8 * den;
        for (int i = 0; i < 5; i++) tbr[i].set(x0 + i * (bw + gap), y0, x0 + i * (bw + gap) + bw, y0 + bh);
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
        pop = false;
        removeCallbacks(lpr);
        touch = 0; ta = false; tb = false; pressed = 0; knob = false; crossM = 0;
        invalidate();
    }

    public void cancelEdit() {
        editing = false;
        pop = false;
        removeCallbacks(lpr);
        reloadLayout();
        invalidate();
    }

    int pick(float x, float y) {
        for (int pass = 0; pass < 2; pass++) {
            boolean wantHidden = pass == 1;
            if (isHid(DPAD) == wantHidden && Math.hypot(x - dcx, y - dcy) < dr * 1.1) return DPAD;
            if (isHid(DPC) == wantHidden && Math.hypot(x - ccx, y - ccy) < cr * 1.1) return DPC;
            int best = -1;
            float bd = 1e9f;
            for (int j = 0; j < 15; j++) {
                if (isHid(j) != wantHidden) continue;
                float dd = (float) Math.hypot(x - px[j], y - py[j]);
                if (dd < pr[j] * 1.2f && dd < bd) { bd = dd; best = j; }
            }
            if (best >= 0) return best;
        }
        return scr.contains(x, y) ? SCREEN : -1;
    }

    /** Janelinha que aparece ao segurar a tela no editor. */
    void layoutPopup(float x, float y) {
        float w = Math.min(vw - 16 * den, 210 * den), h = 40 * den, gap = 6 * den;
        float left = Math.max(8 * den, Math.min(x - w / 2, vw - w - 8 * den));
        float top = Math.max(tbr[0].bottom + 30 * den, Math.min(y, vh - 2 * h - gap - 8 * den));
        pb[0].set(left, top, left + w, top + h);
        pb[1].set(left, top + h + gap, left + w, top + 2 * h + gap);
    }

    void toggleFullscreen() {
        if (sfh > 0) {
            float nx, ny, nr;
            if (psr > 0) { nx = psx; ny = psy; nr = psr; }
            else {
                float[] oa = ax.clone(), oy = ay.clone(), orr = ar.clone();
                float osf = sfh;
                int oh = hidden;
                defaults(vw, vh);
                nx = ax[SCREEN]; ny = ay[SCREEN]; nr = ar[SCREEN];
                System.arraycopy(oa, 0, ax, 0, N);
                System.arraycopy(oy, 0, ay, 0, N);
                System.arraycopy(orr, 0, ar, 0, N);
                sfh = osf;
                hidden = oh;
            }
            ax[SCREEN] = nx; ay[SCREEN] = ny; ar[SCREEN] = nr;
            sfh = 0;
        } else {
            psx = ax[SCREEN]; psy = ay[SCREEN]; psr = ar[SCREEN];
            ax[SCREEN] = .5f; ay[SCREEN] = .5f; ar[SCREEN] = 1f;
            sfh = 1f;
        }
        recalc();
    }

    /** Alterna entre o direcional analógico e o D-pad (cruzeta), mantendo posição e tamanho. */
    void swapDirectional() {
        boolean a = !isHid(DPAD), c = !isHid(DPC);
        if (a && !c) moveDir(DPAD, DPC);
        else if (!a && c) moveDir(DPC, DPAD);
        else if (a) { hidden |= 1 << DPAD; sel = DPC; }
        else { hidden &= ~(1 << DPAD); sel = DPAD; }
        recalc();
    }

    void moveDir(int from, int to) {
        ax[to] = ax[from]; ay[to] = ay[from]; ar[to] = ar[from];
        hidden |= 1 << from;
        hidden &= ~(1 << to);
        sel = to;
    }

    boolean editTouch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                removeCallbacks(lpr);
                lpDone = false;
                moved = false;
                if (pop) {
                    pop = false;
                    if (pb[0].contains(x, y)) toggleFullscreen();
                    else if (pb[1].contains(x, y)) swapDirectional();
                    invalidate();
                    return true;
                }
                for (int i = 0; i < 5; i++) if (tbr[i].contains(x, y)) { toolbar(i); return true; }
                sel = pick(x, y);
                lx = x; ly = y; dx0 = x; dy0 = y;
                if (sel < 0 || sel == SCREEN) postDelayed(lpr, ViewConfiguration.getLongPressTimeout());
                break;
            case MotionEvent.ACTION_MOVE:
                if (lpDone) break;
                if (!moved) {
                    if (Math.hypot(x - dx0, y - dy0) < slop) break;
                    moved = true;
                    removeCallbacks(lpr);
                }
                if (sel >= 0) {
                    ax[sel] = Math.max(0f, Math.min(1f, ax[sel] + (x - lx) / vw));
                    ay[sel] = Math.max(0f, Math.min(1f, ay[sel] + (y - ly) / vh));
                    lx = x; ly = y;
                    recalc();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(lpr);
                break;
        }
        invalidate();
        return true;
    }

    void toolbar(int i) {
        if (i == 4) {
            saveLayout();
            editing = false;
            pop = false;
            if (onEditDone != null) onEditDone.run();
            return;
        }
        if (i == 3) {
            if (sel >= 0 && sel != SCREEN) { hidden ^= 1 << sel; invalidate(); }
            return;
        }
        if (i == 2) {
            if (sel < 0) {
                defaults(vw, vh);
                VideoSettings.setLayout(getContext(), land, null);
            } else {
                float[] oa = ax.clone(), oy = ay.clone(), orr = ar.clone();
                float osf = sfh;
                int oh = hidden;
                defaults(vw, vh);
                float dx = ax[sel], dy = ay[sel], dRad = ar[sel];
                boolean dh = isHid(sel);
                System.arraycopy(oa, 0, ax, 0, N);
                System.arraycopy(oy, 0, ay, 0, N);
                System.arraycopy(orr, 0, ar, 0, N);
                ax[sel] = dx; ay[sel] = dy; ar[sel] = dRad;
                sfh = sel == SCREEN ? 0 : osf;
                hidden = dh ? (oh | (1 << sel)) : (oh & ~(1 << sel));
            }
            recalc();
            invalidate();
            return;
        }
        if (sel < 0) return;
        float f = i == 0 ? 0.92f : 1.08f;
        boolean dir = sel == DPAD || sel == DPC;
        float lo = sel == SCREEN ? .3f : dir ? .06f : .025f;
        float hi = sel == SCREEN ? 1f : dir ? .4f : .25f;
        if (sel == SCREEN) sfh = 0; // ajuste manual volta para a proporção normal
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
        c.drawText(sfh > 0 ? "TELA CHEIA" : "TELA", scr.centerX(), scr.centerY(), p);
        if (sel == DPAD || sel == DPC) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4 * den);
            p.setColor(0xFFFFD54F);
            if (sel == DPAD) c.drawCircle(dcx, dcy, dr + 4 * den, p);
            else c.drawCircle(ccx, ccy, cr + 4 * den, p);
        } else if (sel >= 0 && sel < 15) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4 * den);
            p.setColor(0xFFFFD54F);
            c.drawCircle(px[sel], py[sel], pr[sel] + 4 * den, p);
        }
        p.setTextSize(13 * den);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xEEFFFFFF);
        String hint = sel < 0 ? "Toque e arraste · Segure a tela: mais opções"
                : sel != SCREEN && isHid(sel) ? "Oculto (aparece só aqui) · toque em Mostrar"
                : "Arraste ou use Menor/Maior";
        c.drawText(hint, vw / 2f, tbr[0].bottom + 22 * den, p);
        for (int i = 0; i < 5; i++) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(i == 4 ? 0xFF2EAD4B : 0xFF1E6BE6);
            c.drawRoundRect(tbr[i], 12 * den, 12 * den, p);
            p.setColor(Color.WHITE);
            p.setTextSize(12.5f * den);
            String lb = TBL[i];
            if (i == 3 && sel >= 0 && sel != SCREEN && isHid(sel)) lb = "Mostrar";
            c.drawText(lb, tbr[i].centerX(), tbr[i].centerY() + 5 * den, p);
        }
        if (pop) {
            String[] pl = {sfh > 0 ? "Sair da tela cheia" : "Tela cheia", "Trocar D-pad / Analógico"};
            for (int i = 0; i < 2; i++) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xF0222833);
                c.drawRoundRect(pb[i], 12 * den, 12 * den, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2 * den);
                p.setColor(0xFFFFD54F);
                c.drawRoundRect(pb[i], 12 * den, 12 * den, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.WHITE);
                p.setTextSize(13.5f * den);
                c.drawText(pl[i], pb[i].centerX(), pb[i].centerY() + 5 * den, p);
            }
        }
    }

    void drawCross(Canvas c, boolean h) {
        if (skDpad != null) {
            bp.setAlpha(h ? 90 : 255);
            sr.set(ccx - cr, ccy - cr, ccx + cr, ccy + cr);
            c.drawBitmap(skDpad, null, sr, bp);
            return;
        }
        float w = cr * 0.33f, L = cr;
        path.reset();
        path.moveTo(ccx - w, ccy - L);
        path.lineTo(ccx + w, ccy - L);
        path.lineTo(ccx + w, ccy - w);
        path.lineTo(ccx + L, ccy - w);
        path.lineTo(ccx + L, ccy + w);
        path.lineTo(ccx + w, ccy + w);
        path.lineTo(ccx + w, ccy + L);
        path.lineTo(ccx - w, ccy + L);
        path.lineTo(ccx - w, ccy + w);
        path.lineTo(ccx - L, ccy + w);
        path.lineTo(ccx - L, ccy - w);
        path.lineTo(ccx - w, ccy - w);
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(h ? 0x22FFFFFF : 0x33FFFFFF);
        c.drawPath(path, p);
        if (!h) {
            p.setColor(0x88FFFFFF);
            if ((crossM & UP) != 0) { tmp.set(ccx - w, ccy - L, ccx + w, ccy - w); c.drawRect(tmp, p); }
            if ((crossM & DN) != 0) { tmp.set(ccx - w, ccy + w, ccx + w, ccy + L); c.drawRect(tmp, p); }
            if ((crossM & LF) != 0) { tmp.set(ccx - L, ccy - w, ccx - w, ccy + w); c.drawRect(tmp, p); }
            if ((crossM & RT) != 0) { tmp.set(ccx + w, ccy - w, ccx + L, ccy + w); c.drawRect(tmp, p); }
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        p.setColor(h ? 0x44FFFFFF : 0x88FFFFFF);
        c.drawPath(path, p);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (editing) drawEdit(c);
        boolean ed = editing;
        int al = ed ? 255 : Math.max(10, Math.min(100, VideoSettings.ctlAlpha)) * 255 / 100;
        boolean lay = al < 255;
        if (lay) c.saveLayerAlpha(0, 0, getWidth(), getHeight(), al);
        p.setStrokeWidth(3);
        if (!isHid(DPAD) || ed) {
            boolean h = isHid(DPAD);
            float kcx = !h && knob ? kx : dcx, kcy = !h && knob ? ky : dcy;
            if (skAnalog != null) {
                bp.setAlpha(h ? 90 : 255);
                sr.set(dcx - dr, dcy - dr, dcx + dr, dcy + dr);
                c.drawBitmap(skAnalog, null, sr, bp);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setColor(h ? 0x44FFFFFF : 0x88FFFFFF);
                c.drawCircle(dcx, dcy, dr, p);
            }
            if (skKnob != null) {
                bp.setAlpha(h ? 90 : 255);
                float kr = dr * 0.4f;
                sr.set(kcx - kr, kcy - kr, kcx + kr, kcy + kr);
                c.drawBitmap(skKnob, null, sr, bp);
            } else {
                p.setStyle(Paint.Style.FILL);
                p.setColor(h ? 0x22FFFFFF : knob ? 0x88FFFFFF : 0x33FFFFFF);
                c.drawCircle(kcx, kcy, dr * 0.4f, p);
            }
        }
        if (!isHid(DPC) || ed) drawCross(c, isHid(DPC));
        for (int i = 0; i < 15; i++) {
            boolean h = isHid(i);
            if (h && !ed) continue;
            boolean on = !h && (((pressed >> i) & 1) != 0 || (COD[i] == FF && ff));
            Bitmap sk = on && skOn[i] != null ? skOn[i] : skB[i];
            if (sk != null) {
                float rr = on && skOn[i] == null ? pr[i] * 0.92f : pr[i];
                bp.setAlpha(h ? 90 : 255);
                sr.set(px[i] - rr, py[i] - rr, px[i] + rr, py[i] + rr);
                c.drawBitmap(sk, null, sr, bp);
                continue;
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(h ? 0x22FFFFFF : on ? 0x88FFFFFF : 0x33FFFFFF);
            c.drawCircle(px[i], py[i], pr[i], p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(h ? 0x44FFFFFF : 0x88FFFFFF);
            c.drawCircle(px[i], py[i], pr[i], p);
            float ts = pr[i] * 1.1f / Math.max(1f, LAB[i].length() * 0.6f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(h ? 0x55FFFFFF : 0xDDFFFFFF);
            p.setTextSize(ts);
            c.drawText(LAB[i], px[i], py[i] + ts * 0.35f, p);
        }
        if (lay) c.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (editing) return editTouch(e);
        int act = e.getActionMasked(), ai = e.getActionIndex();
        int m = 0, pr2 = 0, cm = 0;
        boolean t1 = false, t2 = false, k = false;
        if (act != MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                if (act == MotionEvent.ACTION_UP || (act == MotionEvent.ACTION_POINTER_UP && i == ai)) continue;
                float x = e.getX(i), y = e.getY(i);
                if (!isHid(DPAD)) {
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
                }
                if (!isHid(DPC)) {
                    float cdx = x - ccx, cdy = y - ccy;
                    if (Math.hypot(cdx, cdy) < cr * 1.4) {
                        float th = cr * 0.3f;
                        int mm = 0;
                        if (cdx > th) mm |= RT; else if (cdx < -th) mm |= LF;
                        if (cdy > th) mm |= DN; else if (cdy < -th) mm |= UP;
                        m |= mm;
                        cm |= mm;
                        continue;
                    }
                }
                int best = -1;
                float bd = 1e9f;
                for (int j = 0; j < 15; j++) {
                    if (isHid(j)) continue;
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
        if (VideoSettings.vibrate && ((m & ~touch) != 0 || (pr2 & ~pressed) != 0))
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        touch = m; ta = t1; tb = t2; knob = k; pressed = pr2; crossM = cm;
        invalidate();
        return true;
    }
}
