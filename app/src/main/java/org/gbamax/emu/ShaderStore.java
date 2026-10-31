package org.gbamax.emu;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Shaders locais, importação e download do repositório libretro. */
final class ShaderStore {
    static final String[] REPOS = {"libretro/glsl-shaders", "libretro/slang-shaders"};
    static final String[] TAGS = {"glsl", "slang"};
    static final Pattern INC = Pattern.compile("^\\s*#include\\s+\"([^\"]+)\"");

    static File root(Context c) {
        File f = new File(c.getFilesDir(), "shaders");
        f.mkdirs();
        return f;
    }

    static boolean isPreset(String n) {
        n = n.toLowerCase();
        return n.endsWith(".glslp") || n.endsWith(".slangp");
    }

    static boolean isShader(String n) {
        n = n.toLowerCase();
        return n.endsWith(".glsl") || n.endsWith(".slang");
    }

    // ---------- lista local ----------
    static List<String> listLocal(Context c) {
        File r = root(c);
        List<String> out = new ArrayList<>();
        walk(r, r, out, 0);
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    static void walk(File root, File d, List<String> out, int depth) {
        File[] a = d.listFiles();
        if (a == null) return;
        for (File f : a) {
            if (f.isDirectory()) {
                if (depth < 10) walk(root, f, out, depth + 1);
                continue;
            }
            String n = f.getName();
            // arquivos .glsl/.slang dentro de pastas "shaders" são só dependências de presets
            boolean ok = isPreset(n) || (isShader(n) && !"shaders".equalsIgnoreCase(d.getName()));
            if (ok) out.add(f.getAbsolutePath().substring(root.getAbsolutePath().length() + 1));
        }
    }

    // ---------- importar ----------
    static String displayName(Context c, Uri u) {
        try (Cursor q = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst() && q.getString(0) != null) return q.getString(0);
        } catch (Exception e) { }
        String p = u.getLastPathSegment();
        return p == null ? "shader.glsl" : p.substring(p.lastIndexOf('/') + 1);
    }

    static String safe(String n) { return n.replaceAll("[\\\\/:*?\"<>|]", "_"); }

    static int importUris(Context c, List<Uri> us) {
        int n = 0;
        for (Uri u : us) {
            String name = safe(displayName(c, u));
            try (InputStream in = c.getContentResolver().openInputStream(u)) {
                if (in == null) continue;
                if (name.toLowerCase().endsWith(".zip")) {
                    String b = name.substring(0, name.length() - 4);
                    n += unzip(in, new File(root(c), "imported/" + b));
                } else {
                    copy(in, new File(root(c), "imported/" + name));
                    n++;
                }
            } catch (Exception e) { }
        }
        return n;
    }

    static void copy(InputStream in, File out) throws IOException {
        File p = out.getParentFile();
        if (p != null) p.mkdirs();
        try (OutputStream o = new FileOutputStream(out)) {
            byte[] t = new byte[16384];
            int k;
            while ((k = in.read(t)) != -1) o.write(t, 0, k);
        }
    }

    static int unzip(InputStream in, File dest) throws IOException {
        int n = 0;
        String base = dest.getCanonicalPath() + File.separator;
        ZipInputStream z = new ZipInputStream(in);
        ZipEntry e;
        while ((e = z.getNextEntry()) != null) {
            File f = new File(dest, e.getName());
            if (!f.getCanonicalPath().startsWith(base)) continue; // proteção contra zip-slip
            if (e.isDirectory()) { f.mkdirs(); continue; }
            File p = f.getParentFile();
            if (p != null) p.mkdirs();
            try (OutputStream o = new FileOutputStream(f)) {
                byte[] t = new byte[16384];
                int k;
                while ((k = z.read(t)) != -1) o.write(t, 0, k);
            }
            n++;
        }
        return n;
    }

    // ---------- download (libretro) ----------
    static byte[] http(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setRequestProperty("User-Agent", "GBA-MAX");
        h.setInstanceFollowRedirects(true);
        if (h.getResponseCode() != 200) throw new IOException("HTTP " + h.getResponseCode() + " " + url);
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] t = new byte[16384];
            int k;
            while ((k = in.read(t)) != -1) o.write(t, 0, k);
            return o.toByteArray();
        } finally { h.disconnect(); }
    }

    /** Lista os presets/shaders do repositório; branch[0] recebe o ramo usado. */
    static List<String> repoIndex(String repo, String[] branch) throws Exception {
        Exception last = null;
        for (String br : new String[]{"master", "main"}) {
            try {
                byte[] b = http("https://api.github.com/repos/" + repo + "/git/trees/" + br + "?recursive=1");
                JSONArray t = new JSONObject(new String(b, "UTF-8")).getJSONArray("tree");
                List<String> out = new ArrayList<>();
                for (int i = 0; i < t.length(); i++) {
                    JSONObject o = t.getJSONObject(i);
                    if (!"blob".equals(o.optString("type"))) continue;
                    String p = o.getString("path");
                    String low = p.toLowerCase();
                    String[] parts = p.split("/");
                    String dir = parts.length > 1 ? parts[parts.length - 2].toLowerCase() : "";
                    if (low.endsWith(".glslp") || low.endsWith(".slangp")) out.add(p);
                    else if ((low.endsWith(".glsl") || low.endsWith(".slang")) && !dir.equals("shaders") && !dir.equals("include") && parts.length > 0)
                        out.add(p);
                }
                Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
                branch[0] = br;
                return out;
            } catch (Exception e) { last = e; }
        }
        throw last != null ? last : new IOException("sem resposta");
    }

    /** Baixa o arquivo e tudo que ele referencia. Devolve o caminho relativo dentro de shaders/. */
    static String download(Context c, int repoIdx, String branch, String path) throws IOException {
        File base = new File(root(c), TAGS[repoIdx]);
        fetch(base, REPOS[repoIdx], branch, path, new HashSet<String>(), 0, true);
        return TAGS[repoIdx] + "/" + path;
    }

    static final Pattern KV = Pattern.compile("^\\s*([A-Za-z0-9_\\-]+)\\s*=\\s*\"?([^\"]*?)\"?\\s*(#.*)?$");
    static final Pattern REF = Pattern.compile("^\\s*#reference\\s+\"?([^\"]+?)\"?\\s*$");

    static void fetch(File base, String repo, String br, String path, Set<String> seen, int depth, boolean must) throws IOException {
        if (!seen.add(path) || depth > 12) return;
        byte[] data;
        try {
            data = http("https://raw.githubusercontent.com/" + repo + "/" + br + "/" + path.replace(" ", "%20"));
        } catch (IOException e) {
            if (must) throw e;
            return;
        }
        File out = new File(base, path);
        File p = out.getParentFile();
        if (p != null) p.mkdirs();
        try (OutputStream o = new FileOutputStream(out)) { o.write(data); }
        String low = path.toLowerCase();
        String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
        List<String> deps = new ArrayList<>();
        if (isPreset(low)) {
            Map<String, String> kv = new HashMap<>();
            for (String line : new String(data, "UTF-8").split("\n")) {
                line = line.replace("\r", "");
                Matcher r = REF.matcher(line);
                if (r.find()) { deps.add(r.group(1)); continue; }
                Matcher m = KV.matcher(line);
                if (m.find()) kv.put(m.group(1), m.group(2));
            }
            for (Map.Entry<String, String> e : kv.entrySet())
                if (e.getKey().matches("shader\\d+")) deps.add(e.getValue());
            String tx = kv.get("textures");
            if (tx != null) for (String nm : tx.split(";")) {
                String v = kv.get(nm.trim());
                if (v != null) deps.add(v);
            }
        } else if (isShader(low) || low.endsWith(".inc") || low.endsWith(".h")) {
            for (String line : new String(data, "UTF-8").split("\n")) {
                Matcher m = INC.matcher(line);
                if (m.find()) deps.add(m.group(1));
            }
        }
        for (String d : deps) fetch(base, repo, br, norm(dir, d.trim()), seen, depth + 1, false);
    }

    static String norm(String dir, String ref) {
        List<String> st = new ArrayList<>();
        String all = (dir.isEmpty() ? "" : dir + "/") + ref;
        for (String s : all.split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) { if (!st.isEmpty()) st.remove(st.size() - 1); }
            else st.add(s);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : st) { if (sb.length() > 0) sb.append('/'); sb.append(s); }
        return sb.toString();
    }
}
