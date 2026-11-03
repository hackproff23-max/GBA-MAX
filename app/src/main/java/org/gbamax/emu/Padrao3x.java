package org.gbamax.emu;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.InputStream;

/** Instala o 3xBRZ (assets/3xbrz.shader) em Shaders/padrao e o escolhe como shader padrão (uma vez). */
final class Padrao3x {
    static void instalar(Context c) {
        try {
            String rel = "padrao/3xbrz.shader";
            File out = new File(ShaderStore.root(c), rel);
            // sempre reinstala: assim uma versão nova do shader no app substitui a antiga
            try (InputStream in = c.getAssets().open("3xbrz.shader")) { ShaderStore.copy(in, out); }
            SharedPreferences p = c.getSharedPreferences("gbamax_video", 0);
            if (p.getBoolean("padrao_3xbrz", false)) return;
            String cur = VideoSettings.shader;
            // só troca se estava sem shader ou num dos padrões antigos (não mexe em shader escolhido por você)
            if (cur == null || cur.isEmpty() || cur.startsWith("padrao/")) {
                VideoSettings.shader = rel;
                p.edit().putString("shader", rel).apply();
            }
            p.edit().putBoolean("padrao_3xbrz", true).apply();
        } catch (Throwable t) { }
    }
}
