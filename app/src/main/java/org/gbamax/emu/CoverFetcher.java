package org.gbamax.emu;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// Identifica cada ROM e baixa SÓ a capa dos jogos que você tem (acervo libretro-thumbnails).
// Ordem, do mais leve ao mais pesado: cache -> nome exato da capa (sem baixar listas)
// -> lista de nomes (só se precisar) -> código do cabeçalho (só se precisar).
public class CoverFetcher {
    public interface Done { void onCover(MenuView.Game g, File f); }
    public interface Progress { void onProgress(int done, int total, String status); }

    // bytes baixados (capas e, só se precisar, listas) e cancelamento
    static final java.util.concurrent.atomic.AtomicLong BYTES = new java.util.concurrent.atomic.AtomicLong();
    volatile boolean cancel;
    boolean offlineMode, listLoaded, codesLoaded;

    static final String SYS = "Nintendo - Game Boy Advance";
    static final String LIST_API = "https://api.github.com/repos/libretro-thumbnails/Nintendo_-_Game_Boy_Advance/git/trees/master?recursive=1";
    static final String DAT_URL = "https://raw.githubusercontent.com/libretro/libretro-database/master/dat/Nintendo%20-%20Game%20Boy%20Advance.dat";
    static final long MAX_AGE = 30L * 24 * 3600 * 1000;

    final File dir;
    final ContentResolver cr;

    final List<String> names = new ArrayList<>();              // nomes completos das capas disponíveis
    final Map<String, List<String>> byKey = new HashMap<>();   // chave sem (região) -> nomes
    final Map<String, String> byFull = new HashMap<>();        // chave com tudo -> nome
    final Map<String, String> codes = new HashMap<>();         // código de 4 letras -> nome do jogo base
    final Map<String, Set<String>> toks = new HashMap<>();     // nome -> palavras (sem região)

    public CoverFetcher(File dir, ContentResolver cr) {
        this.dir = dir;
        this.cr = cr;
        dir.mkdirs();
    }

    // ---------------- execução ----------------
    // Só aplica capas que já estão no cache (sem usar a internet).
    public void run(List<MenuView.Game> games, Done cb) { run(games, cb, null, true); }

    public void run(List<MenuView.Game> games, Done cb, Progress pg, boolean offline) {
        int total = games.size();
        offlineMode = offline;
        RomId.garantirDat(dir, !offline);
        int done = 0;
        for (MenuView.Game g : games) {
            if (cancel) return;
            try {
                if (pg != null) pg.onProgress(done, total, g.name);
                if (g.cover != null) continue; // capa colocada por você na pasta tem prioridade
                File f = new File(dir, safe(g.name) + ".jpg");
                File old = new File(dir, safe(g.name) + ".png"); // capas baixadas em versões antigas
                File src = new File(dir, safe(g.name) + ".src");
                if (f.length() > 500 && !src.exists()) { cb.onCover(g, f); continue; } // capa importada por você tem prioridade
                if (offline && f.length() > 500 && src.exists()) { cb.onCover(g, f); continue; }
                RomId.Info ri = RomId.info(cr, g.uri, dir);
                String of = ri == null ? null : RomId.oficial(ri, g.name);
                boolean pk = (ri != null && RomId.ehPokemon(ri.code)) || norm(g.name).contains("pokemon");
                if (pk && of == null) continue; // mod/hack de Pokémon (ou ROM ainda não identificada): sem imagem
                if (of != null) { List<String> sl = src.exists() ? readLines(src) : new ArrayList<String>(); boolean mesma = !sl.isEmpty() && of.equals(sl.get(0)); if (f.length() > 500 && (offline || mesma)) { cb.onCover(g, f); continue; } if (offline) { if (old.length() > 500) cb.onCover(g, old); continue; } f.delete(); for (String c : candidatos(of)) { if (cancel) return; if (download(c, f)) { writeText(src, of); cb.onCover(g, f); break; } } continue; }
                if (f.length() > 500) { cb.onCover(g, f); continue; }
                if (old.length() > 500) { cb.onCover(g, old); continue; }
                if (offline) continue;
                // 1) tenta o nome exato da capa: não baixa nenhuma lista
                boolean ok = false;
                for (String c : candidatos(g.name)) {
                    if (cancel) return;
                    if (download(c, f)) { cb.onCover(g, f); ok = true; break; }
                }
                if (ok) continue;
                // 2) não achou: identifica pela lista de nomes / código do cabeçalho (carregados só agora)
                ensureList();
                if (names.isEmpty()) continue;
                String hit = identify(g.name, headerCode(g.uri));
                if (hit == null) continue;
                if (download(hit, f)) cb.onCover(g, f);
            } catch (Throwable t) {
            } finally { done++; }
        }
        if (pg != null) pg.onProgress(total, total, "Concluído");
    }

    void ensureList() {
        if (listLoaded || offlineMode) return;
        listLoaded = true;
        try { loadList(); } catch (Throwable t) { }
    }

    void ensureCodes() {
        if (codesLoaded || offlineMode) return;
        codesLoaded = true;
        try { loadCodes(); } catch (Throwable t) { }
    }

    /** Nomes prováveis da capa deste jogo (o acervo troca & * / : < > ? \ | " por _). */
    static List<String> candidatos(String fileName) {
        String base = clean(fileName);
        Set<String> r = new LinkedHashSet<>();
        if (base.isEmpty()) return new ArrayList<>(r);
        r.add(san(base));
        String s = strip(base);
        if (!s.isEmpty()) {
            for (String reg : new String[]{" (USA)", " (Europe)", " (USA, Europe)", " (World)", " (Japan)"})
                r.add(san(s + reg));
        }
        return new ArrayList<>(r);
    }

    static String san(String n) { return n.replaceAll("[&*/:<>?\\\\|\"]", "_"); }

    // ---------------- identificação ----------------
    String identify(String fileName, String code) {
        String base = clean(fileName);
        String k = key(base);
        // 1) nome do arquivo igual ao de uma capa
        List<String> l = byKey.get(k);
        if (l != null) return best(l);
        // 2) código do cabeçalho (ROM hack/mod usa o código do jogo base)
        if (code != null) {
            ensureCodes();
            String n = codes.get(code);
            if (n != null) {
                String h = byFull.get(key(n));
                if (h == null) {
                    List<String> l2 = byKey.get(key(strip(n)));
                    if (l2 != null) h = best(l2);
                }
                if (h != null) return h;
            }
        }
        // 3) parecido pelas palavras do nome
        Set<String> f = words(base);
        if (f.isEmpty()) return null;
        String bestName = null;
        double bs = 0;
        int bl = 0;
        for (String n : names) {
            Set<String> t = toks.get(n);
            if (t == null || t.isEmpty()) continue;
            int common = 0;
            for (String w : f) if (t.contains(w)) common++;
            if (common == 0) continue;
            double sc = (double) common / f.size();
            if (sc < .6 || (f.size() > 1 && common < 2)) continue;
            if (sc > bs || (sc == bs && t.size() < bl)) { bs = sc; bestName = n; bl = t.size(); }
        }
        return bestName;
    }

    static String best(List<String> l) {
        String r = l.get(0);
        int rs = rank(r);
        for (String s : l) { int x = rank(s); if (x > rs) { r = s; rs = x; } }
        return r;
    }

    static int rank(String s) {
        int r = s.contains("(USA") ? 30 : s.contains("(Europe") || s.contains("(World") ? 20 : 10;
        if (s.contains("(Rev") || s.contains("(Beta") || s.contains("(Proto") || s.contains("(Demo")
                || s.contains("(Sample") || s.contains("(Unl")) r -= 5;
        return r;
    }

    static String clean(String n) {
        String s = n.replaceAll("\\[[^\\]]*\\]", " ").replace('_', ' ');
        return s.trim();
    }

    static String strip(String n) { return n.replaceAll("\\s*\\([^)]*\\)", "").trim(); }

    static String key(String n) { return strip(n).replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT); }

    static String fullKey(String n) { return n.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT); }

    static Set<String> words(String n) {
        Set<String> r = new HashSet<>();
        for (String w : strip(n).toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) if (w.length() > 0) r.add(w);
        return r;
    }

    static String safe(String n) {
        return n.replaceAll("[^A-Za-z0-9._-]", "_") + "_" + Integer.toHexString(n.hashCode());
    }

    // ---------------- cabeçalho da ROM ----------------
    String headerCode(Uri u) {
        if (u == null) return null;
        try (InputStream in = cr.openInputStream(u)) {
            if (in == null) return null;
            byte[] b = new byte[0xB0];
            int got = readFully(in, b, 2);
            if (got < 2) return null;
            if (b[0] == 'P' && b[1] == 'K') {
                try (InputStream in2 = cr.openInputStream(u); ZipInputStream z = new ZipInputStream(in2)) {
                    ZipEntry e;
                    while ((e = z.getNextEntry()) != null) {
                        String n = e.getName().toLowerCase(Locale.ROOT);
                        if (!e.isDirectory() && (n.endsWith(".gba") || n.endsWith(".agb") || n.endsWith(".bin"))) {
                            byte[] h = new byte[0xB0];
                            if (readFully(z, h, h.length) < h.length) return null;
                            return code(h);
                        }
                    }
                }
                return null;
            }
            int more = readFully(in, b, b.length - got, got);
            if (got + more < b.length) return null;
            return code(b);
        } catch (Throwable t) { return null; }
    }

    static int readFully(InputStream in, byte[] b, int n) throws IOException { return readFully(in, b, n, 0); }

    static int readFully(InputStream in, byte[] b, int n, int off) throws IOException {
        int t = 0;
        while (t < n) {
            int r = in.read(b, off + t, n - t);
            if (r < 0) break;
            t += r;
        }
        return t;
    }

    static String code(byte[] h) {
        String c = new String(h, 0xAC, 4, java.nio.charset.StandardCharsets.US_ASCII);
        return c.matches("[A-Z0-9]{4}") ? c : null;
    }

    // ---------------- listas (com cache; só baixadas se alguma capa não for achada pelo nome) ----------------
    void loadList() throws IOException {
        File f = new File(dir, "_list.txt");
        if (!offlineMode && (!f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE)) {
            try {
                String js = httpText(LIST_API);
                JSONArray a = new JSONObject(js).getJSONArray("tree");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < a.length(); i++) {
                    String p = a.getJSONObject(i).optString("path");
                    if (p.startsWith("Named_Boxarts/") && p.endsWith(".png"))
                        sb.append(p, 14, p.length() - 4).append('\n');
                }
                if (sb.length() > 0) writeText(f, sb.toString());
            } catch (Throwable t) { }
        }
        if (!f.exists()) return;
        for (String s : readLines(f)) addName(s);
    }

    void loadCodes() {
        File f = new File(dir, "_codes.txt");
        if (!offlineMode && (!f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE)) {
            try {
                String dat = httpText(DAT_URL);
                Map<String, String> m = new HashMap<>();
                Pattern pn = Pattern.compile("name \"([^\"]+)\"");
                Pattern ps = Pattern.compile("serial \"AGB-([A-Z0-9]{4})");
                for (String blk : dat.split("\ngame \\(")) {
                    Matcher a = pn.matcher(blk), b = ps.matcher(blk);
                    if (!a.find() || !b.find()) continue;
                    String n = a.group(1).replaceAll("\\.(gba|agb)$", "");
                    String c = b.group(1);
                    String old = m.get(c);
                    if (old == null || rank(n) > rank(old)) m.put(c, n);
                }
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> e : m.entrySet()) sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
                if (sb.length() > 0) writeText(f, sb.toString());
            } catch (Throwable t) { }
        }
        if (!f.exists()) return;
        for (String s : readLines(f)) {
            int i = s.indexOf('\t');
            if (i == 4) codes.put(s.substring(0, 4), s.substring(5));
        }
    }

    void addName(String n) {
        if (n.isEmpty()) return;
        names.add(n);
        String k = key(n);
        List<String> l = byKey.get(k);
        if (l == null) { l = new ArrayList<>(); byKey.put(k, l); }
        l.add(n);
        byFull.put(fullKey(n), n);
        toks.put(n, words(n));
    }

    // ---------------- rede e arquivos ----------------
    boolean download(String name, File out) {
        try {
            String u = "https://thumbnails.libretro.com/" + enc(SYS) + "/Named_Boxarts/" + enc(name) + ".png";
            byte[] d = httpBytes(u);
            if (d.length < 500) return false;
            Bitmap src = BitmapFactory.decodeByteArray(d, 0, d.length);
            if (src == null) return false;
            // 256x256, JPEG (fundo branco no lugar de transparência)
            Bitmap dst = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(dst);
            cv.drawColor(Color.WHITE);
            Paint pt = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            cv.drawBitmap(src, null, new Rect(0, 0, 256, 256), pt);
            src.recycle();
            File tmp = new File(out.getPath() + ".tmp");
            boolean ok;
            try (FileOutputStream o = new FileOutputStream(tmp)) { ok = dst.compress(Bitmap.CompressFormat.JPEG, 90, o); }
            dst.recycle();
            return ok && tmp.renameTo(out);
        } catch (Throwable t) { return false; }
    }

    static String enc(String s) throws IOException { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
    static final String PB = "https://pokebat.net/wp-json/wp/v2/";
    static final Set<String> PB_STOP = new HashSet<>(java.util.Arrays.asList("pokemon", "version", "gba", "rom", "hack", "the", "of", "br", "pt", "ptbr", "english", "translation", "traducao", "portugues", "full", "final", "beta", "demo"));
    static String norm(String s) { return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT); }
    static List<String> pbTokens(String s) { List<String> r = new ArrayList<>(); for (String w : norm(strip(clean(s))).split("[^a-z0-9]+")) if (!w.isEmpty() && !PB_STOP.contains(w) && !w.matches("v\\d+")) r.add(w); return r; }
    boolean ehPoke(MenuView.Game g) { if (norm(g.name).contains("pokemon")) return true; String c = headerCode(g.uri); return c != null && (c.startsWith("AXV") || c.startsWith("AXP") || c.startsWith("BPE") || c.startsWith("BPR") || c.startsWith("BPG")); }
    boolean pokebat(String name, File out) throws Exception {
        List<String> q = pbTokens(name);
        if (q.isEmpty()) return false;
        JSONArray res = null;
        for (int n = Math.min(3, q.size()); n >= 1; n--) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append(i > 0 ? " " : "").append(q.get(i));
            res = new JSONArray(httpText(PB + "search?subtype=post&per_page=8&_fields=id,title,url&search=" + enc(sb.toString())));
            if (res.length() > 0) break;
        }
        if (res == null || res.length() == 0) return false;
        int bestId = 0;
        double bs = 0;
        for (int i = 0; i < res.length(); i++) {
            JSONObject o = res.getJSONObject(i);
            Set<String> t = new HashSet<>(pbTokens(android.text.Html.fromHtml(o.optString("title")).toString()));
            int common = 0;
            for (String w : q) if (t.contains(w)) common++;
            double sc = (double) common / q.size();
            if (common == 0 || sc < .6 || (q.size() > 1 && common < 2)) continue;
            if (sc > bs) { bs = sc; bestId = o.optInt("id"); }
        }
        if (bestId == 0) return false;
        JSONObject p = new JSONObject(httpText(PB + "posts/" + bestId + "?_fields=featured_media,link"));
        String img = null;
        int fm = p.optInt("featured_media");
        if (fm > 0) img = new JSONObject(httpText(PB + "media/" + fm + "?_fields=source_url")).optString("source_url", null);
        if (img == null || img.isEmpty()) {
            Matcher m = Pattern.compile("property=\"og:image\"\\s+content=\"([^\"]+)\"").matcher(httpText(p.optString("link")));
            if (m.find()) img = m.group(1);
        }
        if (img == null || img.isEmpty()) return false;
        return saveAspect(httpBytes(img), out);
    }
    boolean saveAspect(byte[] d, File out) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(d, 0, d.length, o);
            if (o.outWidth <= 0) return false;
            int ss = 1;
            while (Math.max(o.outWidth, o.outHeight) / (ss * 2) >= 1024) ss *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = ss;
            Bitmap src = BitmapFactory.decodeByteArray(d, 0, d.length, o);
            if (src == null) return false;
            float s = Math.min(1f, 512f / Math.max(src.getWidth(), src.getHeight()));
            Bitmap dst = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(src.getWidth() * s)), Math.max(1, Math.round(src.getHeight() * s)), true);
            if (dst != src) src.recycle();
            File tmp = new File(out.getPath() + ".tmp");
            boolean ok;
            try (FileOutputStream fo = new FileOutputStream(tmp)) { ok = dst.compress(Bitmap.CompressFormat.JPEG, 90, fo); }
            dst.recycle();
            return ok && tmp.renameTo(out);
        } catch (Throwable t) { return false; }
    }

    static byte[] httpBytes(String u) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) GBA-MAX");
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] t = new byte[16384];
            int n;
            while ((n = in.read(t)) != -1) { o.write(t, 0, n); BYTES.addAndGet(n); }
            return o.toByteArray();
        } finally { c.disconnect(); }
    }

    static String httpText(String u) throws IOException { return new String(httpBytes(u), "UTF-8"); }

    static void writeText(File f, String s) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes("UTF-8")); }
    }

    static List<String> readLines(File f) {
        List<String> r = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String s;
            while ((s = br.readLine()) != null) r.add(s);
        } catch (IOException e) { }
        return r;
    }
}
