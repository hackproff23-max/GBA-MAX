package org.gbamax.emu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Baixa as capas em segundo plano (continua mesmo com o app minimizado). */
public class CoverService extends Service {
    static final String CH = "covers", CANCEL = "org.gbamax.emu.CANCEL_COVERS";

    static volatile boolean running;
    static volatile int total, done;
    static volatile long startMs, endMs;
    static volatile String status = "";
    static volatile CoverFetcher.Done listener;
    static volatile CoverFetcher current;
    static List<MenuView.Game> pending;

    public static void start(Context c, List<MenuView.Game> l) {
        if (running) return;
        pending = new ArrayList<>(l);
        total = l.size(); done = 0; status = "Iniciando..."; startMs = System.currentTimeMillis(); endMs = 0;
        running = true;
        Intent i = new Intent(c, CoverService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable t) { running = false; }
    }

    public static void cancel() { CoverFetcher f = current; if (f != null) f.cancel = true; }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent in, int flags, int id) {
        if (in != null && CANCEL.equals(in.getAction())) { cancel(); return START_NOT_STICKY; }
        final List<MenuView.Game> l = pending;
        if (l == null) { running = false; stopSelf(); return START_NOT_STICKY; }
        pending = null;
        note(0, l.size(), "Iniciando...");
        new Thread(() -> {
            CoverFetcher.BYTES.set(0);
            CoverFetcher f = new CoverFetcher(Pastas.capas(this), getContentResolver());
            current = f;
            try {
                f.run(l, (g, file) -> { CoverFetcher.Done d = listener; if (d != null) d.onCover(g, file); },
                        (d, t, s) -> { done = d; total = t; status = s; note(d, t, s); }, false);
            } catch (Throwable t) { }
            endMs = System.currentTimeMillis();
            status = f.cancel ? "Cancelado" : "Concluído";
            current = null;
            running = false;
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
            stopSelf();
        }).start();
        return START_NOT_STICKY;
    }

    void note(int d, int t, String s) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(new NotificationChannel(CH, "Capas dos jogos", NotificationManager.IMPORTANCE_LOW));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Baixando capas dos jogos")
                    .setContentText(d + "/" + t + " · " + s)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(Math.max(t, 1), d, false);
            Notification n = b.build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(7, n);
        } catch (Throwable e) { }
    }

    @Override public void onDestroy() { running = false; super.onDestroy(); }
}
