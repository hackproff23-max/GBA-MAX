package org.gbamax.emu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Shader;
import android.os.Build;
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

    // brilho animado ao apertar: 0 = apagado, 1 = aceso (um valor por botão e um para o D-pad)
    final float[] glow = new float[15];
    float cgl, cgx, cgy;
    Bitmap halo, halo2;
    // brilho branco da cruzeta com skin: só pinta em cima dos pixels da imagem (SRC_ATOP)
    final Paint gp = mkGp();
    final LightingColorFilter[] lf = new LightingColorFilter[17];

    // modo de edição
    volatile boolean editing;
    Runnable onEditDone;
    int sel = -1;
    float lx, ly, dx0, dy0;
    boolean moved, lpDone, pop;
    final RectF[] tbr = {new RectF(), new RectF(), new RectF(), new RectF(), new RectF(), new RectF()};
    final RectF[] pb = {new RectF(), new RectF()};
    static final String[] TBL = {"Menor", "Maior", "Padrão", "Ocultar", "Todos", "Salvar"};

    // janela "Todos os botões" do editor: 0 fechada, 1 menu, 2 tamanho, 3 opacidade, 4 formato
    int panel;
    boolean moveAll;
    final RectF[] pnl = {new RectF(), new RectF(), new RectF(), new RectF()};
    final RectF[] gr = new RectF[11];
    final RectF okR = new RectF();
    float pTitleY;
    final Path spath = new Path();
    // formato de cada botão (índice em SHAPES), guardado em SharedPreferences ("btnShapes": 15 números)
    final int[] shp = new int[15];
    static final String[] SHAPES = {"Círculo", "Quadrado", "Retângulo", "Hexágono", "Octógono", "Pentágono",
            "Losango", "Triângulo", "Estrela", "Coração", "Cruz"};

    void loadShapes(Context c) {
        try {
            String s = c.getSharedPreferences("gbamax_video", 0).getString("btnShapes", null);
            if (s == null) return;
            String[] t = s.split(",");
            for (int i = 0; i < shp.length && i < t.length; i++)
                shp[i] = Math.max(0, Math.min(SHAPES.length - 1, Integer.parseInt(t[i].trim())));
        } catch (Throwable t) { }
    }

    void saveShapes() {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < shp.length; i++) { if (i > 0) sb.append(','); sb.append(shp[i]); }
            getContext().getSharedPreferences("gbamax_video", 0).edit().putString("btnShapes", sb.toString()).apply();
        } catch (Throwable t) { }
    }

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
        for (int i = 0; i < gr.length; i++) gr[i] = new RectF();
        loadShapes(c);
        try { loadSkin(c); } catch (Throwable t) { }
    }

    /** Skin padrão do app (assets/botoes), usada quando não há imagem personalizada. */
    static Bitmap assetSkin(Context c, String key) {
        try (java.io.InputStream in = c.getAssets().open("botoes/" + key + ".png")) {
            return android.graphics.BitmapFactory.decodeStream(in);
        } catch (Throwable t) { return null; }
    }

    /** Imagem personalizada, ou a skin padrão se não houver. */
    static Bitmap skin(Context c, java.io.File d, String key) {
        Bitmap b = Personalizar.bmp(d, key);
        return b != null ? b : assetSkin(c, key);
    }

    /** Carrega as imagens dos botões: personalizadas (se houver) ou a skin padrão. */
    void loadSkin(Context c) {
        java.io.File d = Personalizar.botoes(c);
        for (int i = 0; i < 15; i++) {
            skB[i] = skin(c, d, Personalizar.KEYS[i]);
            skOn[i] = Personalizar.bmp(d, Personalizar.KEYS[i] + "_on");
        }
        skDpad = skin(c, d, Personalizar.DPAD);
        skAnalog = skin(c, d, Personalizar.ANALOG);
        skKnob = skin(c, d, Personalizar.KNOB);
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

    // ================= brilho ao apertar =================
    /** Bolinha de luz (branco quente que some nas bordas), criada uma vez só. */
    Bitmap halo() {
        if (halo == null) {
            int s = 128;
            halo = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas hc = new Canvas(halo);
            Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
            hp.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f,
                    new int[]{0xFFFFFFFF, 0xAAFFF0B8, 0x00FFD36B}, new float[]{0.3f, 0.62f, 1f}, Shader.TileMode.CLAMP));
            hc.drawCircle(s / 2f, s / 2f, s / 2f, hp);
        }
        return halo;
    }

    static Paint mkGp() {
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        q.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP));
        return q;
    }

    /** Mancha de luz branca que some nas bordas, criada uma vez só. */
    Bitmap halo2() {
        if (halo2 == null) {
            int s = 128;
            halo2 = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas hc = new Canvas(halo2);
            Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
            hp.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f,
                    new int[]{0xFFFFFFFF, 0xDDFFFFFF, 0x00FFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            hc.drawCircle(s / 2f, s / 2f, s / 2f, hp);
        }
        return halo2;
    }

    /** Luz em volta do ponto (x, y); g = intensidade 0..1. */
    void drawHalo(Canvas c, float x, float y, float r, float g) {
        float R = r * 1.7f;
        bp.setColorFilter(null);
        bp.setAlpha((int) (Math.max(0f, Math.min(1f, g)) * 235));
        sr.set(x - R, y - R, x + R, y + R);
        c.drawBitmap(halo(), null, sr, bp);
    }

    /** Clareia a imagem do botão conforme o brilho (16 níveis, filtros reaproveitados). */
    LightingColorFilter lighten(float g) {
        int k = Math.max(0, Math.min(16, Math.round(g * 16)));
        if (lf[k] == null) lf[k] = new LightingColorFilter(0xFFFFFFFF, (k * 2) * 0x010101);
        return lf[k];
    }

    /** Aproxima v do alvo: sobe rápido ao apertar e apaga devagar ao soltar. Devolve true se ainda anima. */
    static boolean ease(float[] v, int i, boolean on) {
        float tg = on ? 1f : 0f;
        v[i] += (tg - v[i]) * (on ? 0.5f : 0.16f);
        if (Math.abs(tg - v[i]) < 0.02f) { v[i] = tg; return false; }
        return true;
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
        int nt = tbr.length;
        float bw = Math.min(vw / (nt + 0.6f), 84 * den), bh = 40 * den, gap = 5 * den;
        float tot = bw * nt + gap * (nt - 1), x0 = (vw - tot) / 2, y0 = 8 * den;
        for (int i = 0; i < nt; i++) tbr[i].set(x0 + i * (bw + gap), y0, x0 + i * (bw + gap) + bw, y0 + bh);
        layoutPanel();
    }

    // ================= janela "Todos os botões" =================
    void layoutPanel() {
        float w = Math.min(vw - 32 * den, 240 * den), h = 44 * den, gap = 6 * den;
        float left = (vw - w) / 2f;
        if (panel == 4) {
            float cell = Math.min((vw - 48 * den) / 4f, 64 * den);
            float gw = cell * 4 + gap * 3;
            float gh = cell * 3 + gap * 3 + h;
            float top = Math.max(tbr[0].bottom + 56 * den, (vh - gh) / 2f);
            float l0 = (vw - gw) / 2f;
            for (int i = 0; i < gr.length; i++) {
                int rw = i / 4, cl = i % 4;
                float x = l0 + cl * (cell + gap), y = top + rw * (cell + gap);
                gr[i].set(x, y, x + cell, y + cell);
            }
            float oy = top + 3 * (cell + gap);
            okR.set(l0, oy, l0 + gw, oy + h);
            pTitleY = top - 14 * den;
            return;
        }
        int rows = panel == 1 ? 4 : 3;
        float total = rows * h + (rows - 1) * gap;
        float top = Math.max(tbr[0].bottom + 56 * den, (vh - total) / 2f);
        for (int i = 0; i < rows; i++) pnl[i].set(left, top + i * (h + gap), left + w, top + i * (h + gap) + h);
        pTitleY = top - 14 * den;
    }

    void panelTouch(float x, float y) {
        if (panel == 4) {
            for (int i = 0; i < gr.length; i++) {
                if (gr[i].contains(x, y)) {
                    if (sel >= 0 && sel < 15) { shp[sel] = i; saveShapes(); }
                    return;
                }
            }
            panel = 0;
            return;
        }
        int hit = -1;
        int rows = panel == 1 ? 4 : 3;
        for (int i = 0; i < rows; i++) if (pnl[i].contains(x, y)) hit = i;
        if (hit < 0) { panel = 0; return; }
        if (panel == 1) {
            if (hit == 0) { moveAll = true; panel = 0; }
            else if (hit == 1) { panel = 2; layoutPanel(); }
            else if (hit == 2) { panel = 3; layoutPanel(); }
            else if (sel >= 0 && sel < 15) { panel = 4; layoutPanel(); }
            else {
                panel = 0;
                android.widget.Toast.makeText(getContext(), "Toque num botão antes para escolher o formato dele", android.widget.Toast.LENGTH_SHORT).show();
            }
        } else if (panel == 2) {
            if (hit == 0) scaleAll(0.92f);
            else if (hit == 1) scaleAll(1.08f);
            else panel = 0;
        } else {
            if (hit == 0) stepAlpha(-10);
            else if (hit == 1) stepAlpha(10);
            else panel = 0;
        }
    }

    /** Muda o tamanho de todos os botões e das direções (a tela do jogo não muda). */
    void scaleAll(float f) {
        for (int i = 0; i < N; i++) {
            if (i == SCREEN) continue;
            boolean dir = i == DPAD || i == DPC;
            float lo = dir ? .06f : .025f, hi = dir ? .4f : .25f;
            ar[i] = Math.max(lo, Math.min(hi, ar[i] * f));
        }
        recalc();
    }

    /** Move todos os botões juntos, sem deixar nenhum sair da tela. */
    void shiftAll(float dx, float dy) {
        float mnx = 1, mxx = 0, mny = 1, mxy = 0;
        for (int i = 0; i < N; i++) {
            if (i == SCREEN) continue;
            mnx = Math.min(mnx, ax[i]); mxx = Math.max(mxx, ax[i]);
            mny = Math.min(mny, ay[i]); mxy = Math.max(mxy, ay[i]);
        }
        dx = Math.max(-mnx, Math.min(1 - mxx, dx));
        dy = Math.max(-mny, Math.min(1 - mxy, dy));
        for (int i = 0; i < N; i++) {
            if (i == SCREEN) continue;
            ax[i] += dx; ay[i] += dy;
        }
    }

    void stepAlpha(int d) {
        VideoSettings.ctlAlpha = Math.max(10, Math.min(100, VideoSettings.ctlAlpha + d));
        VideoSettings.save(getContext());
    }

    // ================= formatos =================
    void poly(Path q, float cx, float cy, float r, int n, double rot) {
        for (int k = 0; k < n; k++) {
            double a = rot + 2 * Math.PI * k / n;
            float x = cx + (float) (r * Math.cos(a)), y = cy + (float) (r * Math.sin(a));
            if (k == 0) q.moveTo(x, y); else q.lineTo(x, y);
        }
        q.close();
    }

    /** Monta o contorno do formato st centrado em (cx, cy) com raio r. */
    Path buildShape(float cx, float cy, float r, int st) {
        Path q = spath;
        q.reset();
        switch (st) {
            case 1:
                q.addRoundRect(cx - r * .9f, cy - r * .9f, cx + r * .9f, cy + r * .9f, r * .3f, r * .3f, Path.Direction.CW);
                break;
            case 2:
                q.addRoundRect(cx - r * 1.3f, cy - r * .8f, cx + r * 1.3f, cy + r * .8f, r * .2f, r * .2f, Path.Direction.CW);
                break;
            case 3: poly(q, cx, cy, r, 6, 0); break;
            case 4: poly(q, cx, cy, r, 8, Math.PI / 8); break;
            case 5: poly(q, cx, cy, r, 5, -Math.PI / 2); break;
            case 6: poly(q, cx, cy, r * 1.1f, 4, -Math.PI / 2); break;
            case 7: poly(q, cx, cy + r * .15f, r * 1.1f, 3, -Math.PI / 2); break;
            case 8:
                for (int k = 0; k < 10; k++) {
                    float rr = k % 2 == 0 ? r * 1.15f : r * .5f;
                    double a = -Math.PI / 2 + k * Math.PI / 5;
                    float x = cx + (float) (rr * Math.cos(a)), y = cy + (float) (rr * Math.sin(a));
                    if (k == 0) q.moveTo(x, y); else q.lineTo(x, y);
                }
                q.close();
                break;
            case 9:
                q.moveTo(cx, cy + r * .95f);
                q.cubicTo(cx + r * 1.35f, cy + r * .15f, cx + r * .95f, cy - r * 1.05f, cx, cy - r * .4f);
                q.cubicTo(cx - r * .95f, cy - r * 1.05f, cx - r * 1.35f, cy + r * .15f, cx, cy + r * .95f);
                q.close();
                break;
            case 10: {
                float w = r * .38f, L = r * 1.05f;
                q.moveTo(cx - w, cy - L); q.lineTo(cx + w, cy - L); q.lineTo(cx + w, cy - w);
                q.lineTo(cx + L, cy - w); q.lineTo(cx + L, cy + w); q.lineTo(cx + w, cy + w);
                q.lineTo(cx + w, cy + L); q.lineTo(cx - w, cy + L); q.lineTo(cx - w, cy + w);
                q.lineTo(cx - L, cy + w); q.lineTo(cx - L, cy - w); q.lineTo(cx - w, cy - w);
                q.close();
                break;
            }
            default:
                q.addCircle(cx, cy, r, Path.Direction.CW);
        }
        return q;
    }

    void drawRow(Canvas c, RectF r, String t, boolean on) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xF0222833);
        c.drawRoundRect(r, 12 * den, 12 * den, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * den);
        p.setColor(on ? 0xFFFFD54F : 0x88FFFFFF);
        c.drawRoundRect(r, 12 * den, 12 * den, p);
        if (t != null) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            p.setTextSize(13.5f * den);
            c.drawText(t, r.centerX(), r.centerY() + 5 * den, p);
        }
    }

    void drawPanel(Canvas c) {
        c.drawColor(0x66000000);
        String title;
        String[] tx = null;
        if (panel == 1) {
            title = "Todos os botões";
            tx = new String[]{"Mover todos", "Tamanho de todos", "Opacidade de todos", "Formato do botão selecionado"};
        } else if (panel == 2) {
            title = "Tamanho de todos";
            tx = new String[]{"−  Menor", "+  Maior", "OK"};
        } else if (panel == 3) {
            title = "Opacidade: " + Math.max(10, Math.min(100, VideoSettings.ctlAlpha)) + "%";
            tx = new String[]{"−  Mais transparente", "+  Mais opaco", "OK"};
        } else {
            int cur = sel >= 0 && sel < 15 ? shp[sel] : 0;
            title = "Botão " + (sel >= 0 && sel < 15 ? LAB[sel] : "") + ": " + SHAPES[cur];
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        p.setTextSize(16 * den);
        p.setFakeBoldText(true);
        c.drawText(title, vw / 2f, pTitleY, p);
        p.setFakeBoldText(false);
        if (panel == 4) {
            int cur = sel >= 0 && sel < 15 ? shp[sel] : 0;
            for (int i = 0; i < gr.length; i++) {
                drawRow(c, gr[i], null, i == cur);
                Path sp = buildShape(gr[i].centerX(), gr[i].centerY(), gr[i].width() * .3f, i);
                p.setStyle(Paint.Style.FILL);
                p.setColor(i == cur ? 0xFFFFD54F : 0xCCFFFFFF);
                c.drawPath(sp, p);
            }
            drawRow(c, okR, "OK", false);
            return;
        }
        int rows = panel == 1 ? 4 : 3;
        for (int i = 0; i < rows; i++) drawRow(c, pnl[i], tx[i], false);
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
        panel = 0;
        moveAll = false;
        removeCallbacks(lpr);
        touch = 0; ta = false; tb = false; pressed = 0; knob = false; crossM = 0;
        invalidate();
    }

    public void cancelEdit() {
        editing = false;
        pop = false;
        panel = 0;
        moveAll = false;
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
                if (panel != 0) {
                    lpDone = true; // o resto do gesto não move nada
                    panelTouch(x, y);
                    invalidate();
                    return true;
                }
                if (pop) {
                    pop = false;
                    if (pb[0].contains(x, y)) toggleFullscreen();
                    else if (pb[1].contains(x, y)) swapDirectional();
                    invalidate();
                    return true;
                }
                for (int i = 0; i < tbr.length; i++) if (tbr[i].contains(x, y)) { toolbar(i); return true; }
                if (moveAll) { sel = -1; lx = x; ly = y; dx0 = x; dy0 = y; break; }
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
                if (moveAll) {
                    shiftAll((x - lx) / vw, (y - ly) / vh);
                    lx = x; ly = y;
                    recalc();
                    break;
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
        if (i == 5) {
            saveLayout();
            editing = false;
            pop = false;
            panel = 0;
            moveAll = false;
            if (onEditDone != null) onEditDone.run();
            return;
        }
        if (i == 4) {
            if (moveAll) moveAll = false;
            else { pop = false; panel = 1; layoutPanel(); }
            invalidate();
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
        String hint = moveAll ? "Arraste para mover todos os botões · toque em Parar"
                : sel < 0 ? "Toque e arraste · Segure a tela ou use Todos"
                : sel != SCREEN && isHid(sel) ? "Oculto (aparece só aqui) · toque em Mostrar"
                : "Arraste ou use Menor/Maior";
        c.drawText(hint, vw / 2f, tbr[0].bottom + 22 * den, p);
        for (int i = 0; i < tbr.length; i++) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(i == 5 ? 0xFF2EAD4B : i == 4 ? (moveAll ? 0xFFFFA000 : 0xFF7E57C2) : 0xFF1E6BE6);
            c.drawRoundRect(tbr[i], 12 * den, 12 * den, p);
            p.setColor(Color.WHITE);
            p.setTextSize(11.5f * den);
            String lb = TBL[i];
            if (i == 3 && sel >= 0 && sel != SCREEN && isHid(sel)) lb = "Mostrar";
            if (i == 4 && moveAll) lb = "Parar";
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
            bp.setColorFilter(null);
            bp.setAlpha(h ? 90 : 255);
            sr.set(ccx - cr, ccy - cr, ccx + cr, ccy + cr);
            if (!h && !editing && cgl > 0f && (cgx != 0 || cgy != 0)) {
                // cada tecla acende sozinha; os cantos só acendem com duas direções juntas
                int sv = c.saveLayer(sr, null);
                c.drawBitmap(skDpad, null, sr, bp);
                boolean diag = cgx != 0 && cgy != 0;
                float o = cr * (diag ? 0.52f : 0.6f), R = cr * (diag ? 0.5f : 0.66f);
                float gx = ccx + cgx * o, gy = ccy + cgy * o;
                gp.setAlpha((int) (Math.min(1f, cgl) * 245));
                tmp.set(gx - R, gy - R, gx + R, gy + R);
                c.drawBitmap(halo2(), null, tmp, gp);
                c.restoreToCount(sv);
            } else {
                c.drawBitmap(skDpad, null, sr, bp);
            }
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

    // zonas apertadas da cruzeta (para o destaque só na direção tocada)
    final Path zp = new Path();

    /** Abre uma camada com opacidade pct (0..100) em volta de (x, y); devolve o ponto para restoreToCount. */
    int layerAt(Canvas c, float x, float y, float r, int pct) {
        float m = r * 1.6f + 8 * den;
        return c.saveLayerAlpha(x - m, y - m, x + m, y + m, Math.max(0, Math.min(100, pct)) * 255 / 100);
    }

    /** Cruzeta em base%; só a(s) direção(ões) apertada(s) ficam 10% mais opacas (70% -> 80%). */
    void drawCrossLayered(Canvas c, boolean h, int base, boolean ed) {
        if (skDpad != null) {
            int sv0 = layerAt(c, ccx, ccy, cr, base);
            drawCross(c, h);
            c.restoreToCount(sv0);
            return;
        }
        int cm = crossM;
        if (h || ed || cm == 0) {
            int sv = layerAt(c, ccx, ccy, cr, base);
            drawCross(c, h);
            c.restoreToCount(sv);
            return;
        }
        float th = cr * 0.3f, e = cr * 1.5f;
        zp.reset();
        if ((cm & UP) != 0) zp.addRect(ccx - e, ccy - e, ccx + e, ccy - th, Path.Direction.CW);
        if ((cm & DN) != 0) zp.addRect(ccx - e, ccy + th, ccx + e, ccy + e, Path.Direction.CW);
        if ((cm & LF) != 0) zp.addRect(ccx - e, ccy - e, ccx - th, ccy + e, Path.Direction.CW);
        if ((cm & RT) != 0) zp.addRect(ccx + th, ccy - e, ccx + e, ccy + e, Path.Direction.CW);
        // resto da cruzeta na opacidade padrão
        int sv = layerAt(c, ccx, ccy, cr, base);
        if (Build.VERSION.SDK_INT >= 26) c.clipOutPath(zp); else c.clipPath(zp, Region.Op.DIFFERENCE);
        drawCross(c, false);
        c.restoreToCount(sv);
        // lado apertado, 10% mais opaco
        sv = layerAt(c, ccx, ccy, cr, base + 10);
        c.clipPath(zp);
        drawCross(c, false);
        c.restoreToCount(sv);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (editing) drawEdit(c);
        boolean ed = editing;
        boolean anim = false; // ainda tem brilho acendendo/apagando?
        // opacidade padrão (70%); no editor tem piso de 35% para os botões continuarem visíveis
        // ao apertar, cada botão sobe 10% (80%) em vez de a camada inteira ter uma opacidade só
        int base = Math.max(ed ? 35 : 10, Math.min(100, VideoSettings.ctlAlpha));
        p.setStrokeWidth(3);
        if (!isHid(DPAD) || ed) {
            boolean h = isHid(DPAD);
            int sv = layerAt(c, dcx, dcy, dr, base + (!h && knob ? 10 : 0));
            float kcx = !h && knob ? kx : dcx, kcy = !h && knob ? ky : dcy;
            if (skAnalog != null) {
                bp.setColorFilter(null);
                bp.setAlpha(h ? 90 : 255);
                sr.set(dcx - dr, dcy - dr, dcx + dr, dcy + dr);
                c.drawBitmap(skAnalog, null, sr, bp);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setColor(h ? 0x44FFFFFF : 0x88FFFFFF);
                c.drawCircle(dcx, dcy, dr, p);
            }
            if (skKnob != null) {
                bp.setColorFilter(null);
                bp.setAlpha(h ? 90 : 255);
                float kr = dr * 0.4f;
                sr.set(kcx - kr, kcy - kr, kcx + kr, kcy + kr);
                c.drawBitmap(skKnob, null, sr, bp);
            } else {
                p.setStyle(Paint.Style.FILL);
                p.setColor(h ? 0x22FFFFFF : knob ? 0x88FFFFFF : 0x33FFFFFF);
                c.drawCircle(kcx, kcy, dr * 0.4f, p);
            }
            c.restoreToCount(sv);
        }
        if (!isHid(DPC) || ed) {
            // brilho da cruzeta: segue o lado que está apertado e apaga devagar
            int cm = crossM;
            if (cm != 0) {
                cgx = ((cm & RT) != 0 ? 1 : 0) - ((cm & LF) != 0 ? 1 : 0);
                cgy = ((cm & DN) != 0 ? 1 : 0) - ((cm & UP) != 0 ? 1 : 0);
            }
            float[] cg = {cgl};
            if (ease(cg, 0, cm != 0)) anim = true;
            cgl = cg[0];
            drawCrossLayered(c, isHid(DPC), base, ed);
        }
        for (int i = 0; i < 15; i++) {
            boolean h = isHid(i);
            if (h && !ed) continue;
            boolean on = !h && (((pressed >> i) & 1) != 0 || (COD[i] == FF && ff));
            // brilho animado: acende rápido ao apertar e apaga devagar ao soltar
            if (ease(glow, i, on)) anim = true;
            float lum = ed || h ? 0f : glow[i];
            // 70% parado -> 80% apertado (sobe junto com o brilho)
            int sv = layerAt(c, px[i], py[i], pr[i], base + Math.round(10 * lum));
            Bitmap sk = on && skOn[i] != null ? skOn[i] : skB[i];
            if (sk != null) {
                float rr = pr[i];
                bp.setAlpha(h ? 90 : 255);
                bp.setColorFilter(lum > 0f ? lighten(lum) : null);
                sr.set(px[i] - rr, py[i] - rr, px[i] + rr, py[i] + rr);
                c.drawBitmap(sk, null, sr, bp);
                bp.setColorFilter(null);
                c.restoreToCount(sv);
                continue;
            }
            Path sp = buildShape(px[i], py[i], pr[i], shp[i]);
            p.setStyle(Paint.Style.FILL);
            p.setColor(h ? 0x22FFFFFF : on ? 0x88FFFFFF : 0x33FFFFFF);
            c.drawPath(sp, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(h ? 0x44FFFFFF : 0x88FFFFFF);
            c.drawPath(sp, p);
            float ts = pr[i] * 1.1f / Math.max(1f, LAB[i].length() * 0.6f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(h ? 0x55FFFFFF : 0xDDFFFFFF);
            p.setTextSize(ts);
            c.drawText(LAB[i], px[i], py[i] + ts * 0.35f, p);
            c.restoreToCount(sv);
        }
        if (ed && panel != 0) drawPanel(c);
        if (anim && !ed) postInvalidateOnAnimation();
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
