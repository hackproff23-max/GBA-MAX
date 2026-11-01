package org.gbamax.emu;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Janelas da "Configuração de tela" (mesmo estilo de vidro das Configurações). */
class VideoMenu {
    interface Pick { void on(int i); }

    final MenuView mv;
    final Context c;

    VideoMenu(MenuView m) { mv = m; c = m.getContext(); }

    int dp(float v) { return mv.dp(v); }

    int screenH() { return c.getResources().getDisplayMetrics().heightPixels; }

    void toast(String s) { Toast.makeText(c, s, Toast.LENGTH_SHORT).show(); }

    void changed() {
        VideoSettings.save(c);
        mv.host.videoChanged();
    }

    // ================= janela principal =================
    void show() {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView title = mv.label("Configuração de tela", 18, 0xFF1B1F27, true);
        title.setPadding(dp(4), 0, 0, dp(10));
        box.addView(title);

        String[] ori = {"Automática", "Retrato", "Paisagem"};
        String sh = VideoSettings.shader;
        String shName = sh == null || sh.isEmpty() ? "Nenhum" : sh.substring(sh.lastIndexOf('/') + 1);
        String[] tx = {"Orientação de tela", "Editar layout de tela e controle", "Pulo de quadros",
                "Selecionar shaders", "Importar shaders", "Baixar shaders", "Controle de cores"};
        String[] vl = {ori[VideoSettings.orient], "", VideoSettings.frameskip == 0 ? "Desligado" : String.valueOf(VideoSettings.frameskip),
                shName, "", "", ""};
        int[] ty = {MenuView.Icons.SCREEN, MenuView.Icons.PAD, MenuView.Icons.ADV, MenuView.Icons.LIB,
                MenuView.Icons.SAVE, MenuView.Icons.WIFI, MenuView.Icons.FACE};
        int[] bg = {0xFF1E6BE6, 0xFF7E57C2, 0xFF3A3C42, 0xFF2EAD4B, 0xFFFFA000, 0xFF1E6BE6, 0xFFE91E63};

        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] dd = new Dialog[1];
        for (int i = 0; i < tx.length; i++) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(3), dp(12), dp(3));
            GradientDrawable rg = new GradientDrawable();
            rg.setColor(0x99FFFFFF);
            rg.setCornerRadius(dp(16));
            rg.setStroke(dp(1), 0xCCFFFFFF);
            row.setBackground(rg);
            row.addView(mv.new DockBtn(c, ty[i], bg[i], Color.WHITE, false), new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView t = mv.label(tx[i], 14, 0xFF1B1F27, true);
            col.addView(t);
            if (!vl[i].isEmpty()) {
                TextView v = mv.label(vl[i], 11.5f, 0xFF6B7587, false);
                v.setSingleLine(true);
                v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                col.addView(v);
            }
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
            cl.leftMargin = dp(6);
            row.addView(col, cl);
            TextView ar = mv.label("›", 20, 0xFF8A93A3, false);
            row.addView(ar);
            final int k = i;
            row.setOnClickListener(v -> { dd[0].dismiss(); action(k); });
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.bottomMargin = dp(6);
            list.addView(row, rl);
        }
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(-1, Math.min(dp(7 * 56), (int) (screenH() * .68f))));
        dd[0] = mv.glassDialog(box);
        dd[0].show();
    }

    void action(int k) {
        switch (k) {
            case 0:
                choice("Orientação de tela", new String[]{"Automática", "Retrato", "Paisagem"}, VideoSettings.orient, i -> {
                    VideoSettings.orient = i;
                    changed();
                    show();
                });
                break;
            case 1: mv.host.editLayout(); break;
            case 2:
                choice("Pulo de quadros", new String[]{"Desligado", "1 quadro", "2 quadros", "3 quadros", "4 quadros"},
                        VideoSettings.frameskip, i -> {
                            VideoSettings.frameskip = i;
                            changed();
                            show();
                        });
                break;
            case 3: pickShader(); break;
            case 4: mv.host.importShaders(); break;
            case 5: download(); break;
            case 6: colors(); break;
        }
    }

    // ================= escolha simples =================
    void choice(String title, String[] items, int checked, final Pick pick) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = mv.label(title, 18, 0xFF1B1F27, true);
        t.setPadding(dp(4), 0, 0, dp(10));
        box.addView(t);
        final Dialog[] dd = new Dialog[1];
        for (int i = 0; i < items.length; i++) {
            TextView r = mv.btn((i == checked ? "✓  " : "") + items[i], i == checked ? 0xFF1E6BE6 : 0x99FFFFFF,
                    i == checked ? Color.WHITE : 0xFF1B1F27);
            r.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            final int k = i;
            r.setOnClickListener(v -> { dd[0].dismiss(); pick.on(k); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(6);
            box.addView(r, lp);
        }
        TextView back = mv.btn("Voltar", 0xFFE3E7EE, 0xFF1B1F27);
        back.setOnClickListener(v -> { dd[0].dismiss(); show(); });
        box.addView(back);
        dd[0] = mv.glassDialog(box);
        dd[0].show();
    }

    // ================= selecionar shader =================
    void pickShader() {
        final List<String> files = ShaderStore.listLocal(c);
        final List<String> all = new ArrayList<>();
        all.add("");           // nenhum
        all.addAll(files);
        listDialog("Selecionar shaders", all, VideoSettings.shader, true, new Pick() {
            @Override public void on(int i) {
                VideoSettings.shader = all.get(i);
                changed();
                toast(all.get(i).isEmpty() ? "Shader desativado" : "Shader aplicado");
                show();
            }
        }, files.isEmpty() ? "Nenhum shader instalado.\nUse Importar ou Baixar shaders." : null);
    }

    /** Lista rolável com busca. Item "" = "Nenhum". */
    void listDialog(String title, final List<String> items, final String current, boolean noneFirst,
                    final Pick pick, String emptyMsg) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = mv.label(title, 18, 0xFF1B1F27, true);
        t.setPadding(dp(4), 0, 0, dp(8));
        box.addView(t);
        if (emptyMsg != null) {
            TextView m = mv.label(emptyMsg, 14, 0xFF4A5363, false);
            m.setPadding(dp(4), 0, 0, dp(12));
            box.addView(m);
        }
        final List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) shown.add(i);
        final EditText q = new EditText(c);
        q.setHint("Buscar...");
        q.setSingleLine(true);
        q.setTextSize(14);
        q.setTextColor(0xFF1B1F27);
        if (items.size() > 8) box.addView(q, new LinearLayout.LayoutParams(-1, -2));
        final BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) {
                TextView r = v instanceof TextView ? (TextView) v : new Txt(c);
                int idx = shown.get(i);
                String s = items.get(idx);
                boolean on = s.equals(current == null ? "" : current);
                r.setText((on ? "✓  " : "") + (s.isEmpty() ? "Nenhum (desativar)" : s));
                r.setTextSize(13.5f);
                r.setTextColor(on ? 0xFF1E6BE6 : 0xFF1B1F27);
                r.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                r.setPadding(dp(8), dp(11), dp(8), dp(11));
                return r;
            }
        };
        ListView lv = new ListView(c);
        lv.setAdapter(ad);
        lv.setDividerHeight(1);
        lv.setVerticalScrollBarEnabled(true);
        final Dialog[] dd = new Dialog[1];
        lv.setOnItemClickListener((p, v, i, id) -> { dd[0].dismiss(); pick.on(shown.get(i)); });
        q.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable e) {
                String f = e.toString().toLowerCase().trim();
                shown.clear();
                for (int i = 0; i < items.size(); i++)
                    if (f.isEmpty() || items.get(i).toLowerCase().contains(f)) shown.add(i);
                ad.notifyDataSetChanged();
            }
        });
        box.addView(lv, new LinearLayout.LayoutParams(-1, (int) (screenH() * .5f)));
        TextView back = mv.btn("Voltar", 0xFFE3E7EE, 0xFF1B1F27);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = dp(8);
        box.addView(back, bl);
        dd[0] = mv.glassDialog(box);
        back.setOnClickListener(v -> { dd[0].dismiss(); show(); });
        dd[0].show();
    }

    // ================= baixar shaders (libretro) =================
    void download() {
        choice("Baixar shaders (libretro)", new String[]{"GLSL — compatível (recomendado)", "Slang — experimental"}, -1, i -> loadIndex(i));
    }

    void loadIndex(final int repo) {
        toast("Buscando lista...");
        new Thread(() -> {
            final String[] br = new String[1];
            List<String> r = null;
            String err = null;
            try { r = ShaderStore.repoIndex(ShaderStore.REPOS[repo], br); }
            catch (Exception e) { err = e.getMessage(); }
            final List<String> list = r;
            final String er = err;
            mv.post(() -> {
                if (list == null || list.isEmpty()) {
                    toast("Falha ao listar: " + (er == null ? "vazio" : er));
                    show();
                    return;
                }
                listDialog("Shaders libretro (" + list.size() + ")", list, null, false, new Pick() {
                    @Override public void on(int i) { fetch(repo, br[0], list.get(i)); }
                }, null);
            });
        }).start();
    }

    void fetch(final int repo, final String br, final String path) {
        toast("Baixando " + path.substring(path.lastIndexOf('/') + 1) + "...");
        new Thread(() -> {
            String rel = null, err = null;
            try { rel = ShaderStore.download(c, repo, br, path); }
            catch (Exception e) { err = e.getMessage(); }
            final String fr = rel, fe = err;
            mv.post(() -> {
                if (fr == null) { toast("Falha no download: " + fe); show(); return; }
                VideoSettings.shader = fr;
                changed();
                toast("Shader baixado e aplicado");
                show();
            });
        }).start();
    }

    // ================= controle de cores =================
    interface Setter { void set(int v); }

    void slider(LinearLayout box, String name, final int min, final int max, int cur, final String kind, final Setter st) {
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView n = mv.label(name, 14, 0xFF1B1F27, true);
        final TextView val = mv.label("", 13, 0xFF1E6BE6, true);
        head.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(val);
        box.addView(head);
        SeekBar sb = new SeekBar(c);
        sb.setMax(max - min);
        sb.setProgress(cur - min);
        val.setText(fmt(kind, cur));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                st.set(p + min);
                val.setText(fmt(kind, p + min));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        box.addView(sb, lp);
    }

    static String fmt(String kind, int v) {
        if (kind.equals("sign")) return (v > 0 ? "+" : "") + v;
        if (kind.equals("gamma")) return String.format(java.util.Locale.US, "%.2f", v / 100f);
        return v + "%";
    }

    void colors() {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = mv.label("Controle de cores", 18, 0xFF1B1F27, true);
        t.setPadding(dp(4), 0, 0, dp(8));
        box.addView(t);
        LinearLayout in = new LinearLayout(c);
        in.setOrientation(LinearLayout.VERTICAL);
        slider(in, "Brilho", -100, 100, VideoSettings.bright, "sign", v -> VideoSettings.bright = v);
        slider(in, "Contraste", 0, 200, VideoSettings.contrast, "pct", v -> VideoSettings.contrast = v);
        slider(in, "Saturação", 0, 200, VideoSettings.sat, "pct", v -> VideoSettings.sat = v);
        slider(in, "Gamma", 50, 200, VideoSettings.gamma, "gamma", v -> VideoSettings.gamma = v);
        slider(in, "LCD opacidade", 0, 100, VideoSettings.lcd, "pct", v -> VideoSettings.lcd = v);
        ScrollView sv = new ScrollView(c);
        sv.addView(in);
        box.addView(sv, new LinearLayout.LayoutParams(-1, Math.min(dp(330), (int) (screenH() * .6f))));
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView rs = mv.btn("Restaurar", 0xFFE3E7EE, 0xFF1B1F27);
        TextView ok = mv.btn("Concluir", 0xFF2EAD4B, Color.WHITE);
        row.addView(rs, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(0, -2, 1f);
        ol.leftMargin = dp(8);
        row.addView(ok, ol);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = dp(8);
        box.addView(row, rl);
        final Dialog d = mv.glassDialog(box);
        rs.setOnClickListener(v -> { VideoSettings.resetColors(); changed(); d.dismiss(); colors(); });
        ok.setOnClickListener(v -> d.dismiss());
        d.setOnDismissListener(x -> changed());
        d.show();
    }
}
