package org.gbamax.emu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Botão de recarregar a lista de jogos (setas em círculo) na barra superior. */
class Recarregar extends View {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF q = new RectF();

    Recarregar(Context c) {
        super(c);
        setClickable(true);
    }

    void girar() { animate().rotationBy(360f).setDuration(600).start(); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
        float R = Math.min(w, h) / 2f * .55f;
        int col = Tema.get(Tema.TEXTO);
        p.setColor(col);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(R * .32f);
        p.setStrokeCap(Paint.Cap.ROUND);
        q.set(cx - R, cy - R, cx + R, cy + R);
        c.drawArc(q, 30, 280, false, p);
        // ponta da seta no fim do arco (sentido horário)
        double th = Math.toRadians(310);
        float ex = cx + R * (float) Math.cos(th), ey = cy + R * (float) Math.sin(th);
        float rx = (float) Math.cos(th), ry = (float) Math.sin(th);
        float tx = -ry, ty = rx;
        Path a = new Path();
        a.moveTo(ex + tx * R * .75f, ey + ty * R * .75f);
        a.lineTo(ex + rx * R * .7f, ey + ry * R * .7f);
        a.lineTo(ex - rx * R * .7f, ey - ry * R * .7f);
        a.close();
        p.setStyle(Paint.Style.FILL);
        c.drawPath(a, p);
    }
}
