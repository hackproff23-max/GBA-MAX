package org.gbamax.emu;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.LockSupport;

public class MainActivity extends Activity {
    GameView gv;
    FrameLayout root;
    LinearLayout menu;
    TextView info;
    ListView lv;
    ArrayAdapter<String> ad;
    final List<String> names = new ArrayList<>();
    final List<Uri> uris = new ArrayList<>();

    Thread th;
    volatile boolean run;
    AudioTrack at;
    boolean ready, loaded, inGame;
    String sav;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        gv = new GameView(this);
        gv.onRom = this::showMenu;
        gv.setVisibility(View.GONE);
        buildMenu();
        root.addView(gv);
        root.addView(menu);
        setContentView(root);
        ready = Native.init(getApplicationInfo().nativeLibraryDir + "/libmgba_core.so",
                getFilesDir().getAbsolutePath());
        if (!ready) toast("Core mGBA não encontrado");
        String d = getPreferences(0).getString("dir", null);
        if (d != null) scan(Uri.parse(d));
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    void buildMenu() {
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackgroundColor(Color.BLACK);
        int pd = dp(16);
        menu.setPadding(pd, pd * 2, pd, pd);

        TextView t = new TextView(this);
        t.setText("GBA-MAX");
        t.setTextSize(28);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);

        Button bt = new Button(this);
        bt.setText("Selecionar pasta de ROMs");
        bt.setOnClickListener(v -> pickDir());

        info = new TextView(this);
        info.setTextColor(0xFFAAAAAA);
        info.setText("Escolha a pasta onde estão seus jogos .zip");
        info.setPadding(0, pd / 2, 0, pd / 2);

        lv = new ListView(this);
        ad = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        lv.setAdapter(ad);
        lv.setOnItemClickListener((p, v, i, id) -> play(uris.get(i)));

        menu.addView(t);
        menu.addView(bt);
        menu.addView(info);
        menu.addView(lv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    void pickDir() {
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
        scan(u);
    }

    // lista só os .zip da pasta (e subpastas, até 3 níveis)
    void scan(Uri tree) {
        TreeMap<String, Uri> m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try {
            walk(tree, DocumentsContract.getTreeDocumentId(tree), m, 0);
        } catch (Exception e) {
            names.clear(); uris.clear();
            ad.notifyDataSetChanged();
            info.setText("Não consegui ler a pasta. Selecione de novo.");
            return;
        }
        names.clear(); uris.clear();
        for (Map.Entry<String, Uri> e : m.entrySet()) { names.add(e.getKey()); uris.add(e.getValue()); }
        ad.notifyDataSetChanged();
        info.setText(names.isEmpty() ? "Nenhum jogo .zip encontrado nesta pasta"
                : names.size() + " jogos (.zip) — toque para jogar");
    }

    void walk(Uri tree, String id, Map<String, Uri> m, int depth) {
        Uri ch = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        try (Cursor c = getContentResolver().query(ch, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String did = c.getString(0), n = c.getString(1), mt = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mt)) {
                    if (depth < 3) walk(tree, did, m, depth + 1);
                } else if (n != null && n.toLowerCase().endsWith(".zip")) {
                    m.put(n.substring(0, n.length() - 4), DocumentsContract.buildDocumentUriUsingTree(tree, did));
                }
            }
        }
    }

    void play(Uri u) {
        if (!ready) { toast("Core mGBA não encontrado"); return; }
        try { open(u); } catch (Exception e) { toast("Erro ao abrir: " + e.getMessage()); }
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
        if (!Native.load(rom, "game.gba")) { toast("ROM inválida"); return; }
        Native.loadSram(sav);
        loaded = true;
        inGame = true;
        menu.setVisibility(View.GONE);
        gv.setVisibility(View.VISIBLE);
        start();
    }

    void showMenu() {
        stop();
        save();
        inGame = false;
        gv.touch = 0; gv.ta = false; gv.tb = false;
        gv.setVisibility(View.GONE);
        menu.setVisibility(View.VISIBLE);
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
    @Override protected void onResume() { super.onResume(); if (inGame) start(); }
    @Override protected void onDestroy() { super.onDestroy(); stop(); save(); }
}
