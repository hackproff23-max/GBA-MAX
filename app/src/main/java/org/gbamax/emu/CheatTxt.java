package org.gbamax.emu;

import android.content.Intent;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Importa cheats de um .txt no formato:  {Nome do código}  seguido das linhas de código. */
final class CheatTxt {
    static List<CheatMenu.Cheat> interpretar(String t) {
        List<CheatMenu.Cheat> r = new ArrayList<>();
        if (t == null) return r;
        t = t.replace("\uFEFF", "").replace("\r", "");
        // sem chaves: usa os formatos já aceitos (Nome=CODIGO, blocos, RetroArch)
        if (t.indexOf('{') < 0) return CheatMenu.interpretar(t);
        CheatMenu.Cheat cur = null;
        for (String linha : t.split("\n")) {
            String s = linha.trim();
            if (s.isEmpty()) continue;
            if (s.charAt(0) == '{') {
                if (cur != null && !cur.codigo.isEmpty()) r.add(cur);
                cur = new CheatMenu.Cheat();
                int f = s.indexOf('}');
                cur.nome = CheatMenu.limpaNome(s.substring(1, f > 0 ? f : s.length()));
                cur.on = false;
                if (f > 0 && f + 1 < s.length()) {
                    String resto = CheatMenu.normalizar(s.substring(f + 1).trim());
                    if (resto != null) cur.codigo = resto;
                }
                continue;
            }
            String n = CheatMenu.normalizar(s);
            if (n == null || cur == null) continue;
            cur.codigo = cur.codigo.isEmpty() ? n : cur.codigo + "+" + n;
        }
        if (cur != null && !cur.codigo.isEmpty()) r.add(cur);
        int k = 0;
        for (CheatMenu.Cheat ch : r) {
            k++;
            if (ch.nome.isEmpty()) ch.nome = "Cheat " + k;
        }
        return r;
    }

    static void importar(CheatMenu cm, String texto) {
        List<CheatMenu.Cheat> novos = interpretar(texto);
        int add = 0;
        for (CheatMenu.Cheat ch : novos) {
            boolean dup = false;
            for (CheatMenu.Cheat x : cm.lista) if (x.codigo.equals(ch.codigo)) dup = true;
            if (!dup) { cm.lista.add(ch); add++; }
        }
        if (add == 0) {
            cm.g.toast(novos.isEmpty() ? "Não achei cheats nesse arquivo" : "Esses cheats já estão na lista");
            return;
        }
        cm.salvar();
        cm.preencher();
        cm.g.toast(add + " cheat(s) importado(s) (desligados)");
    }

    /** Chamado pelo MainActivity quando o seletor de arquivos devolve o .txt. */
    static void resultado(MainActivity a, Intent d) {
        CheatMenu cm = a.cheatPend;
        a.cheatPend = null;
        Uri u = d == null ? null : d.getData();
        if (cm == null || u == null) return;
        try (InputStream in = a.getContentResolver().openInputStream(u)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int k;
            while ((k = in.read(b)) != -1 && o.size() < 4_000_000) o.write(b, 0, k);
            importar(cm, o.toString("UTF-8"));
        } catch (Throwable e) {
            a.toast("Não consegui ler o arquivo");
        }
    }
}
