package org.gbamax.emu;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;

/** Configurações de vídeo (salvas em SharedPreferences próprio). */
public class VideoSettings {
    // 0 = automática, 1 = retrato, 2 = paisagem
    public static volatile int orient = 0;
    // 0 = desligado; 1..4 = pula N quadros a cada quadro mostrado
    public static volatile int frameskip = 0;
    // caminho relativo dentro de filesDir/shaders ("" = nenhum)
    public static volatile String shader = "";
    // controle de cores (independente de shader)
    public static volatile int bright = 0;     // -100..100
    public static volatile int contrast = 100; // 0..200 (%)
    public static volatile int sat = 100;      // 0..200 (%)
    public static volatile int gamma = 100;    // 50..200 (/100)
    public static volatile int lcd = 0;        // 0..100 (%)

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
    }

    public static void save(Context c) {
        sp(c).edit().putInt("orient", orient).putInt("frameskip", frameskip)
                .putString("shader", shader).putInt("bright", bright)
                .putInt("contrast", contrast).putInt("sat", sat)
                .putInt("gamma", gamma).putInt("lcd", lcd).apply();
    }

    public static void resetColors() { bright = 0; contrast = 100; sat = 100; gamma = 100; lcd = 0; }

    public static void applyOrientation(Activity a) {
        int o = orient == 1 ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : orient == 2 ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
        a.setRequestedOrientation(o);
    }

    // layout dos controles/tela, um por orientação
    public static String getLayout(Context c, boolean land) { return sp(c).getString(land ? "layout_l" : "layout_p", null); }

    public static void setLayout(Context c, boolean land, String s) {
        SharedPreferences.Editor e = sp(c).edit();
        if (s == null) e.remove(land ? "layout_l" : "layout_p"); else e.putString(land ? "layout_l" : "layout_p", s);
        e.apply();
    }
}
