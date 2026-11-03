<?xml version="1.0" encoding="UTF-8"?>
<!--
   3xBRZ original otimizada (GBA-MAX) - upscale 3x

   - Algoritmo xBRZ original (Zenju): 21 pixels vizinhos, distancia YCbCr, deteccao de
     direcao dominante, linhas rasas/ingremes/diagonais e cantos arredondados
   - Otimizacoes: pixel central do bloco 3x3 sai direto; areas lisas saem apos 9 leituras;
     as demais leituras so acontecem nas bordas; cada rotacao so e calculada se tocar o pixel
   - Sem ajuste de cor (brilho, contraste e gamma ficam no menu do app)
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
#version 300 es
precision highp float;
precision highp int;
uniform sampler2D rubyTexture;
uniform vec2 rubyTextureSize;
varying vec2 vTexCoord;

#define TOL    0.1176   // equalColorTolerance 30/255
#define DOM    3.6      // dominantDirectionThreshold
#define STEEP  2.2      // steepDirectionThreshold

vec3 P[25];
ivec2 gB;
ivec2 gMax;

vec3 ld(int i, int j) {
  return texelFetch(rubyTexture, clamp(gB + ivec2(i, j), ivec2(0), gMax), 0).rgb;
}

vec3 Q(int i, int j) { return P[(j + 2) * 5 + i + 2]; }

void put(int i, int j) { P[(j + 2) * 5 + i + 2] = ld(i, j); }

float dist(vec3 a, vec3 b) {
  vec3 d = a - b;
  float y = dot(d, vec3(0.2126, 0.7152, 0.0722));
  float cb = 0.53919 * (d.b - y);
  float cr = 0.63517 * (d.r - y);
  return sqrt(y * y + cb * cb + cr * cr);
}

bool eq(vec3 a, vec3 b) { return dist(a, b) < TOL; }
bool same(vec3 a, vec3 b) { return dot(abs(a - b), vec3(1.0)) < 0.002; }
bool neq(vec3 a, vec3 b) { return !same(a, b); }

// bloco 2x2 cujo canto superior esquerdo e (bx,by): true se for liso (nada a misturar)
bool flatBlk(int bx, int by) {
  vec3 f = Q(bx, by), g = Q(bx + 1, by), j = Q(bx, by + 1), k = Q(bx + 1, by + 1);
  return (same(f, g) && same(j, k)) || (same(f, j) && same(g, k));
}

// x=f, y=g, z=j, w=k : tipo de mistura (0 nenhuma, 1 normal, 2 dominante) do canto central do bloco
ivec4 pre(int bx, int by) {
  vec3 b = Q(bx, by - 1), c = Q(bx + 1, by - 1);
  vec3 e = Q(bx - 1, by), f = Q(bx, by), g = Q(bx + 1, by), h = Q(bx + 2, by);
  vec3 i = Q(bx - 1, by + 1), j = Q(bx, by + 1), k = Q(bx + 1, by + 1), l = Q(bx + 2, by + 1);
  vec3 n = Q(bx, by + 2), o = Q(bx + 1, by + 2);
  float jg = dist(i, f) + dist(f, c) + dist(n, k) + dist(k, h) + 4.0 * dist(j, g);
  float fk = dist(e, j) + dist(j, o) + dist(b, g) + dist(g, l) + 4.0 * dist(f, k);
  ivec4 r = ivec4(0);
  if (jg < fk) {
    int d = DOM * jg < fk ? 2 : 1;
    if (neq(f, g) && neq(f, j)) r.x = d;
    if (neq(k, j) && neq(k, g)) r.w = d;
  } else if (fk < jg) {
    int d = DOM * fk < jg ? 2 : 1;
    if (neq(j, f) && neq(j, k)) r.z = d;
    if (neq(g, f) && neq(g, k)) r.y = d;
  }
  return r;
}

// vizinho (u,v) do pixel central no quadro girado de r x 90 graus
vec3 K(int r, int u, int v) {
  int dx, dy;
  if (r == 0) { dx = u; dy = v; }
  else if (r == 1) { dx = v; dy = -u; }
  else if (r == 2) { dx = -u; dy = -v; }
  else { dx = -v; dy = u; }
  return Q(dx, dy);
}

vec3 rot(int r, vec3 res, ivec2 o, int bTL, int bTR, int bBL, int bBR) {
  int xr, yr;
  if (r == 0) { xr = o.x; yr = o.y; }
  else if (r == 1) { xr = 2 - o.y; yr = o.x; }
  else if (r == 2) { xr = 2 - o.x; yr = 2 - o.y; }
  else { xr = o.y; yr = 2 - o.x; }
  if (xr != 2 && yr != 2) return res; // este canto nao toca este pixel

  int BR, TR, BL;
  if (r == 0) { BR = bBR; TR = bTR; BL = bBL; }
  else if (r == 1) { BR = bTR; TR = bTL; BL = bBR; }
  else if (r == 2) { BR = bTL; TR = bBL; BL = bTR; }
  else { BR = bBL; TR = bBR; BL = bTL; }
  if (BR < 1) return res;

  vec3 e = K(r, 0, 0), f = K(r, 1, 0), h = K(r, 0, 1), i = K(r, 1, 1);
  vec3 g = K(r, -1, 1), c = K(r, 1, -1), d = K(r, -1, 0), b = K(r, 0, -1);

  bool line;
  if (BR >= 2) line = true;
  else if (TR != 0 && !eq(e, g)) line = false;
  else if (BL != 0 && !eq(e, c)) line = false;
  else if (!eq(e, i) && eq(g, h) && eq(h, i) && eq(i, f) && eq(f, c)) line = false;
  else line = true;

  vec3 px = dist(e, f) <= dist(e, h) ? f : h;
  float al = 0.0;
  if (line) {
    float fg = dist(f, g), hc = dist(h, c);
    bool sh = STEEP * fg <= hc && neq(e, g) && neq(d, g);
    bool st = STEEP * hc <= fg && neq(e, c) && neq(b, c);
    if (xr == 2 && yr == 2) al = (sh || st) ? 1.0 : 0.875;
    else if (sh && st) {
      if (xr == 2 && yr == 1) al = 0.75;
      else if (xr == 1 && yr == 2) al = 0.75;
      else al = 0.25;                       // (2,0) e (0,2)
    } else if (sh) {
      if (xr == 2 && yr == 1) al = 0.75;
      else if (xr == 2 && yr == 0) al = 0.25;
      else if (xr == 1 && yr == 2) al = 0.25;
    } else if (st) {
      if (xr == 1 && yr == 2) al = 0.75;
      else if (xr == 0 && yr == 2) al = 0.25;
      else if (xr == 2 && yr == 1) al = 0.25;
    } else {
      if ((xr == 2 && yr == 1) || (xr == 1 && yr == 2)) al = 0.125;
    }
  } else {
    if (xr == 2 && yr == 2) al = 0.45;
  }
  return mix(res, px, al);
}

void main() {
  vec2 pp = vTexCoord * rubyTextureSize;
  gB = ivec2(floor(pp));
  gMax = ivec2(rubyTextureSize) - ivec2(1);
  ivec2 o = ivec2(min(floor(fract(pp) * 3.0), vec2(2.0)));
  vec3 E = ld(0, 0);
  // o miolo do bloco 3x3 nunca e alterado
  if (o.x == 1 && o.y == 1) { gl_FragColor = vec4(E, 1.0); return; }

  for (int j = -1; j <= 1; j++)
    for (int i = -1; i <= 1; i++) put(i, j);

  bool n0 = !flatBlk(-1, -1), n1 = !flatBlk(0, -1), n2 = !flatBlk(-1, 0), n3 = !flatBlk(0, 0);
  if (!(n0 || n1 || n2 || n3)) { gl_FragColor = vec4(E, 1.0); return; }

  put(-2, -1); put(-2, 0); put(-2, 1);
  put(2, -1);  put(2, 0);  put(2, 1);
  put(-1, -2); put(0, -2); put(1, -2);
  put(-1, 2);  put(0, 2);  put(1, 2);

  int bTL = 0, bTR = 0, bBL = 0, bBR = 0;
  if (n0) bTL = pre(-1, -1).w;
  if (n1) bTR = pre(0, -1).z;
  if (n2) bBL = pre(-1, 0).y;
  if (n3) bBR = pre(0, 0).x;
  if (bTL + bTR + bBL + bBR == 0) { gl_FragColor = vec4(E, 1.0); return; }

  vec3 res = E;
  res = rot(0, res, o, bTL, bTR, bBL, bBR);
  res = rot(1, res, o, bTL, bTR, bBL, bBR);
  res = rot(2, res, o, bTL, bTR, bBL, bBR);
  res = rot(3, res, o, bTL, bTR, bBL, bBR);
  gl_FragColor = vec4(res, 1.0);
}
]]></fragment>
</shader>
