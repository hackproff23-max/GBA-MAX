package org.gbamax.emu;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.LockSupport;

public class MainActivity extends Activity {
    static final String FOLDER = Pastas.RAIZ;

    GameView gv;
    GlScreen gl;
    FrameLayout root;
    MenuView mv;
    Uri dirUri;

    Thread th;
    volatile boolean run;
    AudioTrack at;
    boolean ready, loaded, inGame, built, asked, autoScanned;
    boolean menuOpen; // menu dentro do jogo aberto (jogo pausado)
    GameMenu gm;
    String sav;      // GBA-MAX/Data/<jogo>.sav
    String stBase;   // GBA-MAX/Saves/<jogo>  (+ .st1 ... .st5)
    String gameName; // nome do jogo que está sendo aberto
    long autoNs;     // tempo jogado desde o último salvamento automático

    // ================= início e diagnóstico =================
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Avancado.carregar(this); VideoSettings.load(this);
        Tema.load(this);
        VideoSettings.applyMenuOrientation(this); // menu sempre em retrato
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // tela cheia total: desenha por cima do recorte da câmera (notch)
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            writeText("crash.txt", trace(e));
            if (old != null) old.uncaughtException(t, e);
        });
        String crash = readText("crash.txt");
        String flag = readText("native.flag");
        if (crash != null || flag != null) {
            StringBuilder sb = new StringBuilder();
            if (crash != null) sb.append("ERRO JAVA:\n").append(crash).append("\n");
            if (flag != null) sb.append("O APP FECHOU NA ETAPA NATIVA: ").append(flag).append("\n");
            new File(getFilesDir(), "crash.txt").delete();
            new File(getFilesDir(), "native.flag").delete();
            showError(sb.toString());
            return;
        }
        buildUi();
    }

    void buildUi() {
        try {
            root = new FrameLayout(this);
            root.setBackgroundColor(Color.BLACK);
            gl = new GlScreen(this);
            gl.onError = m -> toast(m);
            gl.setVisibility(View.GONE);
            gv = new GameView(this);
            gv.gl = gl;
            gv.onRom = this::openGameMenu;
            gm = null;
            gv.setVisibility(View.GONE);
            mv = new MenuView(this, new MenuView.Host() {
                @Override public void play(MenuView.Game g) { playGame(g); }
                @Override public void pickDir() { chooseDir(); }
                @Override public void refresh() {
                    if (dirUri != null) scan(dirUri); else { autoScanned = false; ensureFolder(); }
                }
                @Override public void videoChanged() {
                    // a orientação escolhida só vale no jogo / editor de layout
                    if (inGame) VideoSettings.applyOrientation(MainActivity.this);
                    if (gl != null) gl.reloadShader();
                }
                @Override public void editLayout() { startLayoutEdit(); }
                @Override public void importShaders() { pickShaders(); }
            });
            root.addView(new GifBg(this, mv), new FrameLayout.LayoutParams(-1, -1));
            root.addView(gl);
            root.addView(gv);
            root.addView(mv);
            setContentView(root);
            built = true;
            String d = Pasta.ler(this) != null ? null : getPreferences(0).getString("dir", null);
            if (d != null) { dirUri = Uri.parse(d); scan(dirUri); }
            else mv.setGames(new ArrayList<MenuView.Game>(), null, false);
            ensureFolder();
            aplicarFundo();
            if (!Tema.introJa) { Tema.introJa = true; try { root.addView(new Intro(this), new FrameLayout.LayoutParams(-1, -1)); } catch (Throwable t) { } }
        } catch (Throwable t) {
            showError(trace(t));
        }
    }

    // ================= personalizar: botões, fundo e abertura =================
    String pendingKey;

    void pickFiles(int req, boolean multi, String... mimes) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        if (mimes != null && mimes.length > 0) i.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
        if (multi) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, req); } catch (Throwable e) { toast("Não consegui abrir o seletor de arquivos"); }
    }

    void personalizarResult(final int req, Intent d) {
        final List<Uri> us = new ArrayList<>();
        ClipData cd = d.getClipData();
        if (cd != null) { for (int k = 0; k < cd.getItemCount(); k++) us.add(cd.getItemAt(k).getUri()); }
        else if (d.getData() != null) us.add(d.getData());
        if (us.isEmpty()) return;
        final String key = pendingKey;
        new Thread(() -> {
            String msg;
            try {
                if (req == 4) msg = Personalizar.importarBotoes(this, us);
                else if (req == 7) msg = Personalizar.importarUmBotao(this, us.get(0), key);
                else if (req == 5) msg = Personalizar.importarFundo(this, us.get(0)); else if (req == 8) msg = Personalizar.importarAbertura(this, us.get(0));
                else msg = "Não foi possível importar";
            } catch (Throwable t) {
                msg = "Não foi possível importar";
            }
            final String m = msg;
            runOnUiThread(() -> {
                if (gv != null) gv.loadSkin(this);
                aplicarFundo();
                Avancado.salvar(this); toast(m);
            });
        }).start();
    }

    void aplicarFundo() {
        if (mv == null) return;
        Bitmap b = null;
        try {
            File f = Personalizar.fundo(this);
            if (f != null) b = Personalizar.decodeMax(f, 1800);
        } catch (Throwable t) { }
        mv.setCustomBg(b);
        mv.bgView.setVisibility(b != null ? View.VISIBLE : View.GONE);
    }


    void showError(String text) {
        built = false;
        if (text.length() > 6000) text = text.substring(0, 6000);
        final String full = text;
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(0xFF101418);
        l.setPadding(dp(16), dp(32), dp(16), dp(16));
        TextView h = new TextView(this);
        h.setText("GBA-MAX encontrou um erro");
        h.setTextColor(Color.WHITE);
        h.setTextSize(20);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        TextView info = new TextView(this);
        info.setText("Toque em Copiar erro e envie o texto abaixo para corrigirmos.");
        info.setTextColor(0xFFAAB2BD);
        info.setPadding(0, dp(8), 0, dp(8));
        LinearLayout row = new LinearLayout(this);
        Button cp = new Button(this);
        cp.setText("Copiar erro");
        cp.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("erro", full));
            toast("Copiado");
        });
        Button op = new Button(this);
        op.setText("Abrir menu");
        op.setOnClickListener(v -> buildUi());
        row.addView(cp);
        row.addView(op);
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(full);
        tv.setTextColor(0xFFFFB4A9);
        tv.setTextSize(10);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        sv.addView(tv);
        l.addView(h);
        l.addView(info);
        l.addView(row);
        l.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(l);
    }

    int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    String trace(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    void writeText(String n, String s) {
        try (FileWriter w = new FileWriter(new File(getFilesDir(), n))) { w.write(s); }
        catch (Throwable x) { }
    }

    String readText(String n) {
        try {
            File f = new File(getFilesDir(), n);
            if (!f.exists()) return null;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(f)) {
                byte[] t = new byte[4096];
                int k;
                while ((k = in.read(t)) != -1) o.write(t, 0, k);
            }
            return o.toString();
        } catch (Throwable x) { return null; }
    }

    void mark(String s) { writeText("native.flag", s); }
    boolean skipResume; int pendingSlot;
    String stBaseFor(String n) { File d = Pastas.dir(this, Pastas.SAVES); return d == null ? null : new File(d, Pastas.seguro(n)).getAbsolutePath(); }
    String hashVelho(Uri u) { try (InputStream in = getContentResolver().openInputStream(u)) { ByteArrayOutputStream o = new ByteArrayOutputStream(1 << 22); byte[] t = new byte[65536]; int k; while ((k = in.read(t)) != -1) o.write(t, 0, k); return getFilesDir() + "/" + Integer.toHexString(Arrays.hashCode(RomReader.extract(o.toByteArray()))); } catch (Throwable e) { return null; } }
    void apagarSaves(MenuView.Game g) { String n = g.name, nome = Pastas.seguro(n); if (loaded && n.equals(gameName)) { try { Native.unload(); } catch (Throwable t) { } loaded = false; } File dd = Pastas.dir(this, Pastas.DATA); if (dd != null) new File(dd, nome + ".sav").delete(); String b = stBaseFor(n); if (b != null) { for (int s = 1; s <= 5; s++) { new File(b + ".st" + s).delete(); new File(b + "-img" + s).delete(); } for (int s = 1; s <= 3; s++) { new File(b + ".auto" + s).delete(); new File(b + "-auto" + s).delete(); } new File(b + ".resume").delete(); new File(b + "-retomar").delete(); } String v = hashVelho(g.uri); if (v != null) { new File(v + ".sav").delete(); for (int s = 1; s <= 5; s++) new File(v + ".st" + s).delete(); } }
    void unmark() { new File(getFilesDir(), "native.flag").delete(); }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        try { if (built && mv != null) mv.relayout(); } catch (Throwable t) { }
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // ================= pasta GBA-MAX no armazenamento interno =================
    // GBA-MAX/Configurações, Shaders, Data, Saves e Cheats (ver Pastas.java)
    boolean hasStorage() { return Pastas.temAcesso(this); }

    void askStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            toast("Permita o acesso a todos os arquivos para criar a pasta " + FOLDER);
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE}, 2);
        }
    }

    void ensureFolder() {
        try {
            if (!hasStorage()) {
                if (!asked) { asked = true; askStorage(); }
                return;
            }
            if (!Pastas.criar(this)) { toast("Não consegui criar a pasta " + FOLDER); return; }
            // a tela de shaders foi criada antes da permissão: recria para ela usar a pasta Shaders
            if (gl != null && !inGame && !gl.shaderRoot.equals(ShaderStore.root(this))) {
                recreate();
                return;
            }
            if (dirUri == null && !autoScanned) { autoScanned = true; scanFolder(Pasta.atual(this)); }
        } catch (Throwable t) {
            toast("Pasta " + FOLDER + ": " + t.getMessage());
        }
    }

    void scanFolder(File dir) { scanFolderAsync(dir); } void scanFolderSync(File dir) {
        TreeMap<String, MenuView.Game> m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Uri> im = new HashMap<>();
        walkFiles(dir, m, im, 0);
        List<MenuView.Game> l = new ArrayList<>(m.values());
        for (MenuView.Game g : l) g.cover = im.get(g.name.toLowerCase());
        mv.setGames(l, getPreferences(0).getString("last", null), true);
        fetchCovers(l);
    }

    void walkFiles(File d, Map<String, MenuView.Game> m, Map<String, Uri> im, int depth) {
        File[] a = d.listFiles();
        if (a == null) return;
        for (File f : a) {
            String n = f.getName();
            if (f.isDirectory()) {
                if (depth == 0 && Pastas.reservada(n)) continue; // Configurações, Shaders, Data, Saves, Cheats
                if (depth < 6) walkFiles(f, m, im, depth + 1);
                continue;
            }
            addEntry(n, Uri.fromFile(f), m, im);
        }
    }

    void addEntry(String n, Uri u, Map<String, MenuView.Game> m, Map<String, Uri> im) {
        int dot = n.lastIndexOf('.');
        if (dot <= 0) return;
        String base = n.substring(0, dot), low = n.toLowerCase();
        if (low.endsWith(".gba") || low.endsWith(".agb") || (low.endsWith(".zip") && Pasta.zipTemJogo(this, u))) {
            MenuView.Game g = new MenuView.Game();
            g.name = base;
            g.uri = u;
            m.put(base, g);
        } else if (low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".webp")) {
            im.put(base.toLowerCase(), u);
        }
    }

    // ================= capas (baixadas e identificadas) =================
    int coverGen;

    void fetchCovers(final List<MenuView.Game> l) {
        final int gen = ++coverGen;
        final File dir = new File(getFilesDir(), "covers");
        new Thread(() -> new CoverFetcher(dir, getContentResolver()).run(new ArrayList<>(l), (g, f) -> runOnUiThread(() -> {
            if (gen != coverGen) return;
            g.cover = Uri.fromFile(f);
            mv.refreshCovers();
        }))).start();
    }

    // ================= pasta escolhida pelo seletor =================
    void chooseDir() { escolherPasta(); } void chooseDirSaf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, 1);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent d) {
        if (res != RESULT_OK || d == null) return;
        if (req == 3) { importResult(d); return; }
        if (req >= 4 && req <= 8) { personalizarResult(req, d); return; }
        if (d.getData() == null) return;
        Uri u = d.getData();
        try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception e) { }
        getPreferences(0).edit().putString("dir", u.toString()).apply();
        dirUri = u;
        scan(u);
    }

    void escolherPasta() { Pasta.escolher(this, f -> { if (!Pasta.salvar(this, f)) { toast("Não consegui salvar a pasta"); return; } getPreferences(0).edit().remove("dir").apply(); dirUri = null; autoScanned = true; scanFolder(f); toast("Pasta dos jogos: " + f.getName()); }); }
    void scanFolderAsync(final File dir) { new Thread(() -> { final TreeMap<String, MenuView.Game> m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER); final Map<String, Uri> im = new HashMap<>(); walkFiles(dir, m, im, 0); runOnUiThread(() -> { List<MenuView.Game> l = new ArrayList<>(m.values()); for (MenuView.Game g : l) g.cover = im.get(g.name.toLowerCase()); mv.setGames(l, getPreferences(0).getString("last", null), true); fetchCovers(l); }); }).start(); }
    void scan(Uri tree) {
        TreeMap<String, MenuView.Game> m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Uri> im = new HashMap<>();
        boolean ok = true;
        try {
            walk(tree, DocumentsContract.getTreeDocumentId(tree), m, im, 0);
        } catch (Exception e) { ok = false; }
        List<MenuView.Game> l = new ArrayList<>(m.values());
        for (MenuView.Game g : l) g.cover = im.get(g.name.toLowerCase());
        mv.setGames(l, getPreferences(0).getString("last", null), true);
        fetchCovers(l);
        if (!ok) toast("Não consegui ler a pasta. Selecione de novo.");
    }

    void walk(Uri tree, String id, Map<String, MenuView.Game> m, Map<String, Uri> im, int depth) {
        Uri ch = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        try (Cursor c = getContentResolver().query(ch, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String did = c.getString(0), n = c.getString(1), mt = c.getString(2);
                if (n == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mt)) {
                    if (depth < 6) walk(tree, did, m, im, depth + 1);
                    continue;
                }
                addEntry(n, DocumentsContract.buildDocumentUriUsingTree(tree, did), m, im);
            }
        }
    }

    // ================= jogo =================
    // o núcleo nativo só é carregado quando você abre o primeiro jogo
    boolean initCore() {
        if (ready) return true;
        mark("carregar o nucleo mGBA (init)");
        try {
            ready = Native.init(getApplicationInfo().nativeLibraryDir + "/libmgba_core.so",
                    getFilesDir().getAbsolutePath());
        } catch (Throwable t) {
            writeText("crash.txt", trace(t));
            ready = false;
        }
        unmark();
        if (!ready) toast("Núcleo mGBA não encontrado ou falhou ao carregar");
        return ready;
    }

    void playGame(MenuView.Game g) {
        final boolean skR = skipResume; final int pS = pendingSlot; skipResume = false; pendingSlot = 0; if (!initCore()) return;
        getPreferences(0).edit().putString("last", g.name).apply();
        gameName = g.name;
        try { open(g.uri, skR, pS); } catch (Throwable e) { toast("Erro ao abrir: " + e.getMessage()); }
    }

    /** Define onde ficam o .sav (Data) e os estados (Saves) e traz os arquivos antigos, se houver. */
    void prepararPastas(String nome, byte[] rom) {
        String velho = getFilesDir() + "/" + Integer.toHexString(Arrays.hashCode(rom));
        sav = velho + ".sav";
        stBase = velho;
        File dData = Pastas.dir(this, Pastas.DATA);
        if (dData != null) {
            sav = new File(dData, nome + ".sav").getAbsolutePath();
            migrar(new File(velho + ".sav"), new File(sav));
        }
        File dSaves = Pastas.dir(this, Pastas.SAVES);
        if (dSaves != null) {
            stBase = new File(dSaves, nome).getAbsolutePath();
            try { new File(dSaves, ".nomedia").createNewFile(); } catch (Exception e) { }
            for (int s = 1; s <= 5; s++) migrar(new File(velho + ".st" + s), new File(stBase + ".st" + s));
        }
    }

    static void migrar(File de, File para) {
        if (de.isFile() && !para.exists()) Pastas.copiar(de, para);
    }

    /** Lê GBA-MAX/Cheats/<jogo>.cht (uma linha por cheat: Nome=CODIGO ou só o código; # desliga). */
    void aplicarCheats(String nome) {
        try {
            Native.cheatReset();
            File d = Pastas.dir(this, Pastas.CHEATS);
            if (d == null) return;
            File f = new File(d, nome + ".cht");
            if (!f.isFile()) {
                try (FileWriter w = new FileWriter(f)) {
                    w.write("# Cheats de " + nome + "\n"
                            + "# Uma linha por cheat:  Nome=CODIGO   (ou só o código)\n"
                            + "# Códigos GameShark / Action Replay / CodeBreaker do GBA. Ex.:  Vida infinita=XXXXXXXX YYYYYYYY\n"
                            + "# Linhas começadas com # ficam desligadas.\n");
                }
                return;
            }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(f)) {
                byte[] t = new byte[4096];
                int k;
                while ((k = in.read(t)) != -1) o.write(t, 0, k);
            }
            int i = 0;
            for (String linha : o.toString("UTF-8").replace("\r", "").split("\n")) {
                String s = linha.trim();
                if (s.isEmpty() || s.startsWith("#") || s.startsWith(";") || s.startsWith("//")) continue;
                int eq = s.indexOf('=');
                String code = eq >= 0 ? s.substring(eq + 1).trim() : s;
                if (code.isEmpty()) continue;
                Native.cheatSet(i++, true, code);
            }
        } catch (Throwable t) { }
    }

    void open(Uri u, boolean skR, int pS) throws Exception {
        stop();
        if (loaded) { save(); Native.unload(); loaded = false; }
        InputStream in = getContentResolver().openInputStream(u);
        ByteArrayOutputStream o = new ByteArrayOutputStream(1 << 22);
        byte[] t = new byte[65536];
        int n;
        while ((n = in.read(t)) != -1) o.write(t, 0, n);
        in.close();
        byte[] rom = RomReader.extract(o.toByteArray());
        String nome = Pastas.seguro(gameName != null ? gameName : "jogo");
        prepararPastas(nome, rom);
        mark("carregar a ROM (load)");
        boolean ok = Native.load(rom, "game.gba");
        unmark();
        if (!ok) { toast("ROM inválida"); return; }
        Native.loadSram(sav);
        loaded = true;
        autoNs = 0;
        VideoSettings.openGame(this, nome); // configurações próprias do jogo (.json)
        if (gv != null) gv.loadSkin(this);
        if (gl != null) gl.reloadShader();
        aplicarCheats(nome);
        inGame = true;
        VideoSettings.applyOrientation(this); // orientação escolhida vale só no jogo
        mv.setVisibility(View.GONE);
        gl.setVisibility(View.VISIBLE);
        gv.setVisibility(View.VISIBLE);
        if (pS != 0) { File sf = new File(statePath(pS)); if (sf.isFile() && Native.loadState(sf.getAbsolutePath())) { afterLoadState(); toast("Estado carregado"); } } else if (VideoSettings.resume && !skR) {
            File rf = new File(statePath(RETOMAR));
            if (rf.isFile() && Native.loadState(rf.getAbsolutePath())) {
                afterLoadState();
                toast("Retomando de onde você parou");
            }
        }
        start();
    }

    // ================= menu dentro do jogo =================
    void openGameMenu() {
        if (!inGame || menuOpen) return;
        if (gm == null) gm = new GameMenu(this);
        gm.open();
    }

    void pauseGame() {
        menuOpen = true;
        stop();
        save();
        gv.touch = 0; gv.ta = false; gv.tb = false;
        gv.knob = false; gv.pressed = 0; gv.crossM = 0;
        gv.setVisibility(View.GONE);
    }

    void resumeGame() {
        menuOpen = false;
        if (!inGame) return;
        gv.setVisibility(View.VISIBLE);
        gv.recalc();
        start();
    }

    // slots 1..5 = manuais (.st1..5); slots 101..103 = automáticos (.auto1..3)
    // slot 200 = retomar de onde parou (.resume)
    static final int RETOMAR = 200;

    String statePath(int slot) {
        if (slot == RETOMAR) return stBase + ".resume";
        return slot > 100 ? stBase + ".auto" + (slot - 100) : stBase + ".st" + slot;
    }

    /** Miniatura do estado: imagem SEM extensão (assim a galeria do celular não a mostra). */
    String thumbPath(int slot) {
        if (slot == RETOMAR) return stBase + "-retomar";
        return slot > 100 ? stBase + "-auto" + (slot - 100) : stBase + "-img" + slot;
    }

    /** Grava a imagem atual do jogo ao lado do estado (JPEG, sem extensão no nome). */
    void saveThumb(int slot) {
        try {
            Bitmap b = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888);
            Native.draw(b);
            try (FileOutputStream o = new FileOutputStream(thumbPath(slot))) {
                b.compress(Bitmap.CompressFormat.JPEG, 90, o);
            }
            b.recycle();
        } catch (Throwable t) { }
    }

    /** Salva o estado e, se der certo, a imagem dele. */
    boolean saveStateImg(int slot) {
        if (!Native.saveState(statePath(slot))) return false;
        saveThumb(slot);
        return true;
    }

    /** Guarda o ponto onde o jogador está para retomar depois. */
    void saveResume() {
        if (!loaded || !inGame || stBase == null || !VideoSettings.resume) return;
        try { saveStateImg(RETOMAR); } catch (Throwable t) { }
    }

    void clearResume() {
        if (stBase == null) return;
        new File(statePath(RETOMAR)).delete();
        new File(thumbPath(RETOMAR)).delete();
    }

    /** Salvamento automático: 3 slots em rodízio, sempre sobrescreve o mais antigo. */
    void autoSave() {
        if (!loaded || stBase == null) return;
        int best = 1;
        long old = Long.MAX_VALUE;
        for (int i = 1; i <= 3; i++) {
            File f = new File(statePath(100 + i));
            long m = f.isFile() ? f.lastModified() : 0;
            if (m < old) { old = m; best = i; }
        }
        showSaving();
        saveStateImg(100 + best);
        hideSaving();
    }

    // ================= indicador do salvamento automático (círculo girando) =================
    SaveSpinner spin;

    static class SaveSpinner extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();

        SaveSpinner(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float s = w * 0.14f;
            p.setStrokeWidth(s);
            r.set(s, s, w - s, h - s);
            p.setShader(new SweepGradient(w / 2f, h / 2f,
                    new int[]{0x004FC3F7, 0xFF4FC3F7, 0xFFFFFFFF}, new float[]{0f, 0.7f, 1f}));
        }

        @Override protected void onDraw(Canvas c) {
            float ang = (System.nanoTime() / 1_000_000L % 800) / 800f * 360f;
            c.save();
            c.rotate(ang, getWidth() / 2f, getHeight() / 2f);
            c.drawArc(r, 0, 360, false, p);
            c.restore();
            postInvalidateOnAnimation();
        }
    }

    void showSaving() {
        runOnUiThread(() -> {
            try {
                if (spin != null || root == null) return;
                float d = getResources().getDisplayMetrics().density;
                int top = (int) (14 * d);
                if (Build.VERSION.SDK_INT >= 28) {
                    WindowInsets wi = root.getRootWindowInsets();
                    if (wi != null && wi.getDisplayCutout() != null) top += wi.getDisplayCutout().getSafeInsetTop();
                }
                spin = new SaveSpinner(this);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (26 * d), (int) (26 * d),
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                lp.topMargin = top;
                root.addView(spin, lp);
            } catch (Throwable e) { }
        });
    }

    void hideSaving() {
        runOnUiThread(() -> {
            if (root == null) return;
            root.postDelayed(() -> {
                try { if (spin != null && spin.getParent() == root) root.removeView(spin); } catch (Throwable e) { }
                spin = null;
            }, 900);
        });
    }

    void afterLoadState() {
        Native.setSkip(false);
        Native.run();
        Native.audio(new short[8192]);
        gl.requestRender();
    }

    void resetGame() {
        Native.reset();
        Native.setSkip(false);
        Native.run();
        Native.audio(new short[8192]);
        gl.requestRender();
    }

    void showMenu() {
        if (gm != null) gm.dismissAll();
        menuOpen = false;
        stop();
        save();
        saveResume();                  // guarda onde parou para retomar
        VideoSettings.save(this);      // grava o .json do jogo
        VideoSettings.closeGame(this); // volta às configurações padrão
        if (gl != null) gl.reloadShader();
        inGame = false;
        gv.touch = 0; gv.ta = false; gv.tb = false;
        gv.setVisibility(View.GONE);
        gl.setVisibility(View.GONE);
        VideoSettings.applyMenuOrientation(this); // menu volta para retrato
        mv.setVisibility(View.VISIBLE);
    }

    // ================= vídeo: editar layout e importar shaders =================
    void startLayoutEdit() {
        if (inGame) {
            if (gm != null) gm.dismissAll();
            gv.setVisibility(View.VISIBLE);
            gv.startEdit(this::endLayoutEdit);
            return;
        }
        VideoSettings.applyOrientation(this); // edita na orientação escolhida
        mv.setVisibility(View.GONE);
        gl.setVisibility(View.VISIBLE);
        gv.setVisibility(View.VISIBLE);
        gv.startEdit(this::endLayoutEdit);
    }

    void endLayoutEdit() {
        if (inGame) { resumeGame(); return; }
        gv.setVisibility(View.GONE);
        gl.setVisibility(View.GONE);
        VideoSettings.applyMenuOrientation(this);
        mv.setVisibility(View.VISIBLE);
    }

    void pickShaders() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, 3);
    }

    void importResult(Intent d) {
        final List<Uri> us = new ArrayList<>();
        ClipData cd = d.getClipData();
        if (cd != null) { for (int k = 0; k < cd.getItemCount(); k++) us.add(cd.getItemAt(k).getUri()); }
        else if (d.getData() != null) us.add(d.getData());
        new Thread(() -> {
            final int n = ShaderStore.importUris(this, us);
            runOnUiThread(() -> {
                toast(n > 0 ? n + " arquivo(s) importado(s)" : "Nada foi importado");
                if (built && mv != null) new VideoMenu(mv).show();
            });
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (gm != null && gm.coresOpen) { gm.closeCores(); return; }
        if (gv != null && gv.editing) { gv.cancelEdit(); endLayoutEdit(); return; }
        if (inGame) { if (menuOpen) resumeGame(); else openGameMenu(); }
        else super.onBackPressed();
    }

    void save() { if (loaded && sav != null) Native.saveSram(sav); }

    void start() {
        if (!loaded || run) return;
        int sr = (int) Math.round(Native.rate());
        int mult = VideoSettings.abuf == 0 ? 1 : VideoSettings.abuf == 1 ? 2 : 4;
        int buf = Math.max(AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT) * 2, 8192) * mult;
        at = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(buf)
                .build();
        at.setVolume(VideoSettings.mute ? 0f : VideoSettings.volume / 100f);
        at.play();
        run = true;
        mark("rodar o jogo (run)");
        th = new Thread(this::loop);
        th.start();
    }

    void stop() {
        run = false;
        if (th != null) {
            try { th.join(); } catch (Exception e) { }
            th = null;
        }
        if (at != null) { at.release(); at = null; }
        Native.setSkip(false);
        unmark();
    }

    void loop() {
        short[] ab = new short[8192];
        int frame = 0, shown = 0;
        short[] ffb = new short[8192 * 8], ffo = new short[8192 * 8];
        int ffn = 0; float ffAcc = 0;
        final long T = 16743000L; // ~59.73 fps
        while (run) {
            long t0 = System.nanoTime();
            boolean f = gv.ff;
            int n = 1; if (f) { if (VideoSettings.ffSpeed == 1) { ffAcc += 1.5f; n = (int) ffAcc; ffAcc -= n; } else n = Math.max(2, VideoSettings.ffSpeed); }
            int fs = VideoSettings.frameskip; // pulo de quadros
            for (int i = 0; i < n; i++) {
                frame++;
                boolean last = i == n - 1;
                boolean skip = !last;
                if (last) { shown++; skip = fs > 0 && (shown % (fs + 1)) != 0; }
                Native.setSkip(skip);
                Native.input(gv.mask(frame));
                Native.run();
                int c = Native.audio(ab);
                if (c > 0) { if (!f) at.write(ab, 0, c); else if (ffn + c <= ffb.length) { System.arraycopy(ab, 0, ffb, ffn, c); ffn += c; } }
                if (last && !skip) gl.requestRender();
            }
            if (ffn > 0) { /* avanço rápido: junta o áudio dos quadros em um só (som acelerado) */
                int pr = ffn / 2 / n;
                for (int j = 0; j < pr; j++) {
                    int sl = 0, sr = 0;
                    for (int q = 0; q < n; q++) { sl += ffb[(j * n + q) * 2]; sr += ffb[(j * n + q) * 2 + 1]; }
                    ffo[j * 2] = (short) (sl / n);
                    ffo[j * 2 + 1] = (short) (sr / n);
                }
                if (pr > 0) at.write(ffo, 0, pr * 2);
                ffn = 0;
            }
            long r = T - (System.nanoTime() - t0);
            if (r > 0) LockSupport.parkNanos(r);
            autoNs += System.nanoTime() - t0;
            if (!VideoSettings.autoOn) autoNs = 0;
            else if (autoNs >= VideoSettings.autoSecs * 1_000_000_000L) { autoNs = 0; autoSave(); } // no intervalo escolhido
        }
    }

    @Override protected void onPause() { super.onPause(); stop(); save(); saveResume(); if (gl != null) gl.onPause(); Avancado.salvar(this); }
    @Override protected void onResume() {
        super.onResume();
        if (gl != null) gl.onResume();
        if (!built) return;
        ensureFolder();
        if (inGame && !menuOpen) start();
    }
    @Override protected void onDestroy() { super.onDestroy(); stop(); save(); saveResume(); }
}
