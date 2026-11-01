package org.gbamax.emu;

import android.app.Dialog;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Movie;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Personalização: imagens dos botões e fundo do app.
 * Tudo fica em GBA-MAX/Personalizar (Botoes/ e fundo.png).
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

    static File fundoGif(Context c) { File d = dir(c); if (d == null) return null; File f = new File(d, "fundo.gif"); return f.isFile() ? f : null; }
    static boolean ehGif(byte[] b) { return b.length > 6 && b[0] == 71 && b[1] == 73 && b[2] == 70 && b[3] == 56; }
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
            byte[] dados = lerTudo(in, 64L << 20); if (ehGif(dados)) { if (Movie.decodeByteArray(dados, 0, dados.length) == null) return "Não consegui ler esse GIF"; try (FileOutputStream go = new FileOutputStream(new File(d, "fundo.gif"))) { go.write(dados); } new File(d, "fundo.png").delete(); return "Fundo animado (GIF) definido"; } Bitmap b = decode(dados, 1800);
            if (b == null) return "Não consegui ler essa imagem";
            try (FileOutputStream o = new FileOutputStream(new File(d, "fundo.png"))) {
                b.compress(Bitmap.CompressFormat.PNG, 100, o);
            }
            b.recycle();
            new File(d, "fundo.gif").delete(); return "Fundo do app definido";
        } catch (Throwable t) {
            return "Não consegui importar o fundo";
        }
    }

    // ================= interface (mesmo estilo da janela de Configurações) =================
    static File abertura(Context c) { File d = dir(c); if (d == null) return null; File g = new File(d, "abertura.gif"); if (g.isFile()) return g; File p = new File(d, "abertura.png"); return p.isFile() ? p : null; }
    static String importarAbertura(Context c, Uri u) { File d = dir(c); if (d == null) return "Sem acesso à pasta GBA-MAX (permita o acesso aos arquivos)"; try (InputStream in = c.getContentResolver().openInputStream(u)) { byte[] x = lerTudo(in, 64L << 20); boolean gif = x.length > 6 && x[0] == 71 && x[1] == 73 && x[2] == 70 && x[3] == 56; File g = new File(d, "abertura.gif"), p = new File(d, "abertura.png"); if (gif) { if (android.graphics.Movie.decodeByteArray(x, 0, x.length) == null) return "Não consegui ler esse GIF"; try (FileOutputStream o = new FileOutputStream(g)) { o.write(x); } p.delete(); return "Abertura (GIF) definida"; } Bitmap b = decode(x, 1800); if (b == null) return "Não consegui ler essa imagem"; try (FileOutputStream o = new FileOutputStream(p)) { b.compress(Bitmap.CompressFormat.PNG, 100, o); } b.recycle(); g.delete(); return "Abertura definida"; } catch (Throwable t) { return "Não consegui importar a abertura"; } }
    interface Pick { void on(int i); }

    static final int IC_IMG = 100, IC_BTN = 101, IC_INFO = 102, IC_TRASH = 103, IC_COR = 104;

    /** Ícone redondo em vidro, igual aos botões da dock/Configurações. */
    static class Ico extends View {
        final int type, col;
        final float den;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Ico(Context c, int type, int col, float den) {
            super(c);
            this.type = type; this.col = col; this.den = den;
            setElevation(5 * den);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) {
                    float r = Math.min(v.getWidth(), v.getHeight()) / 2f - 5 * Ico.this.den;
                    float cx = v.getWidth() / 2f, cy = v.getHeight() / 2f;
                    o.setOval((int) (cx - r), (int) (cy - r), (int) (cx + r), (int) (cy + r));
                }
            });
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
            float r = Math.min(w, h) / 2f - 5 * den;
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, cy - r, 0, cy + r, MenuView.mix(col, Color.WHITE, .35f),
                    MenuView.mix(col, Color.BLACK, .18f), Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
            p.setShader(new LinearGradient(0, cy - r, 0, cy, 0xAAFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawOval(new RectF(cx - r * .72f, cy - r * .94f, cx + r * .72f, cy - r * .05f), p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.2f * den);
            p.setColor(0xCCFFFFFF);
            c.drawCircle(cx, cy, r - .6f * den, p);
            icone(c, cx, cy, r * .5f);
        }

        void icone(Canvas c, float x, float y, float r) {
            if (type < 100) { MenuView.Icons.draw(c, type, x, y, r, Color.WHITE, p); return; }
            p.setShader(null);
            p.setColor(Color.WHITE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * .17f);
            Path q = new Path();
            switch (type) {
                case IC_IMG:
                    c.drawRoundRect(new RectF(x - .85f * r, y - .7f * r, x + .85f * r, y + .7f * r), r * .2f, r * .2f, p);
                    q.moveTo(x - .6f * r, y + .45f * r);
                    q.lineTo(x - .15f * r, y - .05f * r);
                    q.lineTo(x + .2f * r, y + .35f * r);
                    q.lineTo(x + .4f * r, y + .15f * r);
                    q.lineTo(x + .7f * r, y + .45f * r);
                    c.drawPath(q, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(x + .4f * r, y - .3f * r, r * .14f, p);
                    break;
                case IC_COR:
                    c.drawCircle(x, y, r * .88f, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(x - .35f * r, y - .25f * r, r * .15f, p);
                    c.drawCircle(x + .3f * r, y - .3f * r, r * .15f, p);
                    c.drawCircle(x - .05f * r, y + .4f * r, r * .15f, p);
                    break;
                case IC_BTN:
                    c.drawCircle(x, y, r * .85f, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(x, y, r * .3f, p);
                    break;
                case IC_INFO:
                    c.drawCircle(x, y, r * .88f, p);
                    c.drawLine(x, y - .05f * r, x, y + .5f * r, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(x, y - .42f * r, r * .11f, p);
                    break;
                case IC_TRASH:
                    c.drawLine(x - .8f * r, y - .5f * r, x + .8f * r, y - .5f * r, p);
                    q.moveTo(x - .3f * r, y - .5f * r);
                    q.lineTo(x - .3f * r, y - .85f * r);
                    q.lineTo(x + .3f * r, y - .85f * r);
                    q.lineTo(x + .3f * r, y - .5f * r);
                    c.drawPath(q, p);
                    q.reset();
                    q.moveTo(x - .6f * r, y - .35f * r);
                    q.lineTo(x - .5f * r, y + .85f * r);
                    q.lineTo(x + .5f * r, y + .85f * r);
                    q.lineTo(x + .6f * r, y - .35f * r);
                    c.drawPath(q, p);
                    c.drawLine(x - .2f * r, y - .05f * r, x - .2f * r, y + .6f * r, p);
                    c.drawLine(x + .2f * r, y - .05f * r, x + .2f * r, y + .6f * r, p);
                    break;
                default:
                    break;
            }
        }
    }

    static LinearLayout caixa(MenuView mv, Context a, String titulo) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(mv.dp(16), mv.dp(16), mv.dp(16), mv.dp(10));
        box.setBackground(mv.glass(mv.dp(26)));
        TextView t = mv.label(titulo, 18, Tema.get(Tema.TEXTO), true);
        t.setPadding(mv.dp(6), 0, 0, mv.dp(12));
        box.addView(t);
        return box;
    }

    static Dialog janela(MenuView mv, Context a, LinearLayout box) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            int sw = a.getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(mv.dp(340), sw - mv.dp(32)), -2);
        }
        d.setCanceledOnTouchOutside(true);
        return d;
    }

    static LinearLayout linha(MenuView mv, Context a, int type, int cor, String texto, final Runnable r) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(mv.dp(8), mv.dp(4), mv.dp(14), mv.dp(4));
        GradientDrawable rg = new GradientDrawable();
        rg.setColor(Tema.get(Tema.LINHA));
        rg.setCornerRadius(mv.dp(18));
        rg.setStroke(mv.dp(1), 0xCCFFFFFF);
        row.setBackground(rg);
        row.addView(new Ico(a, type, cor, mv.den), new LinearLayout.LayoutParams(mv.dp(52), mv.dp(52)));
        TextView t = new TextView(a);
        t.setText(texto);
        t.setTextColor(Tema.get(Tema.TEXTO));
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = mv.dp(6);
        row.addView(t, tl);
        TextView ar = new TextView(a);
        ar.setText("›");
        ar.setTextColor(Tema.get(Tema.TEXTO2));
        ar.setTextSize(22);
        row.addView(ar);
        row.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.bottomMargin = mv.dp(8);
        row.setLayoutParams(rl);
        return row;
    }

    static void show(final MainActivity a) {
        final MenuView mv = a.mv;
        LinearLayout box = caixa(mv, a, "Personalizar");
        final Dialog d = janela(mv, a, box);

        box.addView(linha(mv, a, MenuView.Icons.PAD, 0xFF7E57C2, "Importar botões (todos)", () -> {
            d.dismiss();
            a.pickFiles(4, true, "image/*", "application/zip");
        }));
        box.addView(linha(mv, a, IC_BTN, 0xFF1E6BE6, "Escolher um botão", () -> {
            d.dismiss();
            escolherBotao(a);
        }));
        box.addView(linha(mv, a, IC_INFO, 0xFF3A3C42, "Nomes dos arquivos", () -> {
            d.dismiss();
            nomesArquivos(a);
        }));
        box.addView(linha(mv, a, IC_IMG, 0xFF2EAD4B, "Fundo do app (imagem ou GIF)", () -> {
            d.dismiss();
            a.pickFiles(5, false, "image/*");
        }));
        box.addView(linha(mv, a, IC_IMG, 0xFFFF8F00, "Abertura (imagem ou GIF)", () -> { d.dismiss(); a.pickFiles(8, false, "image/*"); }));
        box.addView(linha(mv, a, IC_COR, 0xFFD81B60, "Cores do app", () -> {
            d.dismiss();
            TemaUi.lista(a);
        }));
        box.addView(linha(mv, a, IC_TRASH, 0xFFE53935, "Remover...", () -> {
            d.dismiss();
            remover(a);
        }));
        d.show();
    }

    static void nomesArquivos(MainActivity a) {
        MenuView mv = a.mv;
        LinearLayout box = caixa(mv, a, "Nomes dos arquivos");
        TextView m = mv.label("PNG, JPG ou WEBP (ou um .zip com todos). O nome do arquivo diz qual botão é:\n\n"
                + "A, B, L, R, START, SELECT, FF, MENU, TA (turbo A), TB (turbo B)\n"
                + "LA (L+A), LB (L+B), RA (R+A), RB (R+B), AB (A+B)\n"
                + "DPAD (cruzeta), ANALOG (base do analógico), KNOB (bolinha do analógico)\n\n"
                + "Para a imagem do botão apertado, acrescente _on: A_on.png, START_on.png...\n"
                + "Botões sem imagem continuam com o desenho padrão.", 13, 0xFF4A5363, false);
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(m);
        int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * .55f);
        box.addView(sv, new LinearLayout.LayoutParams(-1, -2));
        final Dialog d = janela(mv, a, box);
        TextView ok = mv.btn("OK", 0xFFE3E7EE, Tema.get(Tema.TEXTO));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = mv.dp(10);
        box.addView(ok, fl);
        ok.setOnClickListener(v -> d.dismiss());
        d.show();
    }

    static void remover(final MainActivity a) {
        lista(a, "Remover", new String[]{"Botões personalizados", "Fundo do app", "Abertura"}, w -> {
            if (w == 2) { File ab = abertura(a); if (ab != null) ab.delete(); ab = abertura(a); if (ab != null) ab.delete(); } else if (w == 0) { apagarBotoes(a); if (a.gv != null) a.gv.loadSkin(a); }
            else { File f = fundo(a); if (f != null) f.delete(); File g = fundoGif(a); if (g != null) g.delete(); a.aplicarFundo(); }
            Avancado.salvar(a); a.toast("Removido");
        });
    }

    static void escolherBotao(final MainActivity a) {
        lista(a, "Qual botão?", NOMES, w -> {
            a.pendingKey = TODAS[w];
            a.pickFiles(7, false, "image/*");
        });
    }

    /** Lista de opções em janela de vidro (mesmo estilo das outras). */
    static void lista(MainActivity a, String titulo, String[] itens, final Pick cb) {
        MenuView mv = a.mv;
        LinearLayout box = caixa(mv, a, titulo);
        final Dialog d = janela(mv, a, box);
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < itens.length; i++) {
            final int k = i;
            TextView t = new TextView(a);
            t.setText(itens[i]);
            t.setTextColor(Tema.get(Tema.TEXTO));
            t.setTextSize(15);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(mv.dp(16), mv.dp(13), mv.dp(16), mv.dp(13));
            GradientDrawable g = new GradientDrawable();
            g.setColor(Tema.get(Tema.LINHA));
            g.setCornerRadius(mv.dp(16));
            g.setStroke(mv.dp(1), 0xCCFFFFFF);
            t.setBackground(g);
            t.setOnClickListener(v -> { d.dismiss(); cb.on(k); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = mv.dp(6);
            l.addView(t, lp);
        }
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(l);
        int maxH = (int) (a.getResources().getDisplayMetrics().heightPixels * .6f);
        box.addView(sv, new LinearLayout.LayoutParams(-1, itens.length > 6 ? maxH : -2));
        d.show();
    }
}
