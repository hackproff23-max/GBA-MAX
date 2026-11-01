package org.gbamax.emu;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Lê o cabeçalho e o CRC32 da ROM e compara com o acervo libretro: descobre qual jogo é de verdade (original x mod). */
final class RomId {
    static final class Info { String code = ""; String title = ""; long size; String crc = ""; }

    static final Object LOCK = new Object();
    static final Map<String, Info> mem = new HashMap<>();
    static boolean memCarregado, datCarregado;
    static Map<String, String[]> byCrc = new HashMap<>();         // crc -> {nome, codigo, tamanho}
    static Map<String, List<String[]>> byCode = new HashMap<>();  // codigo -> [{nome, tamanho}]

    static boolean ehPokemon(String c) {
        return c != null && (c.startsWith("AXV") || c.startsWith("AXP") || c.startsWith("BPE")
                || c.startsWith("BPR") || c.startsWith("BPG") || c.startsWith("B24"));
    }

    /** Informações da ROM (com cache em disco por arquivo). Pode demorar na primeira vez. */
    static Info info(ContentResolver cr, Uri u, File dir) {
        if (u == null) return null;
        String chave = null;
        if ("file".equals(u.getScheme())) {
            File f = new File(u.getPath());
            chave = f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
        }
        synchronized (LOCK) {
            carregarCache(dir);
            if (chave != null) { Info i = mem.get(chave); if (i != null) return i; }
        }
        Info i = ler(cr, u);
        if (i == null) return null;
        if (chave != null) synchronized (LOCK) { mem.put(chave, i); anexar(dir, chave, i); }
        return i;
    }

    static Info ler(ContentResolver cr, Uri u) {
        try (InputStream raw = cr.openInputStream(u)) {
            if (raw == null) return null;
            BufferedInputStream bin = new BufferedInputStream(raw, 65536);
            bin.mark(4);
            int a = bin.read(), b = bin.read();
            bin.reset();
            InputStream rom = bin;
            if (a == 'P' && b == 'K') {
                ZipInputStream z = new ZipInputStream(bin);
                ZipEntry e;
                boolean achou = false;
                while ((e = z.getNextEntry()) != null) {
                    String n = e.getName().toLowerCase(Locale.ROOT);
                    if (!e.isDirectory() && (n.endsWith(".gba") || n.endsWith(".agb") || n.endsWith(".bin"))) { achou = true; break; }
                }
                if (!achou) return null;
                rom = z;
            }
            byte[] h = new byte[0xC0];
            int got = 0, n;
            long size = 0;
            CRC32 c = new CRC32();
            byte[] t = new byte[65536];
            while ((n = rom.read(t)) != -1) {
                if (got < h.length) { int k = Math.min(n, h.length - got); System.arraycopy(t, 0, h, got, k); got += k; }
                c.update(t, 0, n);
                size += n;
            }
            if (got < h.length) return null;
            Info i = new Info();
            i.size = size;
            i.crc = String.format(Locale.ROOT, "%08X", c.getValue());
            String code = new String(h, 0xAC, 4, StandardCharsets.US_ASCII);
            i.code = code.matches("[A-Z0-9]{4}") ? code : "";
            i.title = new String(h, 0xA0, 12, StandardCharsets.US_ASCII).replaceAll("[^\\x20-\\x7E]", "").trim();
            return i;
        } catch (Throwable t) { return null; }
    }

    static void carregarCache(File dir) {
        if (memCarregado) return;
        memCarregado = true;
        File f = new File(dir, "_rom.txt");
        if (!f.exists()) return;
        for (String s : CoverFetcher.readLines(f)) {
            String[] p = s.split("\t", 5);
            if (p.length < 4) continue;
            Info i = new Info();
            i.code = p[1];
            i.crc = p[2];
            try { i.size = Long.parseLong(p[3]); } catch (Exception e) { continue; }
            i.title = p.length > 4 ? p[4] : "";
            mem.put(p[0], i);
        }
    }

    static void anexar(File dir, String chave, Info i) {
        try (FileOutputStream o = new FileOutputStream(new File(dir, "_rom.txt"), true)) {
            o.write((chave + "\t" + i.code + "\t" + i.crc + "\t" + i.size + "\t" + i.title.replace('\t', ' ') + "\n").getBytes("UTF-8"));
        } catch (Throwable t) { }
    }

    /** Baixa (no máximo 1x por mês, só se online) e carrega a lista oficial de ROMs: CRC, tamanho, código e nome. */
    static void garantirDat(File dir, boolean online) {
        synchronized (LOCK) {
            File f = new File(dir, "_dat.txt");
            if (online && (!f.exists() || System.currentTimeMillis() - f.lastModified() > CoverFetcher.MAX_AGE)) {
                try {
                    String dat = CoverFetcher.httpText(CoverFetcher.DAT_URL);
                    StringBuilder sb = new StringBuilder();
                    Pattern pn = Pattern.compile("name \"([^\"]+)\""), pc = Pattern.compile("crc ([0-9A-Fa-f]{8})"),
                            ps = Pattern.compile("size (\\d+)"), pk = Pattern.compile("serial \"AGB-([A-Z0-9]{4})");
                    for (String blk : dat.split("\ngame \\(")) {
                        Matcher a = pn.matcher(blk), b = pc.matcher(blk), s = ps.matcher(blk), k = pk.matcher(blk);
                        if (!a.find() || !b.find() || !s.find()) continue;
                        String n = a.group(1).replaceAll("\\.(gba|agb)$", "");
                        sb.append(b.group(1).toUpperCase(Locale.ROOT)).append('\t').append(s.group(1)).append('\t')
                                .append(k.find() ? k.group(1) : "").append('\t').append(n).append('\n');
                    }
                    if (sb.length() > 0) { CoverFetcher.writeText(f, sb.toString()); datCarregado = false; }
                } catch (Throwable t) { }
            }
            if (datCarregado || !f.exists()) return;
            datCarregado = true;
            Map<String, String[]> c1 = new HashMap<>();
            Map<String, List<String[]>> c2 = new HashMap<>();
            for (String s : CoverFetcher.readLines(f)) {
                String[] p = s.split("\t", 4);
                if (p.length < 4) continue;
                c1.put(p[0], new String[]{p[3], p[2], p[1]});
                if (!p[2].isEmpty()) {
                    List<String[]> l = c2.get(p[2]);
                    if (l == null) { l = new ArrayList<>(); c2.put(p[2], l); }
                    l.add(new String[]{p[3], p[1]});
                }
            }
            byCrc = c1;
            byCode = c2;
        }
    }

    /** Nome oficial do jogo se esta ROM é o jogo original (CRC igual, ou mesmo código e mesmo nome); senão null (mod/hack). */
    static String oficial(Info i, String fileName) {
        String[] h = byCrc.get(i.crc);
        if (h != null) return h[0];
        if (!i.code.isEmpty()) {
            List<String[]> l = byCode.get(i.code);
            if (l != null) {
                String k = CoverFetcher.key(CoverFetcher.clean(fileName));
                for (String[] x : l) if (CoverFetcher.key(x[0]).equals(k)) return x[0];
            }
        }
        return null;
    }

    /** Para mods/hacks: nome do jogo base com o mesmo código e o mesmo tamanho (ou null). */
    static String baseMesmoTamanho(Info i) {
        if (i.code.isEmpty()) return null;
        List<String[]> l = byCode.get(i.code);
        if (l == null) return null;
        List<String> c = new ArrayList<>();
        for (String[] x : l) if (String.valueOf(i.size).equals(x[1])) c.add(x[0]);
        return c.isEmpty() ? null : CoverFetcher.best(c);
    }
}
