package org.gbamax.emu;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Estrutura de pastas do app no armazenamento: /GBA-MAX/{Configurações,Shaders,Data,Saves,Cheats}. */
final class Pastas {
    static final String RAIZ = "GBA-MAX";
    static final String CONFIG = "Configurações"; // um .json por jogo, com o nome do jogo
    static final String SHADERS = "Shaders";       // baixados ou importados
    static final String DATA = "Data";             // dados salvos do jogo (.sav)
    static final String SAVES = "Saves";           // savestates (.st1 a .st5)
    static final String CHEATS = "Cheats";         // um .cht por jogo, com o nome do jogo
    static final String PERSONALIZAR = "Personalizar"; // imagens dos botões, fundo do app e abertura
    static final String[] TODAS = {CONFIG, SHADERS, DATA, SAVES, CHEATS, PERSONALIZAR};

    static boolean temAcesso(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23)
            return c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        return true;
    }

    static File raiz() { return new File(Environment.getExternalStorageDirectory(), RAIZ); }

    /** Cria a pasta GBA-MAX e as 5 subpastas. Devolve false se não tiver permissão ou falhar. */
    static boolean criar(Context c) {
        if (!temAcesso(c)) return false;
        File r = raiz();
        if (!r.isDirectory() && !r.mkdirs()) return false;
        for (String n : TODAS) {
            File d = new File(r, n);
            if (!d.isDirectory()) d.mkdirs();
        }
        return true;
    }

    /** Subpasta pronta para uso, ou null se não houver acesso ao armazenamento. */
    static File dir(Context c, String nome) {
        if (!temAcesso(c)) return null;
        File d = new File(raiz(), nome);
        return (d.isDirectory() || d.mkdirs()) ? d : null;
    }

    /** Subpastas do app: o scanner de jogos não entra nelas. */
    static boolean reservada(String n) {
        for (String s : TODAS) if (s.equalsIgnoreCase(n)) return true;
        return false;
    }

    /** Nome do jogo próprio para arquivo. */
    static String seguro(String n) {
        if (n == null) n = "";
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        while (n.endsWith(".")) n = n.substring(0, n.length() - 1).trim();
        return n.isEmpty() ? "jogo" : n;
    }

    /** Apaga os estados automáticos e/ou o "retomar" (com as miniaturas) de todos os jogos. Os manuais ficam. */
    static int apagarSaves(Context c, boolean soRetomar) {
        File d = dir(c, SAVES);
        File[] l = d == null ? null : d.listFiles();
        if (l == null) return 0;
        int n = 0;
        for (File f : l) {
            String s = f.getName();
            boolean r = s.endsWith(".resume") || s.endsWith("-retomar");
            boolean a = s.matches(".*(\\.auto[1-3]|-auto[1-3])$");
            if ((r || (!soRetomar && a)) && f.delete()) n++;
        }
        return n;
    }

    static boolean copiar(File de, File para) {
        try (InputStream in = new FileInputStream(de); OutputStream o = new FileOutputStream(para)) {
            byte[] t = new byte[16384];
            int k;
            while ((k = in.read(t)) != -1) o.write(t, 0, k);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
