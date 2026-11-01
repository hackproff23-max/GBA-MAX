package org.gbamax.emu;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cheats do jogo: lista com ligar/desligar, adicionar, editar, excluir e colar lista.
 * Guarda em GBA-MAX/Cheats/<jogo>.cht, uma linha por cheat:  Nome=CODIGO
 * (várias linhas de código do mesmo cheat separadas por +). Linha com # na frente = desligado.
 * Os códigos (GameShark, Action Replay, CodeBreaker) são reconhecidos pelo núcleo mGBA.
 */
class CheatMenu {
    static class Cheat {
        String nome = "", codigo = "";
        boolean on = true;
    }

    final GameMenu g;
    final Context c;
    final String jogo;
    final boolean noJogo; // true = jogo carregado e pausado: aplica no núcleo ao fechar
    List<Cheat> lista = new ArrayList<>();
    LinearLayout rows;
    boolean mudou;

    CheatMenu(GameMenu g, String jogo, boolean noJogo) {
        this.g = g;
        this.c = g.c;
        this.jogo = jogo;
        this.noJogo = noJogo;
    }

    // ================= arquivo =================
    static File arquivo(Context c, String jogo) {
        File d = Pastas.dir(c, Pastas.CHEATS);
        return d == null ? null : new File(d, jogo + ".cht");
    }

    static String limpaNome(String s) {
        if (s == null) return "";
        s = s.replace("=", "-").replace("\n", " ").trim();
        s = s.replaceAll("^[\\[#;/\\s\"']+", "").replaceAll("[\\]\\s\"']+$", "");
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    /**
     * Normaliza um ou mais códigos (um por linha ou separados por +).
     * Aceita 10 a 16 dígitos hexa por linha, com espaço, ':' ou '-' no meio.
     * Devolve "AAAAAAAA BBBBBBBB+CCCCCCCC DDDD" ou null se algo não for código.
     */
    static String normalizar(String raw) {
        if (raw == null) return null;
        StringBuilder out = new StringBuilder();
        for (String p : raw.replace("\r", "").split("[\n+]")) {
            String s = p.trim();
            if (s.isEmpty()) continue;
            if (!s.matches("[0-9A-Fa-f\\s:\\-]+")) return null;
            String d = s.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
            if (d.length() < 10 || d.length() > 16) return null;
            if (out.length() > 0) out.append('+');
            out.append(d, 0, 8).append(' ').append(d, 8, d.length());
        }
        return out.length() == 0 ? null : out.toString();
    }

    static List<Cheat> ler(Context c, String jogo) {
        List<Cheat> r = new ArrayList<>();
        File f = arquivo(c, jogo);
        if (f == null || !f.isFile()) return r;
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(f)) {
                byte[] t = new byte[4096];
                int k;
                while ((k = in.read(t)) != -1) o.write(t, 0, k);
            }
            for (String linha : o.toString("UTF-8").replace("\r", "").split("\n")) {
                String s = linha.trim();
                if (s.isEmpty()) continue;
                boolean on = true;
                if (s.startsWith("#") || s.startsWith(";") || s.startsWith("//")) {
                    s = s.replaceFirst("^(#+|;+|//+)\\s*", "");
                    on = false;
                }
                int eq = s.indexOf('=');
                String nome = eq >= 0 ? limpaNome(s.substring(0, eq)) : "";
                String cod = normalizar(eq >= 0 ? s.substring(eq + 1) : s);
                if (cod == null) continue; // comentário ou lixo
                Cheat ch = new Cheat();
                ch.nome = nome.isEmpty() ? "Cheat " + (r.size() + 1) : nome;
                ch.codigo = cod;
                ch.on = on;
                r.add(ch);
            }
        } catch (Throwable t) { }
        return r;
    }

    static boolean gravar(Context c, String jogo, List<Cheat> l) {
        File f = arquivo(c, jogo);
        if (f == null) return false;
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8")) {
            w.write("# Cheats de " + jogo + "\n");
            w.write("# Formato: Nome=CODIGO (varias linhas do mesmo cheat separadas por +). # na frente = desligado.\n");
            for (Cheat ch : l) w.write((ch.on ? "" : "#") + limpaNome(ch.nome) + "=" + ch.codigo + "\n");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean algumLigado(Context c, String jogo) {
        for (Cheat ch : ler(c, jogo)) if (ch.on) return true;
        return false;
    }

    static void desligarTodos(Context c, String jogo) {
        List<Cheat> l = ler(c, jogo);
        for (Cheat ch : l) ch.on = false;
        gravar(c, jogo, l);
    }

    /** Entende texto colado: formato RetroArch (.cht), "Nome=CODIGO" ou blocos "nome + linhas de código". */
    static List<Cheat> interpretar(String t) {
        List<Cheat> r = new ArrayList<>();
        if (t == null) return r;
        t = t.replace("\r", "");
        if (t.contains("cheat") && t.contains("_code")) {
            Map<Integer, String[]> m = new TreeMap<>();
            Matcher mt = Pattern.compile("cheat(\\d+)_(desc|code|enable)\\s*=\\s*\"?([^\"\\n]*)\"?").matcher(t);
            while (mt.find()) {
                int i = Integer.parseInt(mt.group(1));
                String[] a = m.get(i);
                if (a == null) { a = new String[3]; m.put(i, a); }
                String k = mt.group(2);
                a[k.equals("desc") ? 0 : k.equals("code") ? 1 : 2] = mt.group(3).trim();
            }
            for (String[] a : m.values()) {
                String n = normalizar(a[1]);
                if (n == null) continue;
                Cheat ch = new Cheat();
                ch.nome = limpaNome(a[0]);
                ch.codigo = n;
                ch.on = a[2] != null && a[2].equals("true");
                r.add(ch);
            }
        } else {
            Cheat cur = null;
            for (String linha : t.split("\n")) {
                String s = linha.trim();
                if (s.isEmpty()) {
                    if (cur != null && !cur.codigo.isEmpty()) { r.add(cur); cur = null; }
                    continue;
                }
                int eq = s.indexOf('=');
                if (eq > 0) {
                    String n = normalizar(s.substring(eq + 1));
                    if (n != null) {
                        if (cur != null && !cur.codigo.isEmpty()) r.add(cur);
                        Cheat ch = new Cheat();
                        ch.nome = limpaNome(s.substring(0, eq));
                        ch.codigo = n;
                        r.add(ch);
                        cur = null;
                        continue;
                    }
                }
                String n = normalizar(s);
                if (n != null) {
                    if (cur == null) cur = new Cheat();
                    cur.codigo = cur.codigo.isEmpty() ? n : cur.codigo + "+" + n;
                } else {
                    if (cur != null && !cur.codigo.isEmpty()) { r.add(cur); cur = null; }
                    if (cur == null) cur = new Cheat();
                    cur.nome = limpaNome(s);
                }
            }
            if (cur != null && !cur.codigo.isEmpty()) r.add(cur);
        }
        int k = 0;
        for (Cheat ch : r) {
            k++;
            if (ch.nome.isEmpty()) ch.nome = "Cheat " + k;
        }
        return r;
    }

    static String resumo(String cod) {
        String[] p = cod.split("\\+");
        return p[0] + (p.length > 1 ? "  +" + (p.length - 1) + " linha(s)" : "");
    }

    // ================= interface =================
    void show() {
        lista = ler(c, jogo);
        mudou = false;
        LinearLayout l = g.new HoverList(c);
        l.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(g.mv.new DockBtn(c, MenuView.Icons.CHEAT, 0xFFFFA000, Color.WHITE, false),
                new LinearLayout.LayoutParams(g.dp(46), g.dp(46)));
        TextView dica = g.mv.label("Toque para ligar ou desligar.\nSegure para editar ou excluir.", 12, 0xFF4A5363, false);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, -2, 1f);
        dl.leftMargin = g.dp(8);
        head.addView(dica, dl);
        l.addView(head, g.lp());

        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView add = g.mv.btn("Adicionar", 0xFF2EAD4B, Color.WHITE);
        TextView colar = g.mv.btn("Colar lista", 0xFF1E6BE6, Color.WHITE);
        btns.addView(add, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.leftMargin = g.dp(8);
        btns.addView(colar, cl);
        l.addView(btns, g.lp());
        TextView baixar = g.mv.btn("Baixar cheats de todos os jogos (PT-BR)", 0xFF7E57C2, Color.WHITE); l.addView(baixar, g.lp()); baixar.setOnClickListener(v -> new CheatBaixar(g).iniciar(() -> { lista = ler(c, jogo); preencher(); mudou = true; }));
        TextView txt = g.mv.btn("Importar arquivo TXT", 0xFF00897B, Color.WHITE); l.addView(txt, g.lp()); txt.setOnClickListener(v -> { g.a.cheatPend = this; g.a.pickFiles(9, false, "text/plain", "application/octet-stream"); });

        rows = new LinearLayout(c);
        rows.setOrientation(LinearLayout.VERTICAL);
        l.addView(rows);
        preencher();

        add.setOnClickListener(v -> editar(null));
        colar.setOnClickListener(v -> colar());
        g.pageDialog("Cheats", l, 9, () -> {
            if (noJogo && mudou) g.a.aplicarCheats(jogo);
        }).show();
    }

    void salvar() {
        if (!gravar(c, jogo, lista)) g.toast("Não consegui gravar o arquivo de cheats");
        mudou = true;
    }

    void preencher() {
        rows.removeAllViews();
        if (lista.isEmpty()) {
            TextView e = g.mv.label("Nenhum cheat ainda. Toque em Adicionar ou cole uma lista copiada.", 13, 0xFF4A5363, false);
            e.setPadding(g.dp(6), g.dp(10), g.dp(6), g.dp(10));
            rows.addView(e);
            return;
        }
        for (final Cheat ch : lista) {
            String sub = (ch.on ? "Ligado" : "Desligado") + " · " + resumo(ch.codigo);
            LinearLayout row = g.row(MenuView.Icons.CHEAT, ch.on ? 0xFF2EAD4B : 0xFF9AA3B2, ch.nome, sub, () -> {
                ch.on = !ch.on;
                salvar();
                preencher();
            });
            row.setOnLongClickListener(v -> { editar(ch); return true; });
            rows.addView(row, g.lp());
        }
    }

    EditText campo(String dica, boolean multi) {
        EditText e = new EditText(c);
        e.setHint(dica);
        e.setTextSize(14);
        e.setTextColor(0xFF1B1F27);
        e.setHintTextColor(0xFF8A93A3);
        e.setTypeface(multi ? Typeface.MONOSPACE : Typeface.DEFAULT);
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            e.setMinLines(3);
            e.setMaxLines(6);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setSingleLine(true);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99FFFFFF);
        bg.setCornerRadius(g.dp(12));
        bg.setStroke(g.dp(1), 0xCCFFFFFF);
        e.setBackground(bg);
        e.setPadding(g.dp(12), g.dp(10), g.dp(12), g.dp(10));
        return e;
    }

    /** alvo == null: novo cheat; senão edita (com botão Excluir). */
    void editar(final Cheat alvo) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(g.mv.label(alvo == null ? "Novo cheat" : "Editar cheat", 18, 0xFF1B1F27, true));
        final EditText nome = campo("Nome (ex.: Vida infinita)", false);
        final EditText cod = campo("Código, um por linha. Serve GameShark, Action Replay e CodeBreaker.", true);
        if (alvo != null) {
            nome.setText(alvo.nome);
            cod.setText(alvo.codigo.replace("+", "\n"));
        }
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
        nl.topMargin = g.dp(10);
        box.addView(nome, nl);
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(-1, -2);
        ql.topMargin = g.dp(8);
        box.addView(cod, ql);

        LinearLayout bt = new LinearLayout(c);
        bt.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancelar = g.mv.btn("Cancelar", 0xFFE3E7EE, 0xFF1B1F27);
        TextView ok = g.mv.btn("Salvar", 0xFF2EAD4B, Color.WHITE);
        bt.addView(cancelar, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(0, -2, 1f);
        ol.leftMargin = g.dp(8);
        bt.addView(ok, ol);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = g.dp(12);
        box.addView(bt, bl);

        TextView exc = null;
        if (alvo != null) {
            exc = g.mv.btn("Excluir cheat", 0xFFE53935, Color.WHITE);
            LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-1, -2);
            el.topMargin = g.dp(8);
            box.addView(exc, el);
        }

        final Dialog d = g.mv.glassDialog(box);
        Window w = d.getWindow();
        if (w != null) w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        g.track(d, null);
        cancelar.setOnClickListener(v -> d.dismiss());
        ok.setOnClickListener(v -> {
            String n = normalizar(cod.getText().toString());
            if (n == null) {
                g.toast("Código inválido. Use linhas como 82003100 0063 ou 82003100 00000063");
                return;
            }
            String nm = limpaNome(nome.getText().toString());
            if (nm.isEmpty()) nm = "Cheat " + (lista.size() + 1);
            if (alvo == null) {
                Cheat ch = new Cheat();
                ch.nome = nm;
                ch.codigo = n;
                ch.on = true;
                lista.add(ch);
            } else {
                alvo.nome = nm;
                alvo.codigo = n;
            }
            salvar();
            preencher();
            d.dismiss();
        });
        if (exc != null) {
            exc.setOnClickListener(v -> g.confirm("Excluir cheat", "Excluir \"" + alvo.nome + "\"?", () -> {
                lista.remove(alvo);
                salvar();
                preencher();
                d.dismiss();
            }));
        }
        d.show();
    }

    void colar() {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData cd = cm == null ? null : cm.getPrimaryClip();
            CharSequence tx = cd != null && cd.getItemCount() > 0 ? cd.getItemAt(0).coerceToText(c) : null;
            List<Cheat> novos = interpretar(tx == null ? null : tx.toString());
            int add = 0;
            for (Cheat ch : novos) {
                boolean dup = false;
                for (Cheat x : lista) if (x.codigo.equals(ch.codigo)) dup = true;
                if (!dup) { lista.add(ch); add++; }
            }
            if (add == 0) {
                g.toast(novos.isEmpty() ? "Não achei cheats no que foi copiado" : "Esses cheats já estão na lista");
                return;
            }
            salvar();
            preencher();
            g.toast(add + " cheat(s) adicionado(s)");
        } catch (Throwable t) {
            g.toast("Não consegui colar");
        }
    }
}
