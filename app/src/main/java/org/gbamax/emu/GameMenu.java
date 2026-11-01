package org.gbamax.emu;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Menu dentro do jogo: cores (com prévia ao vivo), estados salvos e opções. */
class GameMenu {
    interface Bool { void set(boolean v); }

    static final int[] FF = {1, 2, 3, 5, 8, 16};
    static final String[] BUF = {"Pequeno", "Médio", "Grande"};

    final MainActivity a;
    final MenuView mv;
    final Context c;
    final List<Dialog> open = new ArrayList<>();
    Dialog main, advD, optD;
    boolean coresOpen;
    LinearLayout panel;
    View frame;
    TextView shaderTv;

    GameMenu(MainActivity act) { a = act; mv = act.mv; c = act; }

    int dp(float v) { return mv.dp(v); }

    void toast(String s) { Toast.makeText(c, s, Toast.LENGTH_SHORT).show(); }

    // ================= controle dos diálogos =================
    Dialog track(final Dialog d, final Runnable onClose) {
        open.add(d);
        d.setOnDismissListener(x -> {
            open.remove(d);
            if (onClose != null) onClose.run();
        });
        return d;
    }

    void dismissAll() {
        for (Dialog d : new ArrayList<>(open)) { try { d.dismiss(); } catch (Throwable t) { } }
        open.clear();
        main = null;
        if (coresOpen) {
            removeCoresViews();
            a.gv.recalc();
        }
    }

    void closeAll() {
        dismissAll();
        a.resumeGame();
    }

    void open() {
        a.pauseGame();
        showMain();
    }

    // ================= peças de interface =================
    LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.bottomMargin = dp(6);
        return l;
    }

    LinearLayout row(int icon, int bg, String t, String sub, final Runnable r) {
        return row(null, icon, bg, t, sub, r);
    }

    /** Miniatura do estado (imagem sem extensão ao lado dele), ou null se não houver. */
    Bitmap thumb(int slot) {
        try {
            File f = new File(a.thumbPath(slot));
            if (!f.isFile() || !new File(a.statePath(slot)).isFile()) return null;
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    LinearLayout row(Bitmap tb, int icon, int bg, String t, String sub, final Runnable r) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(3), dp(12), dp(3));
        GradientDrawable rg = new GradientDrawable();
        rg.setColor(0x99FFFFFF);
        rg.setCornerRadius(dp(16));
        rg.setStroke(dp(1), 0xCCFFFFFF);
        row.setBackground(rg);
        if (tb != null) {
            ImageView iv = new ImageView(c);
            iv.setImageBitmap(tb);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(6));
                }
            });
            iv.setClipToOutline(true);
            row.addView(iv, new LinearLayout.LayoutParams(dp(66), dp(44)));
        } else {
            row.addView(mv.new DockBtn(c, icon, bg, Color.WHITE, false), new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(mv.label(t, 14, 0xFF1B1F27, true));
        if (sub != null && !sub.isEmpty()) {
            TextView v = mv.label(sub, 11.5f, 0xFF6B7587, false);
            v.setSingleLine(true);
            v.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(v);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.leftMargin = dp(6);
        row.addView(col, cl);
        row.addView(mv.label("›", 20, 0xFF8A93A3, false));
        row.setTag("hov"); row.setOnClickListener(v -> r.run());
        return row;
    }

    Dialog pageDialog(String title, LinearLayout list, int rows, Runnable onClose) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = mv.label(title, 18, 0xFF1B1F27, true);
        t.setPadding(dp(4), 0, 0, dp(10));
        box.addView(t);
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list);
        int maxH = (int) (c.getResources().getDisplayMetrics().heightPixels * .6f);
        box.addView(sv, new LinearLayout.LayoutParams(-1, Math.min(dp(rows * 58), maxH)));
        TextView back = mv.btn("Voltar", 0xFFE3E7EE, 0xFF1B1F27);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = dp(8);
        box.addView(back, bl);
        final Dialog d = mv.glassDialog(box);
        d.setCanceledOnTouchOutside(true);
        track(d, onClose);
        back.setOnClickListener(v -> d.dismiss());
        return d;
    }

    void paintToggle(TextView b, String label, boolean on) {
        b.setText(label + ": " + (on ? "ligado" : "desligado"));
        b.setBackground(mv.pill(on ? 0xFF1E6BE6 : 0x99FFFFFF));
        b.setTextColor(on ? Color.WHITE : 0xFF1B1F27);
    }

    TextView toggle(final String label, boolean init, final Bool s) {
        final boolean[] st = {init};
        final TextView b = mv.btn("", 0x99FFFFFF, 0xFF1B1F27);
        paintToggle(b, label, st[0]);
        b.setOnClickListener(v -> {
            st[0] = !st[0];
            s.set(st[0]);
            paintToggle(b, label, st[0]);
        });
        return b;
    }

    /** VideoMenu cujo "voltar" executa a ação dada (em vez de abrir a janela de vídeo). */
    VideoMenu vmBack(final Runnable back) {
        return new VideoMenu(mv) {
            @Override void show() { if (back != null) back.run(); }
        };
    }

    // ================= menu principal =================
    void showMain() {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView title = mv.label("Menu", 18, 0xFF1B1F27, true);
        title.setPadding(dp(4), 0, 0, dp(10));
        box.addView(title);
        LinearLayout list = new HoverList(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(row(MenuView.Icons.FACE, 0xFFE91E63, "Cores", "Shader, LCD, brilho, contraste...", this::openCores), lp());
        list.addView(row(MenuView.Icons.CHEAT, 0xFFFFA000, "Cheats", "Ligar e desligar códigos do jogo", () -> new CheatMenu(this, Pastas.seguro(a.gameName != null ? a.gameName : "jogo"), true).show()), lp());
        list.addView(row(MenuView.Icons.SAVE, 0xFF2EAD4B, "Salvar estado", "5 slots manuais", () -> slots(true)), lp());
        list.addView(row(MenuView.Icons.LIB, 0xFF1E6BE6, "Carregar estado", "Retomar, 3 automáticos + 5 manuais", () -> slots(false)), lp());
        list.addView(row(MenuView.Icons.GEAR, 0xFF3A3C42, "Opções", "Vídeo, áudio, controle e avançado", this::options), lp());
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list);
        int maxH = (int) (c.getResources().getDisplayMetrics().heightPixels * .55f);
        box.addView(sv, new LinearLayout.LayoutParams(-1, Math.min(dp(5 * 58), maxH)));
        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView exit = mv.btn("Sair do jogo", 0xFFE3E7EE, 0xFF1B1F27);
        TextView back = mv.btn("Voltar ao jogo", 0xFF2EAD4B, Color.WHITE);
        btns.addView(exit, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -2, 1f);
        bl.leftMargin = dp(8);
        btns.addView(back, bl);
        LinearLayout.LayoutParams bll = new LinearLayout.LayoutParams(-1, -2);
        bll.topMargin = dp(8);
        box.addView(btns, bll);
        final Dialog d = mv.glassDialog(box);
        d.setCanceledOnTouchOutside(true);
        track(d, null);
        main = d;
        d.setOnCancelListener(x -> { main = null; a.resumeGame(); });
        exit.setOnClickListener(v -> { dismissAll(); a.showMenu(); });
        back.setOnClickListener(v -> closeAll());
        d.show();
    }

    // ================= estados salvos =================
    // slots 1..5 = manuais; 101..103 = automáticos (a cada 1 minuto jogado, só para carregar)
    void slots(final boolean save) {
        LinearLayout l = new HoverList(c);
        l.setOrientation(LinearLayout.VERTICAL);
        SimpleDateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault());
        int rows = 5;
        if (!save) {
            // o automático mais recente fica marcado
            int newest = 0;
            long nm = 0;
            for (int s = 1; s <= 3; s++) {
                File f = new File(a.statePath(100 + s));
                if (f.isFile() && f.lastModified() > nm) { nm = f.lastModified(); newest = s; }
            }
            for (int s = 1; s <= 3; s++) {
                final int slot = 100 + s;
                File f = new File(a.statePath(slot));
                String sub = f.isFile() ? df.format(new Date(f.lastModified())) : "Vazio";
                if (s == newest) sub += "  (mais recente)";
                l.addView(row(thumb(slot), MenuView.Icons.LIB, 0xFFFFA000, "Automático " + s, sub, () -> doState(false, slot)), lp());
            }
            rows = 8;
            File rf = new File(a.statePath(MainActivity.RETOMAR));
            if (rf.isFile()) {
                l.addView(row(thumb(MainActivity.RETOMAR), MenuView.Icons.LIB, 0xFF7E57C2, "Retomar (ao sair)",
                        df.format(new Date(rf.lastModified())), () -> doState(false, MainActivity.RETOMAR)), 0, lp());
                rows = 9;
            }
        }
        for (int s = 1; s <= 5; s++) {
            final int slot = s;
            File f = new File(a.statePath(s));
            String sub = f.exists() ? df.format(new Date(f.lastModified())) : "Vazio";
            l.addView(row(thumb(slot), save ? MenuView.Icons.SAVE : MenuView.Icons.LIB, save ? 0xFF2EAD4B : 0xFF1E6BE6,
                    "Manual " + s, sub, () -> doState(save, slot)), lp());
        }
        pageDialog(save ? "Salvar estado" : "Carregar estado", l, rows, null).show();
    }

    void doState(boolean save, int slot) {
        String path = a.statePath(slot);
        String nome = slot == MainActivity.RETOMAR ? "retomar" : slot > 100 ? "automático " + (slot - 100) : "manual " + slot;
        if (save) {
            boolean ok = a.saveStateImg(slot);
            toast(ok ? "Estado salvo no slot " + nome : "Não foi possível salvar o estado");
            if (ok) closeAll();
        } else {
            if (!new File(path).exists()) { toast("Slot vazio"); return; }
            boolean ok = Native.loadState(path);
            if (ok) {
                a.afterLoadState();
                toast("Estado carregado (" + nome + ")");
                closeAll();
            } else toast("Não foi possível carregar o estado");
        }
    }

    // ================= opções =================
    void options() {
        LinearLayout l = new HoverList(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(row(MenuView.Icons.SCREEN, 0xFF1E6BE6, "Vídeo", "Orientação, shaders, layout, pulo de quadros", () -> new VideoMenu(mv).show()), lp());
        l.addView(row(MenuView.Icons.AUDIO, 0xFFFFA000, "Áudio", "Volume, mudo e buffer", this::audio), lp());
        l.addView(row(MenuView.Icons.PAD, 0xFF7E57C2, "Controle", "Opacidade, vibração e layout", this::control), lp());
        l.addView(row(MenuView.Icons.ADV, 0xFF3A3C42, "Avançado", "Avanço rápido, quadros e reiniciar", this::advanced), lp());
        l.addView(row(MenuView.Icons.ADV, 0xFF1E6BE6, "Avanço rápido", ffl(VideoSettings.ffSpeed), () -> { optD.dismiss(); ffPick(); }), lp());
        l.addView(toggle("Retomar de onde parou", VideoSettings.resume, on -> {
            VideoSettings.resume = on;
            if (!on) a.clearResume();
            VideoSettings.save(c);
        }), lp());
        l.addView(toggle("Recuperar se o jogo travar", a.recoverOn(), on -> a.setRecover(on)), lp()); optD = pageDialog("Opções", l, 7, null); optD.show();
    }

    static String ffl(int v) { return v == 1 ? "1,5x" : v + "x"; }
    class HoverList extends LinearLayout {
        View hot;
        HoverList(Context x) { super(x); setOrientation(VERTICAL); setPadding(dp(6), dp(4), dp(6), dp(4)); }
        void paint(View v, boolean on) { if (true) return; v.animate().cancel(); v.animate().scaleX(on ? 1.04f : 1f).scaleY(on ? 1.04f : 1f).translationZ(on ? dp(6) : 0).setDuration(140).start(); if (v.getBackground() instanceof GradientDrawable) { GradientDrawable gd = (GradientDrawable) v.getBackground(); gd.setColor(on ? 0xE6CFE3FF : 0x99FFFFFF); gd.setStroke(dp(on ? 2 : 1), on ? 0xFF4D9BFF : 0xCCFFFFFF); } }
        void setHot(View v) { if (v == hot) return; if (hot != null) paint(hot, false); hot = v; if (v != null) paint(v, true); }
        @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) { int ac = e.getActionMasked(); if (ac == android.view.MotionEvent.ACTION_DOWN || ac == android.view.MotionEvent.ACTION_MOVE) { View f = null; for (int i = 0; i < getChildCount(); i++) { View ch = getChildAt(i); if (e.getX() >= ch.getLeft() && e.getX() <= ch.getRight() && e.getY() >= ch.getTop() && e.getY() <= ch.getBottom()) f = ch; } setHot(f); } else if (ac == android.view.MotionEvent.ACTION_UP || ac == android.view.MotionEvent.ACTION_CANCEL) setHot(null); return super.dispatchTouchEvent(e); }
    }
    void gameWindow(final MenuView.Game g) {
        LinearLayout l = new HoverList(c);
        l.addView(row(MenuView.Icons.PAD, 0xFF2EAD4B, "Iniciar jogo", VideoSettings.resume ? "Continua de onde você parou" : "Abre o jogo", () -> { dismissAll(); a.skipResume = false; a.pendingSlot = 0; a.playGame(g); }), lp());
        l.addView(row(MenuView.Icons.HOME, 0xFF1E6BE6, "Reiniciar jogo", "Começa do início (os saves ficam)", () -> { dismissAll(); a.skipResume = true; a.pendingSlot = 0; a.playGame(g); }), lp());
        l.addView(row(MenuView.Icons.LIB, 0xFFFFA000, "Estados salvos", "Escolher um estado para abrir", () -> estados(g)), lp());
        l.addView(row(MenuView.Icons.FACE, 0xFFE91E63, "Shader", shaderSub(g), () -> escolherShader(g)), lp());
        l.addView(row(MenuView.Icons.CHEAT, 0xFFFFA000, "Cheats", "Ligar e desligar códigos deste jogo", () -> new CheatMenu(this, Pastas.seguro(g.name), false).show()), lp());
        l.addView(row(MenuView.Icons.SAVE, 0xFFE53935, "Apagar save", "Remove o save e os estados do jogo", () -> confirm("Apagar save", "O save e todos os estados salvos (manuais, automáticos e retomar) de " + g.name + " serão apagados. Não dá para desfazer.", () -> { a.apagarSaves(g); toast("Save apagado"); })), lp());
        pageDialog(g.name, l, 6, null).show();
    }
    String shaderNome(String s) { return s == null || s.isEmpty() ? "Nenhum" : s.substring(s.lastIndexOf(47) + 1); }
    String shaderSub(MenuView.Game g) { return VideoSettings.shaderPadraoDoJogo(c, g.name) ? "Padrão (" + shaderNome(VideoSettings.shader) + ")" : "Só este jogo: " + shaderNome(VideoSettings.shaderNomeDoJogo(c, g.name)); }
    void escolherShader(final MenuView.Game g) { dismissAll(); final String PAD = "★ Usar o padrão (" + shaderNome(VideoSettings.shader) + ")"; final List<String> all = new ArrayList<>(); all.add(PAD); all.add(""); all.addAll(ShaderStore.listLocal(c)); VideoMenu vm = new VideoMenu(mv) { @Override void show() { gameWindow(g); } }; String cur = VideoSettings.shaderPadraoDoJogo(c, g.name) ? PAD : VideoSettings.shaderNomeDoJogo(c, g.name); vm.listDialog("Shader de " + g.name, all, cur, true, i -> { VideoSettings.shaderDoJogo(c, g.name, i == 0, i == 0 ? "" : all.get(i)); toast(i == 0 ? "Usando o shader padrão" : "Shader salvo só para este jogo"); gameWindow(g); }, null); }
    void estados(final MenuView.Game g) {
        final String b = a.stBaseFor(g.name);
        LinearLayout l = new HoverList(c);
        int n = 0;
        if (b != null) {
            a.stBase = b;
            SimpleDateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault());
            int[] sl = {101, 102, 103, MainActivity.RETOMAR, 1, 2, 3, 4, 5};
            for (final int slot : sl) {
                File f = new File(a.statePath(slot));
                if (!f.isFile()) continue;
                String t = slot == MainActivity.RETOMAR ? "Retomar (ao sair)" : slot > 100 ? "Automático " + (slot - 100) : "Manual " + slot;
                l.addView(row(thumb(slot), MenuView.Icons.LIB, slot > 100 ? 0xFFFFA000 : 0xFF1E6BE6, t, df.format(new Date(f.lastModified())), () -> { dismissAll(); a.skipResume = false; a.pendingSlot = slot; a.playGame(g); }), lp());
                n++;
            }
        }
        if (n == 0) { TextView e = mv.label("Nenhum estado salvo para este jogo.", 14, 0xFF4A5363, false); e.setPadding(dp(4), dp(8), dp(4), dp(8)); l.addView(e); }
        pageDialog("Estados salvos", l, Math.max(1, Math.min(n, 8)), null).show();
    }
    void ffPick() { int cur = 1; for (int i = 0; i < FF.length; i++) if (FF[i] == VideoSettings.ffSpeed) cur = i; String[] it = new String[FF.length]; for (int i = 0; i < FF.length; i++) it[i] = ffl(FF[i]); vmBack(this::options).choice("Avanço rápido", it, cur, i -> { VideoSettings.ffSpeed = FF[i]; VideoSettings.save(c); options(); }); }
    void audio() {
        LinearLayout l = new HoverList(c);
        l.setOrientation(LinearLayout.VERTICAL);
        vmBack(null).slider(l, "Volume", 0, 100, VideoSettings.volume, "pct", v -> VideoSettings.volume = v);
        l.addView(toggle("Som", !VideoSettings.mute, on -> VideoSettings.mute = !on), lp());
        final TextView bf = mv.btn("", 0x99FFFFFF, 0xFF1B1F27);
        bf.setText("Buffer de áudio: " + BUF[VideoSettings.abuf]);
        bf.setOnClickListener(v -> {
            VideoSettings.abuf = (VideoSettings.abuf + 1) % 3;
            bf.setText("Buffer de áudio: " + BUF[VideoSettings.abuf]);
        });
        l.addView(bf, lp());
        TextView hint = mv.label("Aumente o buffer se o som estiver chiando. Vale na próxima vez que o jogo voltar.", 11.5f, 0xFF6B7587, false);
        hint.setPadding(dp(4), 0, dp(4), 0);
        l.addView(hint);
        pageDialog("Configuração de áudio", l, 5, () -> VideoSettings.save(c)).show();
    }

    void control() {
        LinearLayout l = new HoverList(c);
        l.setOrientation(LinearLayout.VERTICAL);
        vmBack(null).slider(l, "Opacidade dos botões", 10, 100, VideoSettings.ctlAlpha, "pct", v -> VideoSettings.ctlAlpha = v);
        l.addView(toggle("Vibração ao tocar", VideoSettings.vibrate, on -> VideoSettings.vibrate = on), lp());
        TextView edit = mv.btn("Editar layout de tela e controle", 0xFF7E57C2, Color.WHITE);
        edit.setOnClickListener(v -> { dismissAll(); a.startLayoutEdit(); });
        l.addView(edit, lp());
        pageDialog("Configuração de controle", l, 4, () -> VideoSettings.save(c)).show();
    }

    // ================= configuração de saves =================
    static final int[] AUTO_S = {30, 60, 120, 300};
    static final String[] AUTO_L = {"30 segundos", "1 minuto", "2 minutos", "5 minutos"};
    Dialog savesD;

    void saves() {
        int cur = 1;
        for (int i = 0; i < AUTO_S.length; i++) if (AUTO_S[i] == VideoSettings.autoSecs) cur = i;
        final int curA = cur;
        LinearLayout l = new HoverList(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(toggle("Salvamento automático", VideoSettings.autoOn, on -> VideoSettings.autoOn = on), lp());
        l.addView(row(MenuView.Icons.ADV, 0xFFFFA000, "Intervalo do automático", AUTO_L[curA] + " (3 slots em rodízio)", () -> {
            savesD.dismiss();
            vmBack(this::saves).choice("Intervalo do automático", AUTO_L, curA, i -> {
                VideoSettings.autoSecs = AUTO_S[i];
                VideoSettings.save(c);
                saves();
            });
        }), lp());
        l.addView(toggle("Retomar de onde parou", VideoSettings.resume, on -> {
            VideoSettings.resume = on;
            if (!on) Pastas.apagarSaves(c, true);
        }), lp());
        l.addView(row(MenuView.Icons.HOME, 0xFFE53935, "Apagar automáticos e retomar",
                "De todos os jogos (os manuais ficam)", () ->
                confirm("Apagar salvamentos", "Os estados automáticos e o ponto de retomar de todos os jogos serão apagados. Os 5 manuais e o save normal do jogo não são tocados.", () -> {
                    int n = Pastas.apagarSaves(c, false);
                    toast(n > 0 ? "Apagado" : "Nada para apagar");
                })), lp());
        TextView hint = mv.label("Os estados ficam em GBA-MAX/Saves e o save normal do jogo (.sav) em GBA-MAX/Data.", 11.5f, 0xFF6B7587, false);
        hint.setPadding(dp(4), dp(4), dp(4), 0);
        l.addView(hint);
        savesD = pageDialog("Configuração de saves", l, 5, () -> VideoSettings.save(c));
        savesD.show();
    }

    void advanced() {
        int cur = 2;
        for (int i = 0; i < FF.length; i++) if (FF[i] == VideoSettings.ffSpeed) cur = i;
        final int curFf = cur;
        LinearLayout l = new HoverList(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(row(MenuView.Icons.ADV, 0xFF1E6BE6, "Avanço rápido", ffl(VideoSettings.ffSpeed), () -> {
            advD.dismiss();
            String[] it = new String[FF.length];
            for (int i = 0; i < FF.length; i++) it[i] = ffl(FF[i]);
            vmBack(this::advanced).choice("Avanço rápido", it, curFf, i -> {
                VideoSettings.ffSpeed = FF[i];
                VideoSettings.save(c);
                advanced();
            });
        }), lp());
        l.addView(row(MenuView.Icons.SCREEN, 0xFFFFA000, "Pulo de quadros",
                VideoSettings.frameskip == 0 ? "Desligado" : String.valueOf(VideoSettings.frameskip), () -> {
            advD.dismiss();
            final VideoMenu vm = vmBack(this::advanced);
            vm.choice("Pulo de quadros", new String[]{"Desligado", "1 quadro", "2 quadros", "3 quadros", "4 quadros"},
                    VideoSettings.frameskip, i -> {
                        VideoSettings.frameskip = i;
                        vm.changed();
                        advanced();
                    });
        }), lp());
        l.addView(row(MenuView.Icons.HOME, 0xFFE53935, "Reiniciar jogo", "Volta ao começo (sem apagar saves)", () ->
                confirm("Reiniciar jogo", "O progresso que não foi salvo será perdido.", () -> {
                    a.resetGame();
                    closeAll();
                })), lp());
        l.addView(row(MenuView.Icons.FACE, 0xFF7E57C2, "Personalizar", "Botões, fundo do app e abertura", () -> {
            advD.dismiss();
            Personalizar.show(a);
        }), lp());
        advD = pageDialog("Avançado", l, 4, null);
        advD.show();
    }

    void confirm(String title, String msg, final Runnable yes) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(mv.label(title, 18, 0xFF1B1F27, true));
        TextView m = mv.label(msg, 14, 0xFF4A5363, false);
        m.setPadding(0, dp(8), 0, dp(14));
        box.addView(m);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView no = mv.btn("Cancelar", 0xFFE3E7EE, 0xFF1B1F27);
        TextView ok = mv.btn("Confirmar", 0xFFE53935, Color.WHITE);
        row.addView(no, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(0, -2, 1f);
        ol.leftMargin = dp(8);
        row.addView(ok, ol);
        box.addView(row);
        final Dialog d = mv.glassDialog(box);
        track(d, null);
        no.setOnClickListener(v -> d.dismiss());
        ok.setOnClickListener(v -> { d.dismiss(); yes.run(); });
        d.show();
    }

    // ================= cores com prévia ao vivo =================
    void updateShaderLabel() {
        if (shaderTv == null) return;
        String sh = VideoSettings.shader;
        String n = sh == null || sh.isEmpty() ? "Nenhum" : sh.substring(sh.lastIndexOf('/') + 1);
        shaderTv.setText("Shader: " + n + "   ›");
    }

    void removeCoresViews() {
        if (panel != null && panel.getParent() == a.root) a.root.removeView(panel);
        if (frame != null && frame.getParent() == a.root) a.root.removeView(frame);
        panel = null;
        frame = null;
        coresOpen = false;
    }

    void closeCores() {
        removeCoresViews();
        VideoSettings.save(c);
        a.stop();
        a.gv.recalc();
        if (main != null) main.show();
    }

    void openCores() {
        if (coresOpen) return;
        if (main != null) main.hide();
        coresOpen = true;
        a.start(); // o jogo roda enquanto você mexe nas cores
        final FrameLayout root = a.root;
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int W = root.getWidth() > 0 ? root.getWidth() : dm.widthPixels;
        int H = root.getHeight() > 0 ? root.getHeight() : dm.heightPixels;
        boolean land = W > H;
        int m = dp(10);
        int pw = (int) Math.min(W * (land ? .36f : .52f), dp(280));
        int ph = pw * 2 / 3;
        int pl = W - m - pw, pt = m;

        // janela pequena com a imagem do jogo (canto superior direito)
        a.gl.setRect(pl, pt, pl + pw, pt + ph);
        frame = new View(c);
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(0);
        fg.setStroke(dp(2), 0xFFFFFFFF);
        fg.setCornerRadius(dp(4));
        frame.setBackground(fg);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(pw, ph, Gravity.TOP | Gravity.LEFT);
        fl.leftMargin = pl;
        fl.topMargin = pt;
        root.addView(frame, fl);

        // painel
        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(mv.glass(dp(22)));
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));
        panel.setElevation(dp(10));
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, dp(8));
        head.addView(mv.label("Cores", 18, 0xFF1B1F27, true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView done = mv.btn("Concluir", 0xFF2EAD4B, Color.WHITE);
        done.setPadding(dp(16), dp(8), dp(16), dp(8));
        head.addView(done);
        panel.addView(head);

        LinearLayout in = new LinearLayout(c);
        in.setOrientation(LinearLayout.VERTICAL);
        shaderTv = mv.btn("", 0x99FFFFFF, 0xFF1B1F27);
        shaderTv.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        shaderTv.setSingleLine(true);
        shaderTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        updateShaderLabel();
        shaderTv.setOnClickListener(v -> new VideoMenu(mv) {
            @Override void show() { updateShaderLabel(); }
        }.pickShader());
        in.addView(shaderTv, lp());
        VideoMenu vm = vmBack(null);
        vm.slider(in, "Brilho", -100, 100, VideoSettings.bright, "sign", v -> { VideoSettings.bright = v; a.gl.requestRender(); });
        vm.slider(in, "Contraste", 0, 200, VideoSettings.contrast, "pct", v -> { VideoSettings.contrast = v; a.gl.requestRender(); });
        vm.slider(in, "Saturação", 0, 200, VideoSettings.sat, "pct", v -> { VideoSettings.sat = v; a.gl.requestRender(); });
        vm.slider(in, "Gamma", 50, 200, VideoSettings.gamma, "gamma", v -> { VideoSettings.gamma = v; a.gl.requestRender(); });
        vm.slider(in, "LCD opacidade", 0, 100, VideoSettings.lcd, "pct", v -> { VideoSettings.lcd = v; a.gl.requestRender(); });
        TextView rs = mv.btn("Restaurar cores", 0xFFE3E7EE, 0xFF1B1F27);
        in.addView(rs, lp());
        rs.setOnClickListener(v -> {
            VideoSettings.resetColors();
            VideoSettings.save(c);
            removeCoresViews();
            openCores();
        });
        ScrollView sv = new ScrollView(c);
        sv.addView(in);
        FrameLayout.LayoutParams pl2;
        if (land) {
            panel.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
            pl2 = new FrameLayout.LayoutParams(W - pw - 3 * m, -1, Gravity.LEFT);
        } else {
            panel.addView(sv, new LinearLayout.LayoutParams(-1, (int) (H * .42f)));
            pl2 = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        }
        pl2.setMargins(m, m, m, m);
        root.addView(panel, pl2);
        done.setOnClickListener(v -> closeCores());
    }
}
