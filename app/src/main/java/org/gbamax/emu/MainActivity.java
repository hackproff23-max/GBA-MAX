package org.gbamax.emu;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
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
import android.view.View;
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
    static final String FOLDER = "GBA-MAX";

    GameView gv;
    FrameLayout root;
    MenuView mv;
    Uri dirUri;

    Thread th;
    volatile boolean run;
    AudioTrack at;
    boolean ready, loaded, inGame, built, asked, autoScanned;
    String sav;

    // ================= início e diagnóstico =================
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
            gv = new GameView(this);
            gv.onRom = this::showMenu;
            gv.setVisibility(View.GONE);
            mv = new MenuView(this, new MenuView.Host() {
                @Override public void play(MenuView.Game g) { playGame(g); }
                @Override public void pickDir() { chooseDir(); }
                @Override public void refresh() {
                    if (dirUri != null) scan(dirUri); else { autoScanned = false; ensureFolder(); }
                }
            });
            root.addView(gv);
            root.addView(mv);
            setContentView(root);
            built = true;
            String d = getPreferences(0).getString("dir", null);
            if (d != null) { dirUri = Uri.parse(d); scan(dirUri); }
            else mv.setGames(new ArrayList<MenuView.Game>(), null, false);
            ensureFolder();
        } catch (Throwable t) {
            showError(trace(t));
        }
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
    boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23)
            return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        return true;
    }

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
            File f = new File(Environment.getExternalStorageDirectory(), FOLDER);
            if (!f.exists() && !f.mkdirs()) { toast("Não consegui criar a pasta " + FOLDER); return; }
            if (dirUri == null && !autoScanned) { autoScanned = true; scanFolder(f); }
        } catch (Throwable t) {
            toast("Pasta " + FOLDER + ": " + t.getMessage());
        }
    }

    void scanFolder(File dir) {
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
        if (low.endsWith(".zip") || low.endsWith(".gba") || low.endsWith(".agb")) {
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
    void chooseDir() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, 1);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent d) {
        if (res != RESULT_OK || d == null || d.getData() == null) return;
        Uri u = d.getData();
        try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception e) { }
        getPreferences(0).edit().putString("dir", u.toString()).apply();
        dirUri = u;
        scan(u);
    }

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
        if (!initCore()) return;
        getPreferences(0).edit().putString("last", g.name).apply();
        try { open(g.uri); } catch (Throwable e) { toast("Erro ao abrir: " + e.getMessage()); }
    }

    void open(Uri u) throws Exception {
        stop();
        if (loaded) { save(); Native.unload(); loaded = false; }
        InputStream in = getContentResolver().openInputStream(u);
        ByteArrayOutputStream o = new ByteArrayOutputStream(1 << 22);
        byte[] t = new byte[65536];
        int n;
        while ((n = in.read(t)) != -1) o.write(t, 0, n);
        in.close();
        byte[] rom = RomReader.extract(o.toByteArray());
        sav = getFilesDir() + "/" + Integer.toHexString(Arrays.hashCode(rom)) + ".sav";
        mark("carregar a ROM (load)");
        boolean ok = Native.load(rom, "game.gba");
        unmark();
        if (!ok) { toast("ROM inválida"); return; }
        Native.loadSram(sav);
        loaded = true;
        inGame = true;
        mv.setVisibility(View.GONE);
        gv.setVisibility(View.VISIBLE);
        start();
    }

    void showMenu() {
        stop();
        save();
        inGame = false;
        gv.touch = 0; gv.ta = false; gv.tb = false;
        gv.setVisibility(View.GONE);
        mv.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        if (inGame) showMenu(); else super.onBackPressed();
    }

    void save() { if (loaded && sav != null) Native.saveSram(sav); }

    void start() {
        if (!loaded || run) return;
        int sr = (int) Math.round(Native.rate());
        int buf = Math.max(AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT) * 2, 8192);
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
        unmark();
    }

    void loop() {
        short[] ab = new short[8192];
        int frame = 0;
        final long T = 16743000L; // ~59.73 fps
        while (run) {
            long t0 = System.nanoTime();
            boolean f = gv.ff;
            int n = f ? 4 : 1; // fast-forward 4x
            for (int i = 0; i < n; i++) {
                frame++;
                Native.input(gv.mask(frame));
                Native.run();
                int c = Native.audio(ab);
                if (!f && c > 0) at.write(ab, 0, c);
            }
            gv.postInvalidate();
            long r = T - (System.nanoTime() - t0);
            if (r > 0) LockSupport.parkNanos(r);
        }
    }

    @Override protected void onPause() { super.onPause(); stop(); save(); }
    @Override protected void onResume() {
        super.onResume();
        if (!built) return;
        ensureFolder();
        if (inGame) start();
    }
    @Override protected void onDestroy() { super.onDestroy(); stop(); save(); }
}
