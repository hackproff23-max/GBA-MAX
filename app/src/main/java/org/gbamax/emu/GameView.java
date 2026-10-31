package org.gbamax.emu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

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

    volatile int touch;
    volatile boolean ta, tb, ff;
    Runnable onRom;

    final Bitmap bmp = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888);
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF scr = new RectF();
    final float[] px = new float[15], py = new float[15], pr = new float[15];
    float dcx, dcy, dr, kx, ky;
    boolean knob;
    int pressed;

    public GameView(Context c) {
        super(c);
        p.setTextAlign(Paint.Align.CENTER);
    }

    int mask(int frame) {
        int m = touch;
        boolean on = ((frame >> 1) & 1) == 0; // turbo ~15 toques/s
        if (ta && on) m |= A;
        if (tb && on) m |= B;
        return m;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (h > w) {
            float sh = w * 2f / 3, ch = h - sh;
            scr.set(0, 0, w, sh);
            for (int i = 0; i < 15; i++) {
                px[i] = P[i][0] * w / 100; py[i] = sh + P[i][1] * ch / 100; pr[i] = P[i][2] * w / 100;
            }
            dcx = PD[0] * w / 100; dcy = sh + PD[1] * ch / 100; dr = PD[2] * w / 100;
        } else {
            float sw = Math.min(w, h * 1.5f), sh = sw / 1.5f;
            scr.set((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2);
            for (int i = 0; i < 15; i++) {
                px[i] = LS[i][0] * w / 100; py[i] = LS[i][1] * h / 100; pr[i] = LS[i][2] * h / 100;
            }
            dcx = LD[0] * w / 100; dcy = LD[1] * h / 100; dr = LD[2] * h / 100;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Color.BLACK);
        Native.draw(bmp);
        p.setFilterBitmap(true);
        c.drawBitmap(bmp, null, scr, p);
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
