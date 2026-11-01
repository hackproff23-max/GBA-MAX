package org.gbamax.emu;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.View;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Pasta dos jogos: gerenciador de arquivos simples, GBA-MAX/pasta_jogos.json e filtro dos .zip que são jogos. */
final class Pasta {
    interface Ok { void on(File f); }

    static final Map<String, Boolean> cache = Collections.synchronizedMap(new HashMap<String, Boolean>());

    static File arq() { return new File(Pastas.raiz(), "pasta_jogos.json"); }

    /** Caminho salvo no json (se a pasta ainda existir), senão null. */
    static String ler(Context c) {
        try {
            if (!Pastas.temAcesso(c)) return null;
            File f = arq();
            if (!f.isFile()) return null;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(f)) {
                byte[] t = new byte[4096];
                int k;
                while ((k = in.read(t)) != -1) b.write(t, 0, k);
            }
            String p = new JSONObject(b.toString("UTF-8")).optString("pasta", "");
            return !p.isEmpty() && new File(p).isDirectory() ? p : null;
        } catch (Throwable t) { return null; }
    }

    static File atual(Context c) {
        String p = ler(c);
        return p != null ? new File(p) : Pastas.raiz();
    }

    static boolean salvar(Context c, File pasta) {
        try {
            if (!Pastas.criar(c)) return false;
            JSONObject o = new JSONObject();
            o.put("pasta", pasta.getAbsolutePath());
            try (OutputStream w = new FileOutputStream(arq())) { w.write(o.toString(2).getBytes("UTF-8")); }
            return true;
        } catch (Throwable t) { return false; }
    }

    // ================= filtro: só .zip que tem um jogo dentro =================
    static boolean cabecalhoGba(InputStream in) throws IOException {
        byte[] h = new byte[0xC0];
        int off = 0, k;
        while (off < h.length && (k = in.read(h, off, h.length - off)) > 0) off += k;
        return off >= h.length && (h[0xB2] & 0xFF) == 0x96;
    }

    static boolean zipTemJogo(Context c, Uri u) {
        try {
            if ("file".equals(u.getScheme())) {
                File f = new File(u.getPath());
                String chave = f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
                Boolean antes = cache.get(chave);
                if (antes != null) return antes;
                boolean r = false;
                try (ZipFile z = new ZipFile(f)) {
                    Enumeration<? extends ZipEntry> en = z.entries();
                    while (en.hasMoreElements() && !r) {
                        ZipEntry e = en.nextElement();
                        if (e.isDirectory()) continue;
                        String n = e.getName().toLowerCase(Locale.ROOT);
                        if (n.endsWith(".gba") || n.endsWith(".agb")) r = true;
                        else if (n.endsWith(".bin")) { try (InputStream in = z.getInputStream(e)) { r = cabecalhoGba(in); } }
                    }
                }
                cache.put(chave, r);
                return r;
            }
            try (ZipInputStream zi = new ZipInputStream(c.getContentResolver().openInputStream(u))) {
                ZipEntry e;
                while ((e = zi.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String n = e.getName().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".gba") || n.endsWith(".agb")) return true;
                    if (n.endsWith(".bin") && cabecalhoGba(zi)) return true;
                }
            }
        } catch (Throwable t) { }
        return false;
    }

    // ================= gerenciador de arquivos simples =================
    static List<File> raizes(Context c) {
        List<File> l = new ArrayList<>();
        l.add(Environment.getExternalStorageDirectory());
        try {
            for (File d : c.getExternalFilesDirs(null)) {
                if (d == null) continue;
                String s = d.getAbsolutePath();
                int i = s.indexOf("/Android/data");
                if (i > 0) {
                    File r = new File(s.substring(0, i));
                    boolean dup = false;
                    for (File x : l) if (x.equals(r)) dup = true;
                    if (!dup) l.add(r);
                }
            }
        } catch (Throwable t) { }
        return l;
    }

    static boolean dentro(File f, List<File> rs) {
        String s = f.getAbsolutePath();
        for (File r : rs) if (s.startsWith(r.getAbsolutePath() + "/")) return true;
        return false;
    }

    static TextView linha(MenuView mv, String t, final Runnable r) {
        TextView v = mv.label(t, 15, Tema.get(Tema.TEXTO), true);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setPadding(mv.dp(14), mv.dp(12), mv.dp(14), mv.dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Tema.get(Tema.LINHA));
        g.setCornerRadius(mv.dp(14));
        g.setStroke(mv.dp(1), 0xCCFFFFFF);
        v.setBackground(g);
        v.setOnClickListener(x -> r.run());
        return v;
    }

    static void addRow(LinearLayout l, MenuView mv, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = mv.dp(6);
        l.addView(v, lp);
    }

    static void escolher(final MainActivity a, final Ok ok) {
        if (!Pastas.temAcesso(a)) {
            a.toast("Permita o acesso a todos os arquivos para escolher a pasta");
            a.askStorage();
            return;
        }
        final MenuView mv = a.mv;
        final LinearLayout box = Personalizar.caixa(mv, a, "Pasta dos jogos");
        final Dialog d = Personalizar.janela(mv, a, box);
        final File[] cur = {atual(a)};
        final List<File> rs = raizes(a);
        final Ok[] nav = new Ok[1];

        final TextView caminho = mv.label("", 12, Tema.get(Tema.TEXTO2), true);
        caminho.setPadding(mv.dp(4), 0, 0, mv.dp(8));
        box.addView(caminho);

        LinearLayout at = new LinearLayout(a);
        at.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < rs.size(); i++) {
            final File r = rs.get(i);
            TextView b = mv.btn(i == 0 ? "Interno" : "Cartão " + i, 0xFFE3E7EE, 0xFF1B1F27);
            b.setOnClickListener(v -> nav[0].on(r));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = mv.dp(6);
            at.addView(b, lp);
        }
        final File gm = Pastas.raiz();
        TextView bg = mv.btn("GBA-MAX", 0xFFE3E7EE, 0xFF1B1F27);
        bg.setOnClickListener(v -> { if (!gm.isDirectory()) gm.mkdirs(); nav[0].on(gm); });
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(0, -2, 1f);
        gl.leftMargin = mv.dp(6);
        at.addView(bg, gl);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.bottomMargin = mv.dp(8);
        box.addView(at, al);

        final LinearLayout lista = new LinearLayout(a);
        lista.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(lista);
        int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * .42f);
        box.addView(sv, new LinearLayout.LayoutParams(-1, maxH));

        final TextView info = mv.label("", 12, Tema.get(Tema.TEXTO2), false);
        info.setPadding(mv.dp(4), mv.dp(8), 0, 0);
        box.addView(info);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = mv.btn("Cancelar", 0xFFE3E7EE, 0xFF1B1F27);
        TextView usar = mv.btn("Usar esta pasta", 0xFF2EAD4B, Color.WHITE);
        row.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(0, -2, 1f);
        ul.leftMargin = mv.dp(6);
        row.addView(usar, ul);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = mv.dp(10);
        box.addView(row, rl);
        cancel.setOnClickListener(v -> d.dismiss());
        usar.setOnClickListener(v -> { d.dismiss(); ok.on(cur[0]); });

        nav[0] = f -> {
            cur[0] = f;
            caminho.setText(f.getAbsolutePath());
            lista.removeAllViews();
            final File pai = f.getParentFile();
            if (pai != null && dentro(f, rs)) addRow(lista, mv, linha(mv, "..   (pasta acima)", () -> nav[0].on(pai)));
            File[] fs = f.listFiles();
            if (fs == null) { info.setText("Sem acesso a esta pasta."); return; }
            List<File> ds = new ArrayList<>();
            int jogos = 0;
            for (File x : fs) {
                String n = x.getName();
                if (x.isDirectory()) { if (!n.startsWith(".")) ds.add(x); }
                else {
                    String lw = n.toLowerCase(Locale.ROOT);
                    if (lw.endsWith(".gba") || lw.endsWith(".agb") || lw.endsWith(".zip")) jogos++;
                }
            }
            Collections.sort(ds, (p, q) -> p.getName().compareToIgnoreCase(q.getName()));
            for (File x : ds) addRow(lista, mv, linha(mv, "›   " + x.getName(), () -> nav[0].on(x)));
            info.setText(jogos + " arquivo(s) .gba/.agb/.zip aqui · " + ds.size() + " subpasta(s)");
        };
        nav[0].on(cur[0]);
        d.show();
    }
}
