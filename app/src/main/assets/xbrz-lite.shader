<?xml version="1.0" encoding="UTF-8"?>
<!--
   xBRZ Lite 3x - versao leve do xBR/xBRZ para celulares fracos (GBA-MAX)

   - Avalia so o canto mais proximo de cada pixel (nao os 4 de uma vez)
   - Sai cedo nas areas lisas: so as bordas pagam o custo completo
   - Curvas com smoothstep: diagonais e curvas sem serrilhado
   - Sem ajuste de cor aqui (brilho, contraste e gamma ficam no menu do app)
   - Para a versao mais leve ainda (480x320) use xbrz-lite-2x
   Baseado no algoritmo xBR (Hyllian).
-->
<shader language="GLSL">
<vertex><![CDATA[
attribute vec2 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
  vTexCoord = aTexCoord;
  gl_Position = vec4(aPosition, 0.0, 1.0);
}
]]></vertex>
<fragment filter="nearest" output_width="300%" output_height="300%"><![CDATA[
#ifdef GL_FRAGMENT_PRECISION_HIGH
#define HP highp
#else
#define HP mediump
#endif
precision mediump float;
uniform sampler2D rubyTexture;
uniform HP vec2 rubyTextureSize;
varying HP vec2 vTexCoord;

#define SOFT 0.34   // maior = bordas mais macias
#define EQ   0.06   // cores com diferenca menor que isso sao iguais
#define LV2  2.0

float df(vec3 a, vec3 b) {
  vec3 d = abs(a - b);
  return dot(d, vec3(0.45, 1.0, 0.35));
}

void main() {
  HP vec2 fp = fract(vTexCoord * rubyTextureSize);
  vec2 s = step(0.5001, fp) * 2.0 - 1.0;
  HP vec2 o = s / rubyTextureSize;

  vec3 E = texture2D(rubyTexture, vTexCoord).rgb;
  vec3 F = texture2D(rubyTexture, vTexCoord + vec2(o.x, 0.0)).rgb;
  vec3 H = texture2D(rubyTexture, vTexCoord + vec2(0.0, o.y)).rgb;
  float dEF = df(E, F);
  float dEH = df(E, H);
  if (dEF < EQ || dEH < EQ) {
    gl_FragColor = vec4(E, 1.0);
    return;
  }

  vec3 I = texture2D(rubyTexture, vTexCoord + o).rgb;
  vec3 C = texture2D(rubyTexture, vTexCoord + vec2(o.x, -o.y)).rgb;
  vec3 G = texture2D(rubyTexture, vTexCoord + vec2(-o.x, o.y)).rgb;
  vec3 B = texture2D(rubyTexture, vTexCoord - vec2(0.0, o.y)).rgb;
  vec3 D = texture2D(rubyTexture, vTexCoord - vec2(o.x, 0.0)).rgb;

  float wa = df(E, C) + df(E, G) + df(I, H) + df(I, F) + 4.0 * df(H, F);
  float wb = df(H, D) + df(H, I) + df(F, I) + df(F, B) + 4.0 * df(E, I);
  if (wa >= wb) {
    gl_FragColor = vec4(E, 1.0);
    return;
  }

  vec2 w = 0.5 + s * (fp - 0.5);
  float m = smoothstep(1.5 - SOFT, 1.5 + SOFT, w.x + w.y);
  float dFG = df(F, G);
  float dHC = df(H, C);
  if (LV2 * dFG <= dHC && df(E, G) > EQ && df(D, G) > EQ)
    m = max(m, smoothstep(1.0 - SOFT, 1.0 + SOFT, w.y + 0.5 * w.x));
  if (dFG >= LV2 * dHC && df(E, C) > EQ && df(B, C) > EQ)
    m = max(m, smoothstep(2.0 - SOFT, 2.0 + SOFT, 2.0 * w.x + w.y));

  vec3 pix = dEF <= dEH ? F : H;
  gl_FragColor = vec4(mix(E, pix, m), 1.0);
}
]]></fragment>
</shader>
