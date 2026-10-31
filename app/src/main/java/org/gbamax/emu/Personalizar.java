package org.gbamax.emu;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Personalização: imagens dos botões, fundo do app e abertura (png, gif ou vídeo).
 * Tudo fica em GBA-MAX/Personalizar (Botoes/, fundo.png e abertura.*).
 */
final class Personalizar {
    static final String BOTOES = "Botoes";

    // mesma ordem de GameView.LAB
    static final String[] KEYS = {"l", "la", "lb", "rb", "ra", "r", "a", "b", "ab", "ta", "tb", "sel", "start", "ff", "menu"};
    static final String DPAD = "dpad", ANALOG = "analog", KNOB = "knob";
    static final String[] NOMES = {"L", "L+A", "L+B", "R+B", "R+A", "R", "A", "B", "A+B", "Turbo A", "Turbo B",
            "Select", "Start", "Avanço rápido", "Menu", "Cruzeta (D-pad)", "Base do analógico", "Bolinha do analógico"};
    static final String[] TODAS = {"l", "la", "lb", "rb", "ra", "r", "a", "b", "ab", "ta", "tb", "sel", "start", "ff", "menu",
            DPAD, ANALOG, KNOB};

    static final Map<String, String> ALIAS = new HashMap<>();
    static {
        for (String k : TODAS) ALIAS.put(k, k);
        ALIAS.put("select", "sel");
        ALIAS.put("selecionar", "sel");
        ALIAS.put("turboa", "ta");
        ALIAS.put("turbob", "tb");
        ALIAS.put("fastforward", "ff");
        ALIAS.put("ffwd", "ff");
        ALIAS.put("avanco", "ff");
        ALIAS.put("avancorapido", "ff");
        ALIAS.put("cross", "dpad");
        ALIAS.put("cruz", "dpad");
        ALIAS.put("cruzeta", "dpad");
        ALIAS.put("direcional", "dpad");
        ALIAS.put("pad", "dpad");
        ALIAS.put("analogico", "analog");
        ALIAS.put("base", "analog");
        ALIAS.put("stick", "analog");
        ALIAS.put("joystick", "analog");
        ALIAS.put("analogbase", "analog");
        ALIAS.put("bolinha", "knob");
        ALIAS.put("bola", "knob");
        ALIAS.put("thumb", "knob");
        ALIAS.put("analogknob", "knob");
        ALIAS.put("stickknob", "knob");
    }

    // ================= pastas e arquivos =================
    static File dir(Context c) { return Pastas.dir(c, Pastas.PERSONALIZAR); }

    static File botoes(Context c) {
        File d = dir(c);
        if (d == null) return null;
        File b = new File(d, BOTOES);
        return (b.isDirectory() || b.mkdirs()) ? b : null;
    }

    static File fundo(Context c) {
        File d = dir(c);
        if (d == null) return null;
        File f = new File(d, "fundo.png");
        return f.isFile() ? f : null;
    }

    static File abertura(Context c) {
        File d = dir(c);
        if (d == null) return null;
        File[] l = d.listFiles();
        if (l == null) return null;
        for (File f : l) if (f.isFile() && f.getName().startsWith("abertura.") && f.length() > 0) return f;
        return null;
    }

    static void apagarAberturas(Context c) {
        File d = dir(c);
        File[] l = d == null ? null : d.listFiles();
        if (l == null) return;
        for (File f : l) if (f.getName().startsWith("abertura.")) f.delete();
    }

    static void apagarBotoes(Context c) {
        File b = botoes(c);
        File[] l = b == null ? null : b.listFiles();
        if (l == null) return;
        for (File f : l) f.delete();
    }

    static Bitmap bmp(File dir, String key) {
        if (dir == null) return null;
        File f = new File(dir, key + ".png");
        if (!f.isFile()) return null;
        try { return BitmapFactory.decodeFile(f.getAbsolutePath()); } catch (Throwable t) { return null; }
    }

    // ================= leitura de imagens =================
    static Bitmap decode(byte[] b, int max) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(b, 0, b.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            return BitmapFactory.decodeByteArray(b, 0, b.length, o);
        } catch (Throwable t) {
            return null;
        }
    }

    static Bitmap decodeMax(File f, int max) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    static byte[] lerTudo(InputStream in, long limite) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] t = new byte[16384];
        int k;
        long tot = 0;
        while ((k = in.read(t)) != -1) {
            tot += k;
            if (tot > limite) throw new Exception("arquivo grande demais");
            o.write(t, 0, k);
        }
        return o.toByteArray();
    }

    static String nomeDe(Context c, Uri u) {
        try (Cursor q = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst()) return q.getString(0);
        } catch (Throwable t) { }
        String s = u.getLastPathSegment();
        return s == null ? "" : s.substring(s.lastIndexOf('/') + 1);
    }

    /** "A.png", "l+a.PNG", "Start_on.webp"... -> chave ("a", "la", "start_on"...) ou null. */
    static String keyFor(String nome) {
        if (nome == null) return null;
        String s = nome.toLowerCase(Locale.ROOT);
        int dot = s.lastIndexOf('.');
        if (dot > 0 && s.length() - dot <= 5) s = s.substring(0, dot);
        s = s.replace("+", "").replaceAll("[\\s\\-]+", "_").replaceAll("[^a-z0-9_]", "");
        boolean on = false;
        for (String suf : new String[]{"_on", "_press", "_pressed", "_pressionado", "_ativo"}) {
            if (s.endsWith(suf)) { on = true; s = s.substring(0, s.length() - suf.length()); break; }
        }
        s = s.replaceAll("_+$", "").replace("_", "");
        String k = ALIAS.get(s);
        if (k == null) return null;
        return on ? k + "_on" : k;
    }

    static boolean salvarBotao(Context c, String key, byte[] dados) {
        File d = botoes(c);
        if (d == null) return false;
        Bitmap b = decode(dados, 512);
        if (b == null) return false;
        try (FileOutputStream o = new FileOutputStream(new File(d, key + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            b.recycle();
        }
    }

    // ================= importações (rodar fora da thread principal) =================
    static String importarBotoes(Context c, List<Uri> us) {
        if (botoes(c) == null) return "Sem acesso à pasta GBA-MAX (permita o acesso aos arquivos)";
        int ok = 0, ign = 0;
        for (Uri u : us) {
            String nome = nomeDe(c, u);
            try {
                boolean zip = nome.toLowerCase(Locale.ROOT).endsWith(".zip");
                if (zip) {
                    try (ZipInputStream z = new ZipInputStream(c.getContentResolver().openInputStream(u))) {
                        ZipEntry e;
                        while ((e = z.getNextEntry()) != null) {
                            if (e.isDirectory()) continue;
                            String n = e.getName();
                            n = n.substring(n.lastIndexOf('/') + 1);
                            String k = keyFor(n);
                            if (k == null) { ign++; continue; }
                            if (salvarBotao(c, k, lerTudo(z, 16L << 20))) ok++; else ign++;
                        }
                    }
                } else {
                    String k = keyFor(nome);
                    if (k == null) { ign++; continue; }
                    try (InputStream in = c.getContentResolver().openInputStream(u)) {
                        if (salvarBotao(c, k, lerTudo(in, 16L << 20))) ok++; else ign++;
                    }
                }
            } catch (Throwable t) {
                ign++;
            }
        }
        if (ok == 0) return "Nenhum botão reconhecido. Use os nomes: A, B, L, R, START, SELECT... (veja \"Nomes dos arquivos\")";
        return ok + " imagem(ns) de botão importada(s)" + (ign > 0 ? "; " + ign + " ignorada(s)" : "");
    }

    static String importarUmBotao(Context c, Uri u, String key) {
        if (key == null || botoes(c) == null) return "Sem acesso à pasta GBA-MAX";
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            if (salvarBotao(c, key, lerTudo(in, 16L << 20))) return "Botão importado";
        } catch (Throwable t) { }
        return "Não consegui ler essa imagem";
    }

    static String importarFundo(Context c, Uri u) {
        File d = dir(c);
        if (d == null) return "Sem acesso à pasta GBA-MAX (permita o acesso aos arquivos)";
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            Bitmap b = decode(lerTudo(in, 64L << 20), 1800);
            if (b == null) return "Não consegui ler essa imagem";
            try (FileOutputStream o = new FileOutputStream(new File(d, "fundo.png"))) {
                b.compress(Bitmap.CompressFormat.PNG, 100, o);
            }
            b.recycle();
            return "Fundo do app definido";
        } catch (Throwable t) {
            return "Não consegui importar o fundo";
        }
    }

    /** Detecta o tipo pelo conteúdo: "gif", "img" ou "video". */
    static String tipo(File f) {
        byte[] h = new byte[16];
        int n = 0;
        try (FileInputStream in = new FileInputStream(f)) { n = in.read(h); } catch (Throwable t) { }
        if (n >= 4) {
            if (h[0] == 'G' && h[1] == 'I' && h[2] == 'F') return "gif";
            if ((h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return "img";
            if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8) return "img";
            if (h[0] == 'B' && h[1] == 'M') return "img";
            if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E') return "img";
        }
        return "video";
    }

    static String extDe(File f, String nomeOriginal) {
        String t = tipo(f);
        if (t.equals("gif")) return "gif";
        if (t.equals("img")) {
            byte[] h = new byte[4];
            try (FileInputStream in = new FileInputStream(f)) { in.read(h); } catch (Throwable e) { }
            return (h[0] & 0xFF) == 0x89 ? "png" : (h[0] & 0xFF) == 0xFF ? "jpg" : "img";
        }
        String n = nomeOriginal == null ? "" : nomeOriginal.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String e = dot >= 0 ? n.substring(dot + 1).replaceAll("[^a-z0-9]", "") : "";
        return e.isEmpty() || e.length() > 5 ? "mp4" : e;
    }

    static String importarAbertura(Context c, Uri u) {
        File d = dir(c);
        if (d == null) return "Sem acesso à pasta GBA-MAX (permita o acesso aos arquivos)";
        File tmp = new File(d, "abertura.tmp");
        try {
            try (InputStream in = c.getContentResolver().openInputStream(u); FileOutputStream o = new FileOutputStream(tmp)) {
                byte[] t = new byte[65536];
                int k;
                while ((k = in.read(t)) != -1) o.write(t, 0, k);
            }
            String ext = extDe(tmp, nomeDe(c, u));
            apagarAberturas(c);
            File dest = new File(d, "abertura." + ext);
            if (!tmp.renameTo(dest)) { Pastas.copiar(tmp, dest); tmp.delete(); }
            String aviso = "";
            if (tipo(dest).equals("video")) {
                try {
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    r.setDataSource(dest.getAbsolutePath());
                    String ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    r.release();
                    if (ms == null) { dest.delete(); return "Este vídeo não é suportado pelo aparelho"; }
                    if (Long.parseLong(ms) > 10500) aviso = " (só os primeiros 10 s serão exibidos)";
                } catch (Throwable t) {
                    dest.delete();
                    return "Este vídeo não é suportado pelo aparelho";
                }
            }
            return "Abertura definida" + aviso;
        } catch (Throwable t) {
            tmp.delete();
            return "Não consegui importar a abertura";
        }
    }

    // ================= interface =================
    static void show(final MainActivity a) {
        final MenuView mv = a.mv;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView title = mv.label("Personalizar", 18, 0xFF1B1F27, true);
        title.setPadding(mv.dp(4), 0, 0, mv.dp(10));
        box.addView(title);
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] dd = new Dialog[1];

        l.addView(item(mv, "Importar botões (todos)", 0xFF7E57C2, () -> {
            dd[0].dismiss();
            a.pickFiles(4, true, "image/*", "application/zip");
        }));
        l.addView(item(mv, "Escolher um botão", 0xFF1E6BE6, () -> {
            dd[0].dismiss();
            escolherBotao(a);
        }));
        l.addView(item(mv, "Nomes dos arquivos", 0xFF3A3C42, () -> {
            new AlertDialog.Builder(a).setTitle("Nomes dos arquivos")
                    .setMessage("PNG, JPG ou WEBP (ou um .zip com todos). O nome do arquivo diz qual botão é:\n\n"
                            + "A, B, L, R, START, SELECT, FF, MENU, TA (turbo A), TB (turbo B)\n"
                            + "LA (L+A), LB (L+B), RA (R+A), RB (R+B), AB (A+B)\n"
                            + "DPAD (cruzeta), ANALOG (base do analógico), KNOB (bolinha do analógico)\n\n"
                            + "Para a imagem do botão apertado, acrescente _on: A_on.png, START_on.png...\n"
                            + "Botões sem imagem continuam com o desenho padrão.")
                    .setPositiveButton("OK", null).show();
        }));
        l.addView(item(mv, "Fundo do app (imagem)", 0xFF2EAD4B, () -> {
            dd[0].dismiss();
            a.pickFiles(5, false, "image/*");
        }));
        l.addView(item(mv, "Abertura do app (PNG, GIF ou vídeo até 10 s)", 0xFFFFA000, () -> {
            dd[0].dismiss();
            a.pickFiles(6, false, "image/*", "video/*");
        }));
        l.addView(item(mv, "Testar abertura", 0xFF1E6BE6, () -> {
            dd[0].dismiss();
            a.mostrarAbertura(true);
        }));
        l.addView(item(mv, "Remover...", 0xFFE53935, () -> {
            new AlertDialog.Builder(a).setTitle("Remover")
                    .setItems(new String[]{"Botões personalizados", "Fundo do app", "Abertura"}, (di, w) -> {
                        if (w == 0) { apagarBotoes(a); if (a.gv != null) a.gv.loadSkin(a); }
                        else if (w == 1) { File f = fundo(a); if (f != null) f.delete(); a.aplicarFundo(); }
                        else apagarAberturas(a);
                        a.toast("Removido");
                    }).show();
        }));
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(l);
        int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * .62f);
        box.addView(sv, new LinearLayout.LayoutParams(-1, maxH));
        TextView fechar = mv.btn("Fechar", 0xFFE3E7EE, 0xFF1B1F27);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = mv.dp(8);
        box.addView(fechar, fl);
        final Dialog d = mv.glassDialog(box);
        dd[0] = d;
        d.setCanceledOnTouchOutside(true);
        fechar.setOnClickListener(v -> d.dismiss());
        d.show();
    }

    static TextView item(MenuView mv, String t, int cor, final Runnable r) {
        TextView b = mv.btn(t, cor, 0xFFFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = mv.dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    static void escolherBotao(final MainActivity a) {
        new AlertDialog.Builder(a).setTitle("Qual botão?").setItems(NOMES, (d, w) -> {
            a.pendingKey = TODAS[w];
            a.pickFiles(7, false, "image/*");
        }).show();
    }
}
