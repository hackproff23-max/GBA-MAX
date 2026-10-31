package org.gbamax.emu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Tela do jogo em OpenGL ES 3.0.
 * Cadeia: [shader(s) libretro GLSL/Slang, opcional] -> passe de cor final (brilho, contraste,
 * saturação, gamma e LCD), que SEMPRE roda, com ou sem shader.
 */
public class GlScreen extends GLSurfaceView implements GLSurfaceView.Renderer {
    public interface Err { void on(String m); }

    public volatile Err onError;

    final Bitmap bmp = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888);
    volatile float rl, rt, rr, rb;
    volatile boolean reload = true;
    final File shaderRoot;

    int surfW = 1, surfH = 1, vbo;
    final Tex src = new Tex();
    int cProg, cTex, cBr, cCt, cSat, cGm, cLcd, cSrc;
    List<Pass> chain;
    Map<String, Tex> luts = new HashMap<>();
    Map<String, Float> params = new LinkedHashMap<>();
    int frameCount;
    final float[] MVP = {2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 1, 0, -1, -1, 0, 1};

    static class Tex {
        int id, w, h;
        boolean linear = true;
        int wrap = GLES30.GL_CLAMP_TO_EDGE;
    }

    static class Pass {
        int prog, fbo;
        String alias = "";
        boolean linear = true, slang, uintFrame;
        int wrap = GLES30.GL_CLAMP_TO_EDGE;
        int stx = -1, sty = -1; // 0 source, 1 viewport, 2 absolute, -1 padrão
        float sx = 1, sy = 1;
        final Tex out = new Tex();
        final Map<String, Integer> loc = new HashMap<>();
    }

    static final String CV = "attribute vec4 a_pos; varying vec2 v_uv;\n"
            + "void main(){ v_uv = a_pos.xy; gl_Position = vec4(a_pos.xy * 2.0 - 1.0, 0.0, 1.0); }\n";
    static final String CF = "precision highp float; varying vec2 v_uv; uniform sampler2D u_tex;\n"
            + "uniform float u_br, u_ct, u_sat, u_gm, u_lcd; uniform vec2 u_src;\n"
            + "void main(){\n"
            + "  vec3 c = texture2D(u_tex, v_uv).rgb;\n"
            + "  c = pow(clamp(c, 0.0, 1.0), vec3(1.0 / u_gm));\n"
            + "  c = (c - 0.5) * u_ct + 0.5 + u_br;\n"
            + "  float l = dot(c, vec3(0.299, 0.587, 0.114));\n"
            + "  c = mix(vec3(l), c, u_sat);\n"
            + "  if (u_lcd > 0.0) {\n"
            + "    vec2 f = fract(v_uv * u_src); vec2 d = min(f, 1.0 - f);\n"
            + "    float line = 1.0 - smoothstep(0.0, 0.30, min(d.x, d.y));\n"
            + "    c *= 1.0 - u_lcd * 0.85 * line;\n"
            + "  }\n"
            + "  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);\n"
            + "}\n";

    static final Pattern PARAM = Pattern.compile("#pragma\\s+parameter\\s+(\\w+)\\s+\"[^\"]*\"\\s+([-+0-9.eE]+)");
    static final Pattern UINTF = Pattern.compile("\\buint\\s+FrameCount\\b");

    public GlScreen(Context c) {
        super(c);
        shaderRoot = ShaderStore.root(c);
        setEGLContextClientVersion(3);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    public void setRect(float l, float t, float r, float b) {
        rl = l; rt = t; rr = r; rb = b;
        requestRender();
    }

    public void reloadShader() { reload = true; requestRender(); }

    void report(final String m) {
        final Err e = onError;
        if (e == null) return;
        post(() -> e.on(m.length() > 500 ? m.substring(0, 500) : m));
    }

    // ================= ciclo de vida GL =================
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        chain = null;
        luts = new HashMap<>();
        params = new LinkedHashMap<>();
        try {
            cProg = link(CV, CF);
            cTex = GLES30.glGetUniformLocation(cProg, "u_tex");
            cBr = GLES30.glGetUniformLocation(cProg, "u_br");
            cCt = GLES30.glGetUniformLocation(cProg, "u_ct");
            cSat = GLES30.glGetUniformLocation(cProg, "u_sat");
            cGm = GLES30.glGetUniformLocation(cProg, "u_gm");
            cLcd = GLES30.glGetUniformLocation(cProg, "u_lcd");
            cSrc = GLES30.glGetUniformLocation(cProg, "u_src");
        } catch (Throwable t) {
            cProg = 0;
            report("Erro ao iniciar vídeo: " + t.getMessage());
        }
        int[] a = new int[1];
        GLES30.glGenBuffers(1, a, 0);
        vbo = a[0];
        float[] v = {0, 0, 0, 1, 1, 0, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1};
        FloatBuffer fb = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(v).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, v.length * 4, fb, GLES30.GL_STATIC_DRAW);
        GLES30.glGenTextures(1, a, 0);
        src.id = a[0];
        src.w = 240;
        src.h = 160;
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src.id);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0);
        reload = true;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        surfW = Math.max(1, w);
        surfH = Math.max(1, h);
        GLES30.glViewport(0, 0, w, h);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (cProg == 0) return;
        if (reload) { reload = false; buildChain(); }
        Native.draw(bmp);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src.id);
        GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bmp);
        frameCount++;

        int vw = Math.max(1, Math.round(rr - rl)), vh = Math.max(1, Math.round(rb - rt));
        int vx = Math.round(rl), vy = surfH - Math.round(rb);

        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 16, 0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 16, 0);
        GLES30.glDisableVertexAttribArray(2);
        GLES30.glVertexAttrib4f(2, 1f, 1f, 1f, 1f);

        Tex in = src;
        List<Pass> ch = chain;
        if (ch != null) {
            Tex[] outs = new Tex[ch.size()];
            for (int i = 0; i < ch.size(); i++) {
                Pass p = ch.get(i);
                int[] sz = passSize(p, i == ch.size() - 1, in, vw, vh);
                ensureFbo(p, sz[0], sz[1]);
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, p.fbo);
                GLES30.glViewport(0, 0, sz[0], sz[1]);
                GLES30.glClearColor(0, 0, 0, 1);
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                GLES30.glUseProgram(p.prog);
                bindInputs(p, i, in, outs, sz[0], sz[1], vw, vh);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
                outs[i] = p.out;
                in = p.out;
            }
        }

        // passe final de cor (independente de qualquer shader)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, surfW, surfH);
        GLES30.glClearColor(0, 0, 0, 1);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glViewport(vx, vy, vw, vh);
        GLES30.glUseProgram(cProg);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, in.id);
        setTexParams(true, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glUniform1i(cTex, 0);
        GLES30.glUniform1f(cBr, VideoSettings.bright / 200f);
        GLES30.glUniform1f(cCt, VideoSettings.contrast / 100f);
        GLES30.glUniform1f(cSat, VideoSettings.sat / 100f);
        GLES30.glUniform1f(cGm, Math.max(0.1f, VideoSettings.gamma / 100f));
        GLES30.glUniform1f(cLcd, VideoSettings.lcd / 100f);
        GLES30.glUniform2f(cSrc, 240f, 160f);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    // ================= passes =================
    int[] passSize(Pass p, boolean last, Tex in, int vw, int vh) {
        int tx = p.stx >= 0 ? p.stx : (last ? 1 : 0);
        int ty = p.sty >= 0 ? p.sty : (last ? 1 : 0);
        float w = tx == 0 ? in.w * p.sx : tx == 1 ? vw * p.sx : p.sx;
        float h = ty == 0 ? in.h * p.sy : ty == 1 ? vh * p.sy : p.sy;
        return new int[]{Math.max(1, Math.round(w)), Math.max(1, Math.round(h))};
    }

    void ensureFbo(Pass p, int w, int h) {
        int[] a = new int[1];
        if (p.out.id == 0) {
            GLES30.glGenTextures(1, a, 0);
            p.out.id = a[0];
            GLES30.glGenFramebuffers(1, a, 0);
            p.fbo = a[0];
            p.out.w = 0;
        }
        if (p.out.w != w || p.out.h != h) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, p.out.id);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
            setTexParams(p.linear, p.wrap);
            p.out.w = w;
            p.out.h = h;
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, p.fbo);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, p.out.id, 0);
        }
    }

    static void setTexParams(boolean linear, int wrap) {
        int f = linear ? GLES30.GL_LINEAR : GLES30.GL_NEAREST;
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, f);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, f);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap);
    }

    int u(Pass p, String name) {
        Integer v = p.loc.get(name);
        if (v == null) {
            v = GLES30.glGetUniformLocation(p.prog, name);
            p.loc.put(name, v);
        }
        return v;
    }

    void size(Pass p, String name, int w, int h) {
        int l = u(p, name);
        if (l < 0) return;
        if (p.slang) GLES30.glUniform4f(l, w, h, 1f / w, 1f / h);
        else GLES30.glUniform2f(l, w, h);
    }

    void tex(Pass p, String name, Tex t, boolean linear, int wrap, int[] unit) {
        if (t == null || t.id == 0 || unit[0] >= 16) return;
        int l = u(p, name);
        if (l < 0) return;
        int un = unit[0]++;
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + un);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.id);
        setTexParams(linear, wrap);
        GLES30.glUniform1i(l, un);
    }

    void bindInputs(Pass p, int idx, Tex in, Tex[] outs, int ow, int oh, int vw, int vh) {
        int l = u(p, "MVPMatrix");
        if (l >= 0) GLES30.glUniformMatrix4fv(l, 1, false, MVP, 0);
        l = u(p, "MVP");
        if (l >= 0) GLES30.glUniformMatrix4fv(l, 1, false, MVP, 0);
        l = u(p, "FrameDirection");
        if (l >= 0) GLES30.glUniform1i(l, 1);
        l = u(p, "FrameCount");
        if (l >= 0) { if (p.uintFrame) GLES30.glUniform1ui(l, frameCount); else GLES30.glUniform1i(l, frameCount); }

        size(p, "OutputSize", ow, oh);
        size(p, "FinalViewportSize", vw, vh);
        size(p, "TextureSize", in.w, in.h);
        size(p, "InputSize", in.w, in.h);
        size(p, "SourceSize", in.w, in.h);
        size(p, "OrigTextureSize", src.w, src.h);
        size(p, "OrigInputSize", src.w, src.h);
        size(p, "OriginalSize", src.w, src.h);

        int[] un = {0};
        tex(p, "Texture", in, p.linear, p.wrap, un);
        tex(p, "Source", in, p.linear, p.wrap, un);
        tex(p, "OrigTexture", src, p.linear, p.wrap, un);
        tex(p, "Original", src, p.linear, p.wrap, un);
        tex(p, "OriginalHistory0", src, p.linear, p.wrap, un);

        for (int k = 0; k < idx; k++) {
            Tex o = outs[k];
            if (o == null) continue;
            String a = "Pass" + (k + 1), b = "PassOutput" + k;
            tex(p, a + "Texture", o, p.linear, p.wrap, un);
            tex(p, b, o, p.linear, p.wrap, un);
            size(p, a + "TextureSize", o.w, o.h);
            size(p, a + "InputSize", o.w, o.h);
            size(p, b + "Size", o.w, o.h);
            String al = chain.get(k).alias;
            if (!al.isEmpty()) {
                tex(p, al + "Texture", o, p.linear, p.wrap, un);
                tex(p, al, o, p.linear, p.wrap, un);
                size(p, al + "TextureSize", o.w, o.h);
                size(p, al + "InputSize", o.w, o.h);
                size(p, al + "Size", o.w, o.h);
            }
        }
        for (int n = 1; n <= idx + 1; n++) {
            Tex o = n <= idx ? outs[idx - n] : src;
            if (o == null) continue;
            tex(p, "PassPrev" + n + "Texture", o, p.linear, p.wrap, un);
            size(p, "PassPrev" + n + "TextureSize", o.w, o.h);
            size(p, "PassPrev" + n + "InputSize", o.w, o.h);
        }
        for (Map.Entry<String, Tex> e : luts.entrySet()) {
            Tex t = e.getValue();
            tex(p, e.getKey(), t, t.linear, t.wrap, un);
            size(p, e.getKey() + "Size", t.w, t.h);
            size(p, e.getKey() + "TextureSize", t.w, t.h);
        }
        for (Map.Entry<String, Float> e : params.entrySet()) {
            int pl = u(p, e.getKey());
            if (pl >= 0) GLES30.glUniform1f(pl, e.getValue());
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
    }

    // ================= carregar shader / preset =================
    void freeChain() {
        int[] a = new int[1];
        if (chain != null) {
            for (Pass p : chain) {
                if (p.prog != 0) GLES30.glDeleteProgram(p.prog);
                if (p.fbo != 0) { a[0] = p.fbo; GLES30.glDeleteFramebuffers(1, a, 0); }
                if (p.out.id != 0) { a[0] = p.out.id; GLES30.glDeleteTextures(1, a, 0); }
            }
        }
        for (Tex t : luts.values()) if (t.id != 0) { a[0] = t.id; GLES30.glDeleteTextures(1, a, 0); }
        chain = null;
        luts = new HashMap<>();
        params = new LinkedHashMap<>();
    }

    void buildChain() {
        freeChain();
        String rel = VideoSettings.shader;
        if (rel == null || rel.isEmpty()) return;
        try {
            File f = new File(shaderRoot, rel);
            if (!f.isFile()) throw new IOException("Shader não encontrado: " + rel);
            List<Pass> ps = new ArrayList<>();
            Map<String, Float> pr = new LinkedHashMap<>();
            Map<String, Tex> lt = new HashMap<>();
            if (ShaderStore.isPreset(f.getName())) loadPreset(f, ps, pr, lt);
            else ps.add(makePass(f, pr));
            luts = lt;
            params = pr;
            chain = ps;
        } catch (Throwable t) {
            freeChain();
            String m = t.getMessage();
            report("Shader falhou: " + (m == null ? t.toString() : m));
        }
    }

    void loadPreset(File f, List<Pass> ps, Map<String, Float> pr, Map<String, Tex> lt) throws IOException {
        Map<String, String> kv = new HashMap<>();
        Map<String, File> dirs = new HashMap<>();
        readKv(f, kv, dirs, 0);
        int n = toInt(kv.get("shaders"), 0);
        if (n <= 0) throw new IOException("Preset sem shaders");
        for (int i = 0; i < n; i++) {
            String s = kv.get("shader" + i);
            if (s == null) throw new IOException("Preset sem shader" + i);
            File sf = resolve(dirs.get("shader" + i), s);
            if (sf == null) throw new IOException("Arquivo não encontrado: " + s);
            Pass p = makePass(sf, pr);
            p.alias = nz(kv.get("alias" + i));
            p.linear = !"false".equalsIgnoreCase(kv.get("filter_linear" + i));
            p.wrap = wrapOf(kv.get("wrap_mode" + i));
            String st = kv.get("scale_type" + i), stx = kv.get("scale_type_x" + i), sty = kv.get("scale_type_y" + i);
            p.stx = typeOf(stx != null ? stx : st);
            p.sty = typeOf(sty != null ? sty : st);
            String sc = kv.get("scale" + i), scx = kv.get("scale_x" + i), scy = kv.get("scale_y" + i);
            p.sx = toF(scx != null ? scx : sc, 1f);
            p.sy = toF(scy != null ? scy : sc, 1f);
            if (p.stx < 0 && (scx != null || sc != null)) p.stx = 0;
            if (p.sty < 0 && (scy != null || sc != null)) p.sty = 0;
            ps.add(p);
        }
        for (String k : new ArrayList<>(pr.keySet())) {
            String v = kv.get(k);
            if (v != null) pr.put(k, toF(v, pr.get(k)));
        }
        String tx = kv.get("textures");
        if (tx != null) for (String nm : tx.split(";")) {
            nm = nm.trim();
            String pth = kv.get(nm);
            if (nm.isEmpty() || pth == null) continue;
            File tf = resolve(dirs.get(nm), pth);
            if (tf == null) continue;
            Tex t = loadLut(tf, !"false".equalsIgnoreCase(kv.get(nm + "_linear")), wrapOf(kv.get(nm + "_wrap_mode")));
            if (t != null) lt.put(nm, t);
        }
    }

    void readKv(File f, Map<String, String> kv, Map<String, File> dirs, int depth) throws IOException {
        String t = read(f).replace("\r", "");
        for (String line : t.split("\n")) {
            String s = line.trim();
            Matcher r = ShaderStore.REF.matcher(s);
            if (r.find()) {
                File rf = depth < 8 ? resolve(f.getParentFile(), r.group(1)) : null;
                if (rf != null) readKv(rf, kv, dirs, depth + 1);
                continue;
            }
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("//")) continue;
            Matcher m = ShaderStore.KV.matcher(s);
            if (m.find()) {
                kv.put(m.group(1), m.group(2).trim());
                dirs.put(m.group(1), f.getParentFile());
            }
        }
    }

    Tex loadLut(File f, boolean linear, int wrap) {
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b == null) return null;
        int[] a = new int[1];
        GLES30.glGenTextures(1, a, 0);
        Tex t = new Tex();
        t.id = a[0];
        t.w = b.getWidth();
        t.h = b.getHeight();
        t.linear = linear;
        t.wrap = wrap;
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.id);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
        setTexParams(linear, wrap);
        b.recycle();
        return t;
    }

    Pass makePass(File sf, Map<String, Float> pr) throws IOException {
        String s = readExpanded(sf, 0);
        Matcher m = PARAM.matcher(s);
        while (m.find()) if (!pr.containsKey(m.group(1))) pr.put(m.group(1), toF(m.group(2), 0f));
        boolean slang = sf.getName().toLowerCase().endsWith(".slang") || s.contains("#pragma stage");
        String vs, fs;
        if (slang) {
            String[] x = slangStages(s);
            vs = x[0];
            fs = x[1];
        } else {
            StringBuilder b = new StringBuilder();
            for (String line : s.split("\n", -1)) {
                String t = line.trim();
                if (t.startsWith("#version") || t.startsWith("#pragma parameter")
                        || t.startsWith("#pragma name") || t.startsWith("#pragma format")) b.append('\n');
                else b.append(line).append('\n');
            }
            vs = "#version 100\n#define VERTEX\n#define PARAMETER_UNIFORM\n" + b;
            fs = "#version 100\n#define FRAGMENT\n#define PARAMETER_UNIFORM\nprecision highp float;\n" + b;
        }
        Pass p = new Pass();
        try {
            p.prog = link(vs, fs);
        } catch (RuntimeException e) {
            throw new IOException(sf.getName() + ": " + e.getMessage());
        }
        p.slang = slang;
        p.uintFrame = UINTF.matcher(vs).find() || UINTF.matcher(fs).find();
        return p;
    }

    static String[] slangStages(String s) {
        StringBuilder common = new StringBuilder(), vert = new StringBuilder(), frag = new StringBuilder();
        int mode = 0;
        for (String line : s.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#version") || t.startsWith("#extension GL_GOOGLE")) continue;
            if (t.startsWith("#pragma stage")) { mode = t.contains("vertex") ? 1 : 2; continue; }
            if (t.startsWith("#pragma name") || t.startsWith("#pragma format") || t.startsWith("#pragma parameter")) continue;
            (mode == 0 ? common : mode == 1 ? vert : frag).append(line).append('\n');
        }
        String head = "#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n";
        return new String[]{head + slangConvert(common.toString() + vert), head + slangConvert(common.toString() + frag)};
    }

    /** Converte Vulkan-GLSL (slang) em GLSL ES 3.00: blocos UBO/push viram uniforms simples. */
    static String slangConvert(String s) {
        s = s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        Matcher m = Pattern.compile("layout\\s*\\([^)]*\\)\\s*uniform\\s+\\w+\\s*\\{([^}]*)\\}\\s*(\\w+)?\\s*;").matcher(s);
        StringBuffer sb = new StringBuffer();
        List<String> insts = new ArrayList<>();
        while (m.find()) {
            StringBuilder r = new StringBuilder();
            for (String d : m.group(1).split(";")) {
                d = d.trim();
                if (!d.isEmpty()) r.append("uniform ").append(d).append(";\n");
            }
            if (m.group(2) != null) insts.add(m.group(2));
            m.appendReplacement(sb, Matcher.quoteReplacement(r.toString()));
        }
        m.appendTail(sb);
        s = sb.toString();
        for (String i : insts) s = s.replaceAll("\\b" + i + "\\.", "");
        return s.replaceAll("layout\\s*\\([^)]*\\)\\s*", "");
    }

    // ================= arquivos =================
    static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] t = new byte[16384];
            int k;
            while ((k = in.read(t)) != -1) o.write(t, 0, k);
            return o.toString("UTF-8");
        }
    }

    String readExpanded(File f, int depth) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : read(f).replace("\r", "").split("\n", -1)) {
            Matcher m = ShaderStore.INC.matcher(line);
            if (m.find() && depth < 8) {
                File inc = resolve(f.getParentFile(), m.group(1));
                if (inc != null) { sb.append(readExpanded(inc, depth + 1)).append('\n'); continue; }
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** Resolve caminho relativo; se não existir, procura pelo nome do arquivo em todo shaders/. */
    File resolve(File dir, String ref) {
        if (ref == null) return null;
        if (dir != null) {
            File f = new File(dir, ref);
            if (f.isFile()) return f;
        }
        return findByName(shaderRoot, new File(ref).getName(), 0);
    }

    static File findByName(File d, String name, int depth) {
        File[] a = d.listFiles();
        if (a == null) return null;
        for (File f : a) if (f.isFile() && f.getName().equals(name)) return f;
        if (depth < 10) for (File f : a) if (f.isDirectory()) {
            File r = findByName(f, name, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    // ================= utilitários =================
    static String nz(String s) { return s == null ? "" : s.trim(); }

    static int toInt(String s, int d) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; }
    }

    static float toF(String s, float d) {
        try { return Float.parseFloat(s.trim()); } catch (Exception e) { return d; }
    }

    static int typeOf(String s) {
        if (s == null) return -1;
        s = s.trim().toLowerCase();
        return s.equals("source") ? 0 : s.equals("viewport") ? 1 : s.equals("absolute") ? 2 : -1;
    }

    static int wrapOf(String s) {
        if (s == null) return GLES30.GL_CLAMP_TO_EDGE;
        s = s.trim().toLowerCase();
        return s.equals("repeat") ? GLES30.GL_REPEAT : s.equals("mirrored_repeat") ? GLES30.GL_MIRRORED_REPEAT : GLES30.GL_CLAMP_TO_EDGE;
    }

    static int shader(int type, String s) {
        int sh = GLES30.glCreateShader(type);
        GLES30.glShaderSource(sh, s);
        GLES30.glCompileShader(sh);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String l = GLES30.glGetShaderInfoLog(sh);
            GLES30.glDeleteShader(sh);
            throw new RuntimeException(l);
        }
        return sh;
    }

    static int link(String vs, String fs) {
        int v = shader(GLES30.GL_VERTEX_SHADER, vs), f;
        try {
            f = shader(GLES30.GL_FRAGMENT_SHADER, fs);
        } catch (RuntimeException e) {
            GLES30.glDeleteShader(v);
            throw e;
        }
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glBindAttribLocation(p, 0, "a_pos");
        GLES30.glBindAttribLocation(p, 0, "VertexCoord");
        GLES30.glBindAttribLocation(p, 0, "Position");
        GLES30.glBindAttribLocation(p, 1, "TexCoord");
        GLES30.glBindAttribLocation(p, 2, "COLOR");
        GLES30.glLinkProgram(p);
        GLES30.glDeleteShader(v);
        GLES30.glDeleteShader(f);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String l = GLES30.glGetProgramInfoLog(p);
            GLES30.glDeleteProgram(p);
            throw new RuntimeException(l);
        }
        return p;
    }
}
