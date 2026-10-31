package org.gbamax.emu;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;

public class MainActivity extends Activity {
    GameView gv;
    Thread th;
    volatile boolean run;
    AudioTrack at;
    boolean ready, loaded;
    String sav;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        gv = new GameView(this);
        gv.onRom = this::pick;
        setContentView(gv);
        ready = Native.init(getApplicationInfo().nativeLibraryDir + "/libmgba_core.so",
                getFilesDir().getAbsolutePath());
        if (!ready) { toast("Core mGBA não encontrado"); return; }
        String last = getPreferences(0).getString("rom", null);
        if (last == null) pick();
        else {
            try { open(Uri.parse(last)); } catch (Exception e) { pick(); }
        }
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

    void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, 1);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent d) {
        if (res != RESULT_OK || d == null || d.getData() == null) return;
        Uri u = d.getData();
        try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception e) { }
        try {
            open(u);
            getPreferences(0).edit().putString("rom", u.toString()).apply();
        } catch (Exception e) { toast("Erro ao abrir a ROM"); }
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
        byte[] rom = o.toByteArray();
        sav = getFilesDir() + "/" + Integer.toHexString(Arrays.hashCode(rom)) + ".sav";
        if (!Native.load(rom, "game.gba")) { toast("ROM inválida (use .gba)"); return; }
        Native.loadSram(sav);
        loaded = true;
        start();
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
    @Override protected void onResume() { super.onResume(); start(); }
    @Override protected void onDestroy() { super.onDestroy(); stop(); save(); }
}
