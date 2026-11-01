package org.gbamax.emu;

import android.app.Dialog;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Baixa os cheats do acervo libretro-database só dos jogos da biblioteca e traduz os nomes para PT-BR. */
class CheatBaixar {
    static final String API = "https://api.github.com/repos/libretro/libretro-database/";
    static final String PASTA = "Nintendo - Game Boy Advance";
    static final String RAW = "https://raw.githubusercontent.com/libretro/libretro-database/master/cht/";

    final GameMenu g;
    final MainActivity a;
    volatile boolean cancel, rodando;
    final List<String> names = new ArrayList<>();
    final Map<String, List<String>> byKey = new HashMap<>();
    final Map<String, String> byFull = new HashMap<>();
    final Map<String, java.util.Set<String>> toks = new HashMap<>();
    TextView st, info, bt;
    ProgressBar pb;

    CheatBaixar(GameMenu g) { this.g = g; this.a = g.a; }

    void ui(Runnable r) { a.runOnUiThread(r); }

    void prog(final int p, final String s, final String i) { ui(() -> { pb.setProgress(p); st.setText(s); info.setText(i); }); }

    void iniciar(final Runnable fim) {
        final List<MenuView.Game> jogos = new ArrayList<>(g.mv.games);
        if (jogos.isEmpty()) { g.toast("Nenhum jogo na lista."); return; }
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(g.mv.label("Cheats em português", 18, 0xFF1B1F27, true));
        st = g.mv.label("Iniciando...", 13, 0xFF4A5363, false);
        st.setPadding(0, g.dp(8), 0, g.dp(8));
        st.setSingleLine(true);
        st.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        box.addView(st);
        pb = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        box.addView(pb, new LinearLayout.LayoutParams(-1, g.dp(12)));
        info = g.mv.label("", 13, 0xFF1B1F27, true);
        info.setPadding(0, g.dp(10), 0, g.dp(14));
        box.addView(info);
        bt = g.mv.btn("Cancelar", 0xFFE3E7EE, 0xFF1B1F27);
        box.addView(bt);
        final Dialog d = g.mv.glassDialog(box);
        d.setCancelable(false);
        g.track(d, null);
        bt.setOnClickListener(v -> { if (rodando) { cancel = true; bt.setText("Cancelando..."); } else d.dismiss(); });
        d.show();
        rodando = true;
        new Thread(() -> {
            try { executar(jogos); } catch (Throwable t) { prog(0, "Erro", String.valueOf(t.getMessage())); }
            rodando = false;
            ui(() -> { bt.setText("Fechar"); if (fim != null) fim.run(); });
        }).start();
    }

    void executar(List<MenuView.Game> jogos) {
        try { carregarLista(); } catch (Throwable t) { prog(0, "Não consegui acessar a lista de cheats", "Verifique a internet e tente de novo."); return; }
        final File cd = Pastas.capas(a); prog(1, "Lendo as ROMs...", ""); RomId.garantirDat(cd, true);
        Map<String, List<CheatMenu.Cheat>> achados = new LinkedHashMap<>();
        int sem = 0, tinha = 0, n = jogos.size(), i = 0;
        for (MenuView.Game gm : jogos) {
            if (cancel) break;
            i++;
            prog(i * 55 / n, gm.name, i + "/" + n);
            String jogo = Pastas.seguro(gm.name);
            if (!CheatMenu.ler(a, jogo).isEmpty()) { tinha++; continue; }
            String nome = achar(gm.name); RomId.Info ri = RomId.info(a.getContentResolver(), gm.uri, cd); if (ri != null) { String of = RomId.oficial(ri, gm.name); String viaRom = of != null ? porNome(of) : null; if (viaRom != null) nome = viaRom; else if (nome == null) { String base = RomId.baseMesmoTamanho(ri); nome = base != null ? porNome(base) : null; } }
            if (nome == null) { sem++; continue; }
            try {
                String txt = CoverFetcher.httpText(RAW + CoverFetcher.enc(PASTA) + "/" + CoverFetcher.enc(nome) + ".cht");
                List<CheatMenu.Cheat> l = CheatMenu.interpretar(txt);
                if (l.isEmpty()) { sem++; continue; }
                for (CheatMenu.Cheat ch : l) ch.on = false;
                achados.put(jogo, l);
            } catch (Throwable t) { sem++; }
        }
        if (cancel) { prog(0, "Cancelado", "Nada foi gravado."); return; }
        Set<String> uniq = new LinkedHashSet<>();
        for (List<CheatMenu.Cheat> l : achados.values()) for (CheatMenu.Cheat ch : l) uniq.add(ch.nome);
        Map<String, String> tr = traduzir(new ArrayList<>(uniq));
        if (cancel) { prog(0, "Cancelado", "Nada foi gravado."); return; }
        int ok = 0;
        for (Map.Entry<String, List<CheatMenu.Cheat>> e : achados.entrySet()) {
            for (CheatMenu.Cheat ch : e.getValue()) { String t = tr.get(ch.nome); if (t != null && !t.isEmpty()) ch.nome = CheatMenu.limpaNome(t); }
            if (CheatMenu.gravar(a, e.getKey(), e.getValue())) ok++;
        }
        String aviso = !uniq.isEmpty() && tr.isEmpty() ? " (não consegui traduzir: nomes em inglês)" : "";
        prog(100, "Concluído", ok + " jogo(s) com cheats" + aviso + " · " + sem + " sem cheats no acervo · " + tinha + " já tinham");
    }

    void carregarLista() throws Exception {
        File f = new File(a.getFilesDir(), "cht_lista.txt");
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > CoverFetcher.MAX_AGE) {
            String sha = null;
            JSONArray dirs = new JSONArray(CoverFetcher.httpText(API + "contents/cht"));
            for (int i = 0; i < dirs.length(); i++) { JSONObject o = dirs.getJSONObject(i); if (PASTA.equals(o.optString("name"))) sha = o.optString("sha"); }
            if (sha != null) {
                JSONArray t = new JSONObject(CoverFetcher.httpText(API + "git/trees/" + sha)).getJSONArray("tree");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < t.length(); i++) { String p = t.getJSONObject(i).optString("path"); if (p.endsWith(".cht")) sb.append(p, 0, p.length() - 4).append("\n"); }
                if (sb.length() > 0) CoverFetcher.writeText(f, sb.toString());
            }
        }
        if (!f.exists()) throw new IOException("lista indisponivel");
        for (String s : CoverFetcher.readLines(f)) add(s);
    }

    void add(String n) {
        if (n.isEmpty()) return;
        names.add(n);
        String k = CoverFetcher.key(n);
        List<String> l = byKey.get(k);
        if (l == null) { l = new ArrayList<>(); byKey.put(k, l); }
        l.add(n);
        byFull.put(CoverFetcher.fullKey(n), n);
        toks.put(n, CoverFetcher.words(n));
    }

    /** Acha o arquivo de cheats do jogo: nome igual ou quase igual (nada de adivinhar jogo parecido). */
    String porNome(String n) { String h = byFull.get(CoverFetcher.fullKey(n)); if (h != null) return h; List<String> l = byKey.get(CoverFetcher.key(n)); return l != null ? CoverFetcher.best(l) : null; }
    String achar(String jogo) {
        String base = CoverFetcher.clean(jogo);
        String h = byFull.get(CoverFetcher.fullKey(base));
        if (h != null) return h;
        List<String> l = byKey.get(CoverFetcher.key(base));
        if (l != null) return CoverFetcher.best(l);
        Set<String> f = CoverFetcher.words(base);
        if (f.isEmpty()) return null;
        String best = null;
        double bs = 0;
        int bl = 0;
        for (String n : names) {
            Set<String> t = toks.get(n);
            if (t == null || t.isEmpty() || t.size() > f.size() + 1) continue;
            int common = 0;
            for (String w : f) if (t.contains(w)) common++;
            double sc = (double) common / f.size();
            if (common == 0 || sc < .85) continue;
            if (sc > bs || (sc == bs && t.size() < bl)) { bs = sc; best = n; bl = t.size(); }
        }
        return best;
    }

    Map<String, String> traduzir(List<String> pend) {
        Map<String, String> tr = new HashMap<>();
        int i = 0, n = pend.size(), falhas = 0;
        while (i < n && !cancel) {
            StringBuilder sb = new StringBuilder();
            int j = i;
            while (j < n && j - i < 50 && sb.length() + pend.get(j).length() < 2500) {
                if (j > i) sb.append("\n");
                sb.append(pend.get(j));
                j++;
            }
            if (j == i) { sb.append(pend.get(i)); j = i + 1; }
            String[] out = null;
            try { out = gt(sb.toString()); falhas = 0; } catch (Throwable t) { falhas++; }
            if (out != null && out.length == j - i) {
                for (int k = 0; k < out.length; k++) tr.put(pend.get(i + k), out[k].trim());
            } else if (falhas < 3) {
                for (int k = i; k < j && !cancel; k++) {
                    try { String[] o = gt(pend.get(k)); if (o.length == 1) tr.put(pend.get(k), o[0].trim()); } catch (Throwable t) { falhas++; }
                }
            }
            if (falhas >= 3) break;
            i = j;
            prog(55 + i * 40 / n, "Traduzindo os nomes...", i + "/" + n);
        }
        return tr;
    }

    static String[] gt(String q) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://translate.googleapis.com/translate_a/single").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile)");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        byte[] body = ("client=gtx&sl=en&tl=pt&dt=t&q=" + URLEncoder.encode(q, "UTF-8")).getBytes("UTF-8");
        try (OutputStream o = c.getOutputStream()) { o.write(body); }
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        try (InputStream in = c.getInputStream()) { byte[] t = new byte[8192]; int k; while ((k = in.read(t)) != -1) bo.write(t, 0, k); }
        c.disconnect();
        JSONArray seg = new JSONArray(bo.toString("UTF-8")).getJSONArray(0);
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < seg.length(); i++) { JSONArray s = seg.optJSONArray(i); if (s != null && !s.isNull(0)) r.append(s.getString(0)); }
        return r.toString().split("\n", -1);
    }
}
