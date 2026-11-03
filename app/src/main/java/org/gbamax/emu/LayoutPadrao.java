package org.gbamax.emu;

/** Layout padrão em paisagem (posições da imagem de referência): tela cheia, analógico e D-pad visíveis. */
final class LayoutPadrao {
    // x%largura, y%altura, raio%altura
    static final float[][] LS = {
        {5.9f, 7.6f, 12.4f}, {16.6f, 8.8f, 7.3f}, {24.8f, 8.3f, 7.3f}, {74.5f, 8.3f, 7.3f}, {83.2f, 7.9f, 7.3f}, {93.75f, 7.9f, 13.2f},
        {93.9f, 77.1f, 10.1f}, {80.9f, 85.7f, 10.1f}, {84.9f, 59.4f, 7.3f}, {93.1f, 53.2f, 7.3f}, {76.6f, 66.4f, 7.3f},
        {40f, 91.8f, 7.9f}, {50f, 91.8f, 7.9f}, {60f, 91.8f, 6.6f}, {50f, 5.8f, 4.6f}};
    static final float[] ANALOG = {8.6f, 38.6f, 13.6f};
    static final float[] DPAD = {9.75f, 78.75f, 18.4f};

    /** Chamado no fim de GameView.defaults(): em retrato não mexe em nada. */
    static void aplicar(GameView g, int w, int h) {
        if (h > w) return;
        for (int i = 0; i < 15; i++) {
            g.ax[i] = LS[i][0] / 100f;
            g.ay[i] = LS[i][1] / 100f;
            g.ar[i] = LS[i][2] / 100f;
        }
        g.ax[GameView.DPAD] = ANALOG[0] / 100f; g.ay[GameView.DPAD] = ANALOG[1] / 100f; g.ar[GameView.DPAD] = ANALOG[2] / 100f;
        g.ax[GameView.DPC] = DPAD[0] / 100f; g.ay[GameView.DPC] = DPAD[1] / 100f; g.ar[GameView.DPC] = DPAD[2] / 100f;
        g.ax[GameView.SCREEN] = .5f; g.ay[GameView.SCREEN] = .5f; g.ar[GameView.SCREEN] = 1f;
        g.sfh = 1f;      // tela cheia
        g.hidden = 0;    // analógico e D-pad visíveis
    }
}
