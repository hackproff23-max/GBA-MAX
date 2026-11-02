package org.gbamax.emu;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Configurações de vídeo/áudio/controle.
 * Valem para TODOS os jogos: ficam em SharedPreferences (um conjunto só).
 * (O antigo arquivo GBA-MAX/Configurações/NOME.json por jogo não é mais usado.)
 */
public class VideoSettings {
    /** false = as configurações são iguais para todos os jogos. */
    static final boolean POR_JOGO = false;

    // 0 = automática, 1 = retrato, 2 = paisagem (vale só dentro do jogo e do editor de layout)
    public static volatile int orient = 0;
    // 0 = desligado; 1..4 = pula N quadros a cada quadro mostrado
    public static volatile int frameskip = 0;
    // caminho relativo dentro da pasta Shaders ("" = nenhum)
    public static volatile String shader = "";
    // controle de cores (independente de shader)
    public static volatile int bright = 0;     // -100..100
    public static volatile int contrast = 100; // 0..200 (%)
    public static volatile int sat = 100;      // 0..200 (%)
    public static volatile int gamma = 100;    // 50..200 (/100)
    public static volatile int lcd = 0;        // 0..100 (%)
    // áudio
    public static volatile int volume = 100;   // 0..100
    public static volatile boolean mute = false;
    public static volatile int abuf = 0;       // 0 pequeno, 1 médio, 2 grande
    // controle
    public static volatile int ctlAlpha = 100; // opacidade dos botões (10..100)
    public static volatile boolean vibrate = false;
    // avançado
    public static volatile int ffSpeed = 4;    // velocidade do avanço rápido
    // retomar de onde parou: ao sair do jogo guarda o estado e ao abrir volta nele
    public static volatile boolean resume = true;
    // salvamento automático: liga/desliga e intervalo em segundos
    public static volatile boolean autoOn = true;
    public static volatile int autoSecs = 60;

    /** Nome do jogo aberto (null = fora do jogo). Só informativo: as configurações não mudam por jogo. */
    public static volatile String game = null;
    static String gLayoutP, gLayoutL;
    static volatile boolean shaderPadrao = true; static String shaderBase = "";

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("gbamax_video", 0); }

    public static void load(Context c) {
        SharedPreferences p = sp(c);
        orient = p.getInt("orient", 0);
        frameskip = p.getInt("frameskip", 0);
        shader = p.getString("shader", "");
        bright = p.getInt("bright", 0);
        contrast = p.getInt("contrast", 100);
        sat = p.getInt("sat", 100);
        gamma = p.getInt("gamma", 100);
        lcd = p.getInt("lcd", 0);
        volume = p.getInt("volume", 100);
        mute = p.getBoolean("mute", false);
        abuf = p.getInt("abuf", 0);
        ctlAlpha = p.getInt("ctlAlpha", 100);
        vibrate = p.getBoolean("vibrate", false);
        ffSpeed = p.getInt("ffSpeed", 4);
        resume = p.getBoolean("resume", true);
        autoOn = p.getBoolean("autoOn", true);
        autoSecs = p.getInt("autoSecs", 60);
    }

    public static void save(Context c) {
        if (POR_JOGO && game != null) {
            File jf = jsonFile(c);
            if (jf != null && writeJson(jf)) return;
        }
        sp(c).edit().putInt("orient", orient).putInt("frameskip", frameskip)
                .putString("shader", shader).putInt("bright", bright)
                .putInt("contrast", contrast).putInt("sat", sat)
                .putInt("gamma", gamma).putInt("lcd", lcd)
                .putInt("volume", volume).putBoolean("mute", mute).putInt("abuf", abuf)
                .putInt("ctlAlpha", ctlAlpha).putBoolean("vibrate", vibrate)
                .putInt("ffSpeed", ffSpeed).putBoolean("resume", resume)
                .putBoolean("autoOn", autoOn).putInt("autoSecs", autoSecs).apply();
    }

    // ================= abrir / fechar jogo =================
    /** Abre um jogo: usa as mesmas configurações de todos os jogos (nada é lido nem gravado por jogo). */
    public static void openGame(Context c, String nome) {
        load(c);
        game = nome;
        if (!POR_JOGO) { gLayoutP = null; gLayoutL = null; return; }
        gLayoutP = sp(c).getString("layout_p", null);
        gLayoutL = sp(c).getString("layout_l", null);
        shaderBase = shader; shaderPadrao = true;
        File jf = jsonFile(c);
        if (jf == null) { game = null; return; } // sem acesso à pasta: usa as padrão
        if (jf.isFile()) readJson(jf); else writeJson(jf);
    }

    /** Sai do jogo: recarrega as configurações. */
    public static void closeGame(Context c) {
        game = null;
        load(c);
    }

    static File jsonFile(Context c) {
        String g = game;
        if (g == null) return null;
        File d = Pastas.dir(c, Pastas.CONFIG);
        return d == null ? null : new File(d, Pastas.seguro(g) + ".json");
    }

    static boolean writeJson(File f) {
        try {
            JSONObject o = new JSONObject();
            o.put("orient", orient);
            o.put("frameskip", frameskip);
            if (shaderPadrao && !String.valueOf(shader).equals(shaderBase)) shaderPadrao = false; o.put("shader", shader == null ? "" : shader); o.put("shader_padrao", shaderPadrao);
            o.put("bright", bright);
            o.put("contrast", contrast);
            o.put("sat", sat);
            o.put("gamma", gamma);
            o.put("lcd", lcd);
            o.put("volume", volume);
            o.put("mute", mute);
            o.put("abuf", abuf);
            o.put("ctlAlpha", ctlAlpha);
            o.put("vibrate", vibrate);
            o.put("ffSpeed", ffSpeed);
            o.put("resume", resume);
            o.put("autoOn", autoOn);
            o.put("autoSecs", autoSecs);
            if (gLayoutP != null) o.put("layout_p", gLayoutP);
            if (gLayoutL != null) o.put("layout_l", gLayoutL);
            try (OutputStream w = new FileOutputStream(f)) {
                w.write(o.toString(2).getBytes("UTF-8"));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void readJson(File f) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(f)) {
                byte[] t = new byte[4096];
                int k;
                while ((k = in.read(t)) != -1) b.write(t, 0, k);
            }
            JSONObject o = new JSONObject(b.toString("UTF-8"));
            orient = o.optInt("orient", orient);
            frameskip = o.optInt("frameskip", frameskip);
            shaderPadrao = o.optBoolean("shader_padrao", false); if (!shaderPadrao) shader = o.optString("shader", shader);
            bright = o.optInt("bright", bright);
            contrast = o.optInt("contrast", contrast);
            sat = o.optInt("sat", sat);
            gamma = o.optInt("gamma", gamma);
            lcd = o.optInt("lcd", lcd);
            volume = o.optInt("volume", volume);
            mute = o.optBoolean("mute", mute);
            abuf = o.optInt("abuf", abuf);
            ctlAlpha = o.optInt("ctlAlpha", ctlAlpha);
            vibrate = o.optBoolean("vibrate", vibrate);
            ffSpeed = o.optInt("ffSpeed", ffSpeed);
            resume = o.optBoolean("resume", resume);
            autoOn = o.optBoolean("autoOn", autoOn);
            autoSecs = o.optInt("autoSecs", autoSecs);
            if (o.has("layout_p")) gLayoutP = o.getString("layout_p");
            if (o.has("layout_l")) gLayoutL = o.getString("layout_l");
        } catch (Exception e) { }
    }

    static JSONObject jogoObj(Context c, String nome) { try { File d = Pastas.dir(c, Pastas.CONFIG); if (d == null) return null; File f = new File(d, Pastas.seguro(nome) + ".json"); if (!f.isFile()) return new JSONObject(); ByteArrayOutputStream b = new ByteArrayOutputStream(); try (InputStream in = new FileInputStream(f)) { byte[] t = new byte[4096]; int k; while ((k = in.read(t)) != -1) b.write(t, 0, k); } return new JSONObject(b.toString("UTF-8")); } catch (Exception e) { return new JSONObject(); } }
    static boolean shaderPadraoDoJogo(Context c, String nome) { if (!POR_JOGO) return true; JSONObject o = jogoObj(c, nome); return o == null || o.length() == 0 || o.optBoolean("shader_padrao", false); }
    static String shaderNomeDoJogo(Context c, String nome) { if (!POR_JOGO) return shader == null ? "" : shader; JSONObject o = jogoObj(c, nome); return o == null ? "" : o.optString("shader", ""); }
    static void shaderDoJogo(Context c, String nome, boolean padrao, String sh) { if (!POR_JOGO) return; try { File d = Pastas.dir(c, Pastas.CONFIG); if (d == null) return; JSONObject o = jogoObj(c, nome); o.put("shader", sh == null ? "" : sh); o.put("shader_padrao", padrao); try (OutputStream w = new FileOutputStream(new File(d, Pastas.seguro(nome) + ".json"))) { w.write(o.toString(2).getBytes("UTF-8")); } } catch (Exception e) { } }
    public static void resetColors() { bright = 0; contrast = 100; sat = 100; gamma = 100; lcd = 0; }

    /** Orientação escolhida pelo usuário: usada só no jogo e no editor de layout. */
    public static void applyOrientation(Activity a) {
        int o = orient == 1 ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : orient == 2 ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
        a.setRequestedOrientation(o);
    }

    /** O menu fica sempre em retrato. */
    public static void applyMenuOrientation(Activity a) {
        a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
    }

    // layout dos controles/tela, um por orientação (igual para todos os jogos)
    public static String getLayout(Context c, boolean land) {
        if (POR_JOGO && game != null) return land ? gLayoutL : gLayoutP;
        return sp(c).getString(land ? "layout_l" : "layout_p", null);
    }

    public static void setLayout(Context c, boolean land, String s) {
        if (POR_JOGO && game != null) {
            if (land) gLayoutL = s; else gLayoutP = s;
            File jf = jsonFile(c);
            if (jf != null) writeJson(jf);
            return;
        }
        SharedPreferences.Editor e = sp(c).edit();
        if (s == null) e.remove(land ? "layout_l" : "layout_p"); else e.putString(land ? "layout_l" : "layout_p", s);
        e.apply();
    }
}
