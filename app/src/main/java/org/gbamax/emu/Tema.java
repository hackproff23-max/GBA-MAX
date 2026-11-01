package org.gbamax.emu;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import java.util.Arrays;

/** Cores editáveis do app (Avançado > Personalizar > Cores do app). */
final class Tema {
    static final int VIDRO_TOPO = 0, VIDRO_BASE = 1, PLACA_TOPO = 2, PLACA_BASE = 3, TEXTO = 4, TEXTO2 = 5, LINHA = 6,
            FUNDO0 = 7, SIMB0 = 13, CONTORNO = 19, N = 20;

    static final String[] NOME = {
            "Vidro: cor de cima (barra, dock, janelas)", "Vidro: cor de baixo",
            "Placa dos jogos: cima", "Placa dos jogos: baixo",
            "Letras principais", "Letras secundárias", "Linhas das janelas (fundo)",
            "Início: fundo do ícone", "Tela: fundo do ícone", "Áudio: fundo do ícone",
            "Controle: fundo do ícone", "Saves: fundo do ícone", "Configurações: fundo do ícone",
            "Início: símbolo", "Tela: símbolo", "Áudio: símbolo",
            "Controle: símbolo", "Saves: símbolo", "Configurações: símbolo", "Contorno das janelas: cor"};

    static final int[] PADRAO = {
            0xF5FFFFFF, 0xE6E4EAF3, 0xFAFFFFFF, 0xF2E6ECF5, 0xFF1B1F27, 0xFF8A93A3, 0x99FFFFFF,
            0xFFFFFFFF, 0xFF1E6BE6, 0xFFFFA000, 0xFF7E57C2, 0xFF2EAD4B, 0xFF3A3C42,
            0xFF111111, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF};

    static final int[] cur = PADRAO.clone();
    static float grossura = 1.5f;
    static boolean introJa; // a abertura toca só uma vez por abertura do app (não ao reaplicar cores)

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("tema", 0); }

    static void load(Context c) {
        SharedPreferences s = sp(c);
        for (int i = 0; i < N; i++) cur[i] = s.getInt("c" + i, PADRAO[i]); grossura = s.getFloat("grossura", 1.5f);
    }

    static int get(int i) { return cur[i]; }
    static int glyph(int i) { return cur[SIMB0 + i]; }
    static int[] dockBg() { return Arrays.copyOfRange(cur, FUNDO0, FUNDO0 + 6); }
    static int[] dockBg1() { return Arrays.copyOfRange(cur, FUNDO0 + 1, FUNDO0 + 6); }
    static int[] dockIc() { return Arrays.copyOfRange(cur, SIMB0, SIMB0 + 6); }

    static double lum(int c) {
        double r = Math.pow(Color.red(c) / 255.0, 2.2), g = Math.pow(Color.green(c) / 255.0, 2.2), b = Math.pow(Color.blue(c) / 255.0, 2.2);
        return .2126 * r + .7152 * g + .0722 * b;
    }

    static double contraste(int a, int b) {
        double x = lum(a), y = lum(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }

    /** Impede escolher letra e fundo quase iguais (você não conseguiria mais ler os menus). */
    static boolean legivel(int i, int v) {
        int[] t = cur.clone();
        t[i] = v;
        int[] fundos = {VIDRO_TOPO, VIDRO_BASE, PLACA_TOPO, PLACA_BASE, LINHA};
        for (int f : fundos) if (contraste(t[TEXTO], t[f]) < 2.0) return false;
        return true;
    }

    static void set(Context c, int i, int v) {
        cur[i] = v;
        sp(c).edit().putInt("c" + i, v).apply();
    }

    static void reset(Context c, int i) { set(c, i, PADRAO[i]); }
    static void setGrossura(Context c, float v) { grossura = v; sp(c).edit().putFloat("grossura", v).apply(); }

    static void resetAll(Context c) {
        System.arraycopy(PADRAO, 0, cur, 0, N);
        grossura = 1.5f; sp(c).edit().clear().apply();
    }
}
