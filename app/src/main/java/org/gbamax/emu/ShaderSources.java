package org.gbamax.emu;

import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.GLES30;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Suporte a vários formatos de shader: .vsh/.fsh, .vert/.frag, .glsl, .shader, .xml (bsnes/libretro), ShaderToy, GLSL moderno. */
final class ShaderSources {
    static final String[][] PARES = {{"vsh", "fsh"}, {"vert", "frag"}, {"vs", "fs"}, {"vp", "fp"}, {"vertex", "fragment"}};
    static final String[] SOLTOS = {"glsl", "gsls", "slang", "shader", "glslf", "essl", "xml"};
    static final long T0 = System.nanoTime();

    static final Pattern VER = Pattern.compile("(?m)^\\s*#version\\s+(\\d+)(?:\\s+(\\w+))?");
    static final Pattern INOUT = Pattern.compile("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?(?:flat\\s+|smooth\\s+)?(?:in|out)\\s+(?:(?:lowp|mediump|highp)\\s+)?(?:float|int|uint|[iu]?vec[234]|mat[234])\\b");
    static final Pattern VARY = Pattern.compile("(?m)^\\s*(?:varying|in)\\s+(?:(?:lowp|mediump|highp)\\s+)?vec([234])\\s+(\\w+)\\s*;");
    static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(");
    static final Pattern SEC = Pattern.compile("^\\s*(?:\\[\\s*(vertex|fragment)\\s*\\]|#pragma\\s+(?:stage\\s+)?(vertex|fragment)|#shader\\s+(vertex|fragment)|-{2,}\\s*(vertex|fragment)\\s*-*)\\s*$", Pattern.CASE_INSENSITIVE);
    static final String TOYU = "(?m)^\\s*uniform\\s+[^;]*\\b(?:iResolution|iTime|iTimeDelta|iFrame|iFrameRate|iMouse|iDate|iChannel[0-3]|iChannelResolution)\\b[^;]*;";

    // XML: <shader><vertex><![CDATA[...]]></vertex><fragment>...</fragment></shader>
    static final Pattern XTAG = Pattern.compile("(?is)<(vertex|fragment|vs|fs)\\b([^>]*?)(?:/>|>(.*?)</\\1\\s*>)");
    static final Pattern XCDATA = Pattern.compile("(?s)<!\\[CDATA\\[(.*?)\\]\\]>");
    static final Pattern XFILE = Pattern.compile("(?i)\\b(?:file|src|href)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    // ---------- extensões ----------
    static String ext(String n) {
        int d = n.lastIndexOf('.');
        return d < 0 ? "" : n.substring(d + 1).toLowerCase();
    }

    static boolean isVertexExt(String e) {
        for (String[] p : PARES) if (p[0].equals(e)) return true;
        return false;
    }

    static boolean isFragExt(String e) {
        for (String[] p : PARES) if (p[1].equals(e)) return true;
        return false;
    }

    static boolean isShaderFile(String n) {
        String e = ext(n);
        if (isVertexExt(e) || isFragExt(e)) return true;
        for (String s : SOLTOS) if (s.equals(e)) return true;
        return false;
    }

    /** Arquivo par (vsh<->fsh, vert<->frag...) na mesma pasta com o mesmo nome. */
    static File partner(File f) {
        String n = f.getName();
        int d = n.lastIndexOf('.');
        if (d <= 0) return null;
        String base = n.substring(0, d), e = n.substring(d + 1).toLowerCase(), want = null;
        for (String[] p : PARES) {
            if (p[0].equals(e)) want = p[1];
            else if (p[1].equals(e)) want = p[0];
        }
        if (want == null) return null;
        File dir = f.getParentFile();
        File[] a = dir == null ? null : dir.listFiles();
        if (a == null) return null;
        for (File x : a) {
            if (!x.isFile()) continue;
            String xn = x.getName();
            int xd = xn.lastIndexOf('.');
            if (xd <= 0) continue;
            if (xn.substring(0, xd).equalsIgnoreCase(base) && xn.substring(xd + 1).equalsIgnoreCase(want)) return x;
        }
        return null;
    }

    /** O arquivo de vértice que tem par fica escondido da lista (a entrada é o de fragmento). */
    static boolean hiddenPair(File f) {
        return isVertexExt(ext(f.getName())) && partner(f) != null;
    }

    // ---------- XML ----------
    /** Texto de um elemento XML: junta os blocos CDATA; sem CDATA, desfaz as entidades (&lt; &gt; &amp;...). */
    static String xmlText(String raw) {
        Matcher c = XCDATA.matcher(raw);
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        while (c.find()) { sb.append(c.group(1)); any = true; }
        if (any) return sb.toString();
        return raw.trim().replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    /** {vértice ou null, fragmento} do primeiro passe do XML. Aceita código inline ou file="x.fsh". */
    static String[] xmlPair(File sf, String s) throws IOException {
        String v = null, f = null;
        Matcher m = XTAG.matcher(s);
        while (f == null && m.find()) {
            String tag = m.group(1).toLowerCase();
            boolean vert = tag.equals("vertex") || tag.equals("vs");
            String code = m.group(3) == null ? "" : xmlText(m.group(3));
            if (code.trim().isEmpty()) {
                Matcher a = XFILE.matcher(m.group(2));
                if (a.find()) {
                    String nm = a.group(1) != null ? a.group(1) : a.group(2);
                    File x = new File(sf.getParentFile(), nm);
                    if (!nm.contains("..") && x.isFile()) code = GlScreen.read(x);
                }
            }
            if (code.trim().isEmpty()) continue;
            if (vert) { if (v == null) v = code; } else f = code;
        }
        if (f == null) throw new IOException(sf.getName() + ": XML sem <fragment>");
        return new String[]{v, f};
    }

    /** XML por extensão (.xml) ou pelo conteúdo (ex.: .shader que na verdade é XML). */
    static boolean isXml(File f, String s) {
        if (ext(f.getName()).equals("xml")) return true;
        String t = s.trim();
        return t.startsWith("<") && XTAG.matcher(t).find();
    }

    static String attr(String a, String name) {
        Matcher m = Pattern.compile("(?i)\\b" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").matcher(a == null ? "" : a);
        if (!m.find()) return null;
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    /** "300%" / "3x" = relativo à entrada; "640" / "640px" = absoluto. */
    static void xsize(String v, boolean horiz, GlScreen.Pass p) {
        if (v == null) return;
        v = v.trim().toLowerCase();
        try {
            int type;
            float val;
            if (v.endsWith("%")) { type = 0; val = Float.parseFloat(v.substring(0, v.length() - 1)) / 100f; }
            else if (v.endsWith("x")) { type = 0; val = Float.parseFloat(v.substring(0, v.length() - 1)); }
            else { type = 2; val = Float.parseFloat(v.replace("px", "")); }
            if (horiz) { p.stx = type; p.sx = val; } else { p.sty = type; p.sy = val; }
        } catch (Exception e) { }
    }

    /** Aplica filter="nearest|linear", output_width/output_height e scale do <fragment> ao passe. */
    static void xmlAttrs(File sf, String s, GlScreen.Pass p) {
        if (!isXml(sf, s)) return;
        Matcher m = XTAG.matcher(s);
        while (m.find()) {
            String t = m.group(1).toLowerCase();
            if (t.equals("vertex") || t.equals("vs")) continue;
            String a = m.group(2);
            String fl = attr(a, "filter");
            if (fl != null) p.linear = !fl.equalsIgnoreCase("nearest");
            String sc = attr(a, "scale");
            if (sc != null) { xsize(sc, true, p); xsize(sc, false, p); }
            xsize(attr(a, "output_width"), true, p);
            xsize(attr(a, "output_height"), false, p);
            String wr = attr(a, "wrap");
            if (wr != null) p.wrap = GlScreen.wrapOf(wr);
            return;
        }
    }

    /** Instala o 2xbr.shader (assets) em Shaders/padrao e, uma única vez, o escolhe como shader padrão. */
    static void padrao(Context c) { Padrao3x.instalar(c);
        try {
            String rel = "padrao/2xbr.shader";
            File out = new File(ShaderStore.root(c), rel);
            if (!out.isFile()) {
                try (InputStream in = c.getAssets().open("2xbr.shader")) { ShaderStore.copy(in, out); }
            }
            for (String nm : new String[]{"xbrz-lite.shader", "xbrz-lite-2x.shader"}) { File o2 = new File(ShaderStore.root(c), "padrao/" + nm); if (!o2.isFile()) { try (InputStream in2 = c.getAssets().open(nm)) { ShaderStore.copy(in2, o2); } catch (Exception e2) { } } }
            SharedPreferences p = c.getSharedPreferences("gbamax_video", 0);
            if (!p.getBoolean("padrao_lite", false)) { String lt = "padrao/xbrz-lite.shader"; if (VideoSettings.shader == null || VideoSettings.shader.isEmpty() || VideoSettings.shader.equals(rel)) { VideoSettings.shader = lt; p.edit().putString("shader", lt).apply(); } p.edit().putBoolean("padrao_lite", true).apply(); }
            if (p.getBoolean("padrao_2xbr", false)) return;
            if (VideoSettings.shader == null || VideoSettings.shader.isEmpty()) VideoSettings.shader = rel;
            p.edit().putString("shader", VideoSettings.shader).putBoolean("padrao_2xbr", true).apply();
        } catch (Exception e) { }
    }

    // ---------- montagem do código ----------
    static boolean isModern(String s) {
        Matcher m = VER.matcher(s);
        if (!m.find()) return false;
        int v;
        try { v = Integer.parseInt(m.group(1)); } catch (Exception e) { return false; }
        if (v >= 300 && "es".equalsIgnoreCase(m.group(2))) return true;
        return v >= 130 && INOUT.matcher(s).find();
    }

    /** {extensões (para o topo), corpo sem #version/#pragma parameter}. */
    static String[] prep(String s) {
        StringBuilder ex = new StringBuilder(), b = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#version") || t.startsWith("#pragma parameter")
                    || t.startsWith("#pragma name") || t.startsWith("#pragma format")) b.append('\n');
            else if (t.startsWith("#extension")) {
                if (t.contains("GL_OES_") || t.contains("GL_EXT_")) ex.append(line.replace("require", "enable")).append('\n');
                b.append('\n');
            } else b.append(line).append('\n');
        }
        return new String[]{ex.toString(), b.toString()};
    }

    /** Compatibilidade com GLSL de desktop antigo (gl_Vertex, gl_ModelViewProjectionMatrix, gl_TexCoord, ftransform). */
    static String compat(String b, boolean vert) {
        StringBuilder h = new StringBuilder();
        boolean ft = vert && b.contains("ftransform");
        boolean mvp = b.contains("gl_ModelViewProjectionMatrix");
        boolean gv = vert && b.contains("gl_Vertex");
        if (mvp || ft) h.append("uniform mat4 MVPMatrix;\n");
        if (mvp) h.append("#define gl_ModelViewProjectionMatrix MVPMatrix\n");
        if (gv || ft) h.append("attribute vec4 VertexCoord;\n");
        if (gv) h.append("#define gl_Vertex VertexCoord\n");
        if (ft) h.append("#define ftransform() (MVPMatrix * VertexCoord)\n");
        if (vert && b.contains("gl_MultiTexCoord0")) h.append("attribute vec4 TexCoord;\n#define gl_MultiTexCoord0 TexCoord\n");
        if (b.contains("gl_TexCoord")) h.append("varying vec4 _gtc[2];\n#define gl_TexCoord _gtc\n");
        return h.toString();
    }

    static String[] assemble(String v, String f) {
        String[] pv = prep(v), pf = prep(f);
        String defs = "#define PARAMETER_UNIFORM\n";
        if (!(isModern(v) || isModern(f))) {
            return new String[]{
                    "#version 100\n" + pv[0] + "#define VERTEX\n" + defs + "precision highp float;\n" + compat(pv[1], true) + pv[1],
                    "#version 100\n" + pf[0] + "#define FRAGMENT\n" + defs + "precision highp float;\n" + compat(pf[1], false) + pf[1]};
        }
        String head = "precision highp float;\nprecision highp int;\nprecision highp sampler2D;\n"
                + "#define texture2D texture\n#define texture2DLod textureLod\n";
        String vh = "#version 300 es\n" + pv[0] + "#define VERTEX\n" + defs + head
                + (pv[1].contains("attribute") ? "#define attribute in\n" : "")
                + (pv[1].contains("varying") ? "#define varying out\n" : "");
        String fh = "#version 300 es\n" + pf[0] + "#define FRAGMENT\n" + defs + head
                + (pf[1].contains("varying") ? "#define varying in\n" : "");
        if (pf[1].contains("gl_FragColor")) fh += "out vec4 _fragOut;\n#define gl_FragColor _fragOut\n";
        return new String[]{vh + pv[1], fh + pf[1]};
    }

    /** Vértice padrão: repete como varying tudo que o fragmento espera receber. */
    static String defaultVertex(String f, boolean modern) {
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder decl = new StringBuilder(), asg = new StringBuilder();
        Matcher m = VARY.matcher(f);
        while (m.find()) {
            String n = m.group(2), t = "vec" + m.group(1);
            if (!seen.add(n)) continue;
            decl.append(modern ? "out " : "varying ").append(t).append(' ').append(n).append(";\n");
            asg.append("  ").append(n).append(" = ")
                    .append(t.equals("vec2") ? "uv" : t.equals("vec3") ? "vec3(uv, 0.0)" : "vec4(uv, 0.0, 1.0)").append(";\n");
        }
        String head = modern ? "#version 300 es\nprecision highp float;\nin vec4 a_pos;\n"
                : "#version 100\nprecision highp float;\nattribute vec4 a_pos;\n";
        return head + decl + "void main() {\n  vec2 uv = a_pos.xy;\n" + asg
                + "  gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);\n}\n";
    }

    static boolean isToy(String s) {
        return s.contains("mainImage") && !MAIN.matcher(s).find();
    }

    static String[] toy(String s) {
        String[] p = prep(s);
        String body = p[1].replaceAll(TOYU, "");
        String fs = "#version 300 es\n" + p[0]
                + "precision highp float;\nprecision highp int;\nprecision highp sampler2D;\n"
                + "#define texture2D texture\n#define texture2DLod textureLod\n"
                + "out vec4 _fragOut;\n"
                + "uniform vec3 iResolution;\nuniform float iTime;\nuniform float iTimeDelta;\nuniform int iFrame;\n"
                + "uniform float iFrameRate;\nuniform vec4 iMouse;\nuniform vec4 iDate;\n"
                + "uniform sampler2D iChannel0;\nuniform sampler2D iChannel1;\nuniform sampler2D iChannel2;\nuniform sampler2D iChannel3;\n"
                + "uniform vec3 iChannelResolution[4];\n"
                + body
                + "\nvoid main() {\n  vec4 c = vec4(0.0, 0.0, 0.0, 1.0);\n  mainImage(c, gl_FragCoord.xy);\n  _fragOut = vec4(c.rgb, 1.0);\n}\n";
        return new String[]{defaultVertex("", true), fs};
    }

    /** Divide um arquivo com seções [vertex]/[fragment], #shader vertex, #pragma vertex... ou devolve null. */
    static String[] sections(String s) {
        StringBuilder pre = new StringBuilder(), vb = new StringBuilder(), fb = new StringBuilder();
        int mode = 0;
        boolean hv = false, hf = false;
        for (String line : s.split("\n", -1)) {
            Matcher m = SEC.matcher(line);
            if (m.find()) {
                String k = "";
                for (int i = 1; i <= 4; i++) if (m.group(i) != null) k = m.group(i).toLowerCase();
                if (k.equals("vertex")) { mode = 1; hv = true; } else { mode = 2; hf = true; }
                continue;
            }
            (mode == 0 ? pre : mode == 1 ? vb : fb).append(line).append('\n');
        }
        if (!hv || !hf) return null;
        return new String[]{pre.toString() + vb, pre.toString() + fb};
    }

    /** Vértice + fragmento já separados -> código pronto (vértice vazio = usa o padrão; Shadertoy detectado). */
    static String[] pair(String v, String f) {
        if (isToy(f)) return toy(f);
        if (v == null || v.trim().isEmpty()) return new String[]{defaultVertex(f, isModern(f)), assemble(f, f)[1]};
        return assemble(v, f);
    }

    /** Devolve {vertex, fragment} prontos para compilar. s = arquivo escolhido; sPair = arquivo par (ou null). */
    static String[] glsl(File sf, String s, String sPair) throws IOException {
        String e = ext(sf.getName());
        if (s.contains("CGPROGRAM") || s.contains("HLSLPROGRAM") || s.matches("(?s)^\\s*Shader\\s+\".*"))
            throw new IOException(sf.getName() + ": shader Unity/HLSL/Cg não é suportado (use GLSL)");
        if (isXml(sf, s)) {
            String[] x = xmlPair(sf, s);
            return pair(x[0], x[1]);
        }
        if (isToy(s)) return toy(s);
        String v, f;
        if (isVertexExt(e)) {
            if (sPair == null) throw new IOException(sf.getName() + ": falta o arquivo de fragmento (.fsh/.frag) com o mesmo nome");
            v = s;
            f = sPair;
        } else if (isFragExt(e)) {
            f = s;
            v = sPair;
        } else {
            String[] sec = sections(s);
            if (sec == null) return assemble(s, s);
            v = sec[0];
            f = sec[1];
        }
        if (v == null) return new String[]{defaultVertex(f, isModern(f)), assemble(f, f)[1]};
        return assemble(v, f);
    }

    // ---------- uniforms extras (tempo, resolução, texturas com outros nomes) ----------
    static void f1(GlScreen g, GlScreen.Pass p, String n, float v) {
        int l = g.u(p, n);
        if (l >= 0) GLES30.glUniform1f(l, v);
    }

    static void f2(GlScreen g, GlScreen.Pass p, String n, int w, int h) {
        int l = g.u(p, n);
        if (l >= 0) GLES30.glUniform2f(l, w, h);
    }

    static void extra(GlScreen g, GlScreen.Pass p, GlScreen.Tex in, int ow, int oh, int[] un, int frame) {
        float t = (System.nanoTime() - T0) / 1e9f;
        for (String n : new String[]{"iTime", "iGlobalTime", "Time", "time", "u_time", "uTime", "TIME"}) f1(g, p, n, t);
        f1(g, p, "iTimeDelta", 1f / 60f);
        f1(g, p, "iFrameRate", 60f);
        int l = g.u(p, "iResolution");
        if (l >= 0) GLES30.glUniform3f(l, ow, oh, 1f);
        for (String n : new String[]{"resolution", "u_resolution", "uResolution", "Resolution", "iScreenSize"}) {
            l = g.u(p, n);
            if (l >= 0) GLES30.glUniform2f(l, ow, oh);
        }
        // nomes "ruby" dos shaders XML antigos (bsnes/higan/libretro)
        f2(g, p, "rubyTextureSize", in.w, in.h);
        f2(g, p, "rubyInputSize", in.w, in.h);
        f2(g, p, "rubyOutputSize", ow, oh);
        f2(g, p, "rubyOrigTextureSize", g.src.w, g.src.h);
        f2(g, p, "rubyOrigInputSize", g.src.w, g.src.h);
        g.tex(p, "rubyOrigTexture", g.src, p.linear, p.wrap, un);
        l = g.u(p, "iFrame");
        if (l >= 0) { GLES30.glUniform1i(l, frame); GLES30.glUniform1f(l, frame); }
        l = g.u(p, "iMouse");
        if (l >= 0) GLES30.glUniform4f(l, 0, 0, 0, 0);
        l = g.u(p, "iDate");
        if (l >= 0) GLES30.glUniform4f(l, 0, 0, 0, 0);
        l = g.u(p, "iChannelResolution");
        if (l >= 0) GLES30.glUniform3fv(l, 4, new float[]{in.w, in.h, 1, in.w, in.h, 1, in.w, in.h, 1, in.w, in.h, 1}, 0);
        for (String n : new String[]{"iChannel0", "iChannel1", "iChannel2", "iChannel3", "u_texture", "uTexture", "u_Texture",
                "texture0", "tex0", "inputTexture", "u_image", "uImage", "image", "colorMap", "inputImageTexture", "sTexture", "s_texture", "tex",
                "rubyTexture"})
            g.tex(p, n, in, p.linear, p.wrap, un);
    }
}
