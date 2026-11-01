package org.gbamax.emu;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Map;

/**
 * GBA-MAX/Avançado/personalizacao.json: guarda tudo que você edita no app
 * (cores, fundo, abertura, botões e as configurações padrão).
 * É gravado ao sair do app e lido ao abrir: dá para editar o arquivo à mão.
 */
final class Avancado {
    static final String ARQ = "personalizacao.json";

    static File arq(Context c) {
        File d = Pastas.dir(c, Pastas.AVANCADO);
        return d == null ? null : new File(d, ARQ);
    }

    static JSONObject ler(File f) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(f)) {
                byte[] t = new byte[4096];
                int k;
                while ((k = in.read(t)) != -1) b.write(t, 0, k);
            }
            return new JSONObject(b.toString("UTF-8"));
        } catch (Exception e) { return null; }
    }

    static JSONObject tipo(File dir, String base) throws Exception {
        JSONObject o = new JSONObject();
        File g = dir == null ? null : new File(dir, base + ".gif");
        File p = dir == null ? null : new File(dir, base + ".png");
        if (g != null && g.isFile()) { o.put("tipo", "gif"); o.put("arquivo", g.getName()); }
        else if (p != null && p.isFile()) { o.put("tipo", "imagem"); o.put("arquivo", p.getName()); }
        else { o.put("tipo", "padrao"); o.put("arquivo", ""); }
        return o;
    }

    static void salvar(Context c) {
        try {
            File f = arq(c);
            if (f == null) return;
            JSONObject o = new JSONObject();
            o.put("versao", 1);
            JSONObject cores = new JSONObject();
            for (int i = 0; i < Tema.N; i++) cores.put(Tema.NOME[i], String.format("#%08X", Tema.cur[i]));
            o.put("cores", cores);
            o.put("grossura_contorno", (double) Tema.grossura); o.put("grossura_contorno_texto", (double) Tema.txtGrossura);
            File pd = Personalizar.dir(c);
            o.put("fundo", tipo(pd, "fundo"));
            o.put("abertura", tipo(pd, "abertura"));
            JSONArray bt = new JSONArray();
            File bd = pd == null ? null : new File(pd, Personalizar.BOTOES);
            File[] bl = bd == null ? null : bd.listFiles();
            if (bl != null) {
                java.util.Arrays.sort(bl);
                for (File x : bl) bt.put(x.getName());
            }
            o.put("botoes", bt);
            JSONObject padroes = new JSONObject();
            Map<String, ?> all = VideoSettings.sp(c).getAll();
            for (Map.Entry<String, ?> e : all.entrySet()) padroes.put(e.getKey(), e.getValue());
            o.put("padroes", padroes);
            JSONObject sj = new JSONObject();
            File cd = Pastas.dir(c, Pastas.CONFIG);
            File[] js = cd == null ? null : cd.listFiles();
            if (js != null) {
                for (File x : js) {
                    String n = x.getName();
                    if (!n.endsWith(".json")) continue;
                    JSONObject gj = ler(x);
                    if (gj == null) continue;
                    sj.put(n.substring(0, n.length() - 5), gj.optBoolean("shader_padrao", false) ? "(padrão)" : gj.optString("shader", ""));
                }
            }
            o.put("shader_por_jogo", sj);
            o.put("_aviso", "cores e padroes sao lidos ao abrir o app; shader_por_jogo é só informativo (o shader de cada jogo fica em Configurações/<jogo>.json)");
            try (OutputStream w = new FileOutputStream(f)) { w.write(o.toString(2).getBytes("UTF-8")); }
        } catch (Throwable t) { }
    }

    /** Lê o arquivo (se existir) e aplica cores e configurações padrão. Chamar antes de Tema.load e VideoSettings.load. */
    static void carregar(Context c) {
        try {
            File f = arq(c);
            if (f == null || !f.isFile()) return;
            JSONObject o = ler(f);
            if (o == null) return;
            JSONObject cores = o.optJSONObject("cores");
            SharedPreferences.Editor e = Tema.sp(c).edit();
            if (cores != null) {
                for (int i = 0; i < Tema.N; i++) {
                    String h = cores.optString(Tema.NOME[i], "");
                    if (h.startsWith("#") && h.length() == 9) {
                        try { e.putInt("c" + i, (int) Long.parseLong(h.substring(1), 16)); } catch (Exception x) { }
                    }
                }
            }
            if (o.has("grossura_contorno")) e.putFloat("grossura", (float) o.optDouble("grossura_contorno", 1.5)); if (o.has("grossura_contorno_texto")) e.putFloat("txtGrossura", (float) o.optDouble("grossura_contorno_texto", 0));
            e.apply();
            JSONObject p = o.optJSONObject("padroes");
            if (p != null) {
                SharedPreferences.Editor v = VideoSettings.sp(c).edit();
                Iterator<String> it = p.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    Object x = p.opt(k);
                    if (x instanceof Boolean) v.putBoolean(k, (Boolean) x);
                    else if (x instanceof String) v.putString(k, (String) x);
                    else if (x instanceof Number) v.putInt(k, ((Number) x).intValue());
                }
                v.apply();
            }
        } catch (Throwable t) { }
    }
}
