package org.gbamax.emu;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Guarda TODAS as configurações do emulador (vídeo, áudio, controle, avançado, layout, formatos,
 * tema...) em GBA-MAX/Configurações/configuracoes.json e restaura esse arquivo depois de
 * desinstalar e instalar de novo. Qualquer mudança nas configurações é salva sozinha.
 */
final class Backup {
    static final String ARQ = "configuracoes.json";
    static final String FLAG = "gbamax_backup"; // só marca se já restaurou neste aparelho

    static final Map<String, SharedPreferences.OnSharedPreferenceChangeListener> L = new HashMap<>();
    static final Handler H = new Handler(Looper.getMainLooper());
    static Context app;

    static final Runnable W = new Runnable() {
        @Override public void run() {
            final Context c = app;
            if (c == null) return;
            final File f = arquivo(c);
            if (f == null) return;
            final String js = montar(c);
            new Thread(new Runnable() { @Override public void run() { escrever(f, js); } }).start();
        }
    };

    static File arquivo(Context c) {
        try {
            File d = Pastas.dir(c, Pastas.CONFIG);
            return d == null ? null : new File(d, ARQ);
        } catch (Throwable t) { return null; }
    }

    static boolean ignorar(String n) {
        return n.equals(FLAG) || n.contains("WebView") || n.startsWith("com.google") || n.startsWith("androidx")
                || n.contains("firebase") || n.contains("analytics") || n.contains("MultiDex");
    }

    static String[] nomes(Context c) {
        File d = new File(c.getApplicationInfo().dataDir, "shared_prefs");
        String[] a = d.list();
        if (a == null) return new String[0];
        java.util.List<String> r = new java.util.ArrayList<>();
        for (String s : a) {
            if (!s.endsWith(".xml")) continue;
            String n = s.substring(0, s.length() - 4);
            if (!ignorar(n)) r.add(n);
        }
        return r.toArray(new String[0]);
    }

    /** Passa a salvar sozinho sempre que qualquer configuração mudar. */
    static void observar(Context c) {
        app = c.getApplicationContext();
        for (String n : nomes(app)) {
            if (L.containsKey(n)) continue;
            SharedPreferences.OnSharedPreferenceChangeListener l = new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(SharedPreferences p, String k) { agendar(); }
            };
            L.put(n, l); // guardado aqui para o Android não descartar
            app.getSharedPreferences(n, 0).registerOnSharedPreferenceChangeListener(l);
        }
    }

    static void agendar() {
        H.removeCallbacks(W);
        H.postDelayed(W, 1500);
    }

    /** Restaura o arquivo uma única vez (instalação nova). Se a pasta ainda não está acessível, tenta de novo depois. */
    static void iniciar(Context c) {
        c = c.getApplicationContext();
        SharedPreferences fl = c.getSharedPreferences(FLAG, 0);
        if (!fl.getBoolean("ok", false)) {
            File f = arquivo(c);
            if (f == null) return;
            if (f.isFile()) restaurar(c, f);
            fl.edit().putBoolean("ok", true).apply();
        }
        observar(c);
    }

    /** Salva agora. Devolve true se acabou de restaurar do arquivo (aí as configurações devem ser recarregadas). */
    static boolean salvar(Context c) {
        c = c.getApplicationContext();
        boolean foi = !c.getSharedPreferences(FLAG, 0).getBoolean("ok", false);
        iniciar(c);
        boolean restaurou = foi && c.getSharedPreferences(FLAG, 0).getBoolean("ok", false) && ultimaRestauracao;
        File f = arquivo(c);
        if (f != null && c.getSharedPreferences(FLAG, 0).getBoolean("ok", false) && !restaurou) escrever(f, montar(c));
        return restaurou;
    }

    static boolean ultimaRestauracao;

    static String montar(Context c) {
        try {
            JSONObject raiz = new JSONObject(), ps = new JSONObject();
            for (String n : nomes(c)) {
                JSONObject o = new JSONObject();
                for (Map.Entry<String, ?> e : c.getSharedPreferences(n, 0).getAll().entrySet()) {
                    Object v = e.getValue();
                    JSONObject x = new JSONObject();
                    if (v instanceof Boolean) { x.put("t", "b"); x.put("v", v); }
                    else if (v instanceof Integer) { x.put("t", "i"); x.put("v", v); }
                    else if (v instanceof Long) { x.put("t", "l"); x.put("v", v); }
                    else if (v instanceof Float) { x.put("t", "f"); x.put("v", ((Float) v).doubleValue()); }
                    else if (v instanceof String) { x.put("t", "s"); x.put("v", v); }
                    else if (v instanceof Set) { x.put("t", "S"); x.put("v", new JSONArray((Set<?>) v)); }
                    else continue;
                    o.put(e.getKey(), x);
                }
                if (o.length() > 0) ps.put(n, o);
            }
            raiz.put("versao", 1);
            raiz.put("prefs", ps);
            return raiz.toString(2);
        } catch (Throwable t) { return null; }
    }

    static void escrever(File f, String js) {
        if (js == null) return;
        try {
            File p = f.getParentFile();
            if (p != null) p.mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            try (OutputStream w = new FileOutputStream(tmp)) { w.write(js.getBytes("UTF-8")); }
            if (f.exists() && !f.delete()) return;
            tmp.renameTo(f);
        } catch (Throwable t) { }
    }

    static boolean restaurar(Context c, File f) {
        ultimaRestauracao = false;
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(f)) {
                byte[] t = new byte[8192];
                int k;
                while ((k = in.read(t)) != -1) b.write(t, 0, k);
            }
            JSONObject ps = new JSONObject(b.toString("UTF-8")).getJSONObject("prefs");
            for (Iterator<String> it = ps.keys(); it.hasNext(); ) {
                String n = it.next();
                if (ignorar(n)) continue;
                JSONObject o = ps.getJSONObject(n);
                SharedPreferences.Editor ed = c.getSharedPreferences(n, 0).edit();
                for (Iterator<String> ki = o.keys(); ki.hasNext(); ) {
                    String k = ki.next();
                    JSONObject x = o.getJSONObject(k);
                    switch (x.getString("t")) {
                        case "b": ed.putBoolean(k, x.getBoolean("v")); break;
                        case "i": ed.putInt(k, x.getInt("v")); break;
                        case "l": ed.putLong(k, x.getLong("v")); break;
                        case "f": ed.putFloat(k, (float) x.getDouble("v")); break;
                        case "s": ed.putString(k, x.getString("v")); break;
                        case "S": {
                            JSONArray a = x.getJSONArray("v");
                            java.util.HashSet<String> s = new java.util.HashSet<>();
                            for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
                            ed.putStringSet(k, s);
                            break;
                        }
                    }
                }
                ed.commit();
            }
            ultimaRestauracao = true;
            return true;
        } catch (Throwable t) { return false; }
    }
}
