package org.gbamax.emu;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Importa uma imagem da galeria para ser a capa de um jogo. Fica salva e vale também depois de reabrir o app. */
final class CapaCustom {
    static final int REQ = 9;
    static MenuView.Game alvo;
    static MainActivity host;

    /** Mesmo arquivo que o baixador de capas usa, então a capa importada não é sobrescrita. */
    static File arquivo(Context c, String nome) {
        return new File(Pastas.capas(c), CoverFetcher.safe(nome) + ".jpg");
    }

    static void escolher(MainActivity a, MenuView.Game g) {
        host = a;
        alvo = g;
        try {
            a.getFragmentManager().beginTransaction().add(new Pedido(), "capa").commitAllowingStateLoss();
        } catch (Throwable t) {
            a.toast("Não consegui abrir a galeria");
        }
    }

    /** Fragmento sem tela: abre o seletor de imagens e recebe o resultado (não precisa mexer na MainActivity). */
    public static class Pedido extends Fragment {
        boolean aberto;

        @Override public void onStart() {
            super.onStart();
            if (aberto) return;
            aberto = true;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            try {
                startActivityForResult(i, REQ);
            } catch (Throwable e) {
                fechar();
                if (host != null) host.toast("Não consegui abrir a galeria");
            }
        }

        @Override public void onActivityResult(int req, int res, Intent d) {
            fechar();
            if (req != REQ || res != Activity.RESULT_OK || d == null || d.getData() == null) return;
            importar(d.getData());
        }

        void fechar() {
            try { getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss(); } catch (Throwable t) { }
        }
    }

    static void importar(final Uri u) {
        final MainActivity a = host;
        final MenuView.Game g = alvo;
        if (a == null || g == null) return;
        new Thread(() -> {
            final File f = arquivo(a, g.name);
            boolean ok = false;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = a.getContentResolver().openInputStream(u)) {
                    BitmapFactory.decodeStream(in, null, o);
                }
                int s = 1;
                while (o.outWidth / (s * 2) >= 768 && o.outHeight / (s * 2) >= 768) s *= 2;
                o.inJustDecodeBounds = false;
                o.inSampleSize = s;
                Bitmap src;
                try (InputStream in = a.getContentResolver().openInputStream(u)) {
                    src = BitmapFactory.decodeStream(in, null, o);
                }
                if (src != null) {
                    // limita o maior lado a 768 px mantendo a proporção (a grade corta o excesso no centro)
                    float k = Math.min(1f, 768f / Math.max(src.getWidth(), src.getHeight()));
                    int w = Math.max(1, Math.round(src.getWidth() * k)), h = Math.max(1, Math.round(src.getHeight() * k));
                    Bitmap dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas cv = new Canvas(dst);
                    cv.drawColor(Color.WHITE);
                    cv.drawBitmap(src, null, new RectF(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
                    src.recycle();
                    File p = f.getParentFile();
                    if (p != null) p.mkdirs();
                    File tmp = new File(f.getPath() + ".tmp");
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        ok = dst.compress(Bitmap.CompressFormat.JPEG, 92, out);
                    }
                    dst.recycle();
                    ok = ok && tmp.length() > 500 && (!f.exists() || f.delete()) && tmp.renameTo(f);
                }
            } catch (Throwable t) {
                ok = false;
            }
            final boolean fim = ok;
            a.runOnUiThread(() -> {
                if (fim) {
                    Uri cu = Uri.fromFile(f);
                    g.cover = cu;
                    a.mv.cache.remove(cu.toString()); a.mv.blur.remove(cu.toString());
                    a.mv.failed.remove(cu.toString());
                    a.mv.refreshCovers();
                    a.toast("Capa definida");
                } else a.toast("Não consegui usar essa imagem");
            });
        }).start();
    }
}
