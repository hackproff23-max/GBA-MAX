<?xml version="1.0" encoding="UTF-8"?>
<!--
   xBR Lv2 Quality (8 amostras) - portado de PPSSPP/OpenGL ES
   para o formato XML/GLSL (rubyTexture / rubyTextureSize).

   - Upscale inteligente com deteccao de bordas em 3 direcoes (45/30/60)
   - Curvas suaves via smoothstep no lugar de degraus (serrilhado)
   - Ajustes de cor: brilho, contraste, saturacao, gamma
   - Efeito LCD opcional (desligado por padrao)

   Base: Hyllian xBR (algoritmo), adaptado por Fabricio para PPSSPP,
   agora portado para o wrapper XML/GLSL compativel.
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
	precision highp float;
	#else
	precision mediump float;
	#endif
	uniform sampler2D rubyTexture;
	uniform vec2 rubyTextureSize;
	varying vec2 vTexCoord;

	// ============================================================
	// === AJUSTES (mexa aqui) ====================================
	// ============================================================

	// IMPORTANTE: SCALE precisa bater com output_width/output_height acima
	#define SCALE                 3.0

	// --- Qualidade xBR ---
	#define XBR_Y_WEIGHT         100.0
	#define XBR_EQ_THRESHOLD     20.0
	#define XBR_LV2_COEFFICIENT   2.0
	#define CURVE_SOFTNESS        0.40   // maior = curvas mais suaves

	// --- Cor ---
	#define BRIGHTNESS            1.0   // 1.0 = neutro | >1 mais claro | <1 mais escuro
	#define CONTRAST              1.12   // 1.0 = neutro | >1 mais contraste
	#define SATURATION            1.15   // 1.0 = neutro | >1 mais saturado | 0 = P&B
	#define GAMMA                 1.00   // 1.0 = neutro | <1 clareia sombras | >1 escurece

	// --- LCD / Opacidade ---
	// Simula leve efeito de tela LCD (opacidade do grid)
	// 0.0 = desligado | 0.15~0.30 = sutil | >0.40 = forte
	#define LCD_OPACITY           0.0

	// ============================================================

	const vec3 Y = vec3(0.299, 0.587, 0.114);

	vec4 df(vec4 A, vec4 B) {
		return abs(A - B);
	}

	bvec4 and4(bvec4 A, bvec4 B) {
		return bvec4(A.x && B.x, A.y && B.y, A.z && B.z, A.w && B.w);
	}

	vec4 weighted_distance(vec4 a, vec4 b, vec4 c, vec4 d, vec4 e, vec4 f, vec4 g, vec4 h) {
		return (df(a,b) + df(a,c) + df(d,e) + df(d,f) + 4.0 * df(g,h));
	}

	vec3 color_adjust(vec3 color) {
		// Gamma
		color = pow(max(color, vec3(0.0)), vec3(1.0 / max(GAMMA, 0.01)));

		// Brightness
		color *= BRIGHTNESS;

		// Contrast
		color = (color - 0.5) * CONTRAST + 0.5;

		// Saturation
		float luma = dot(color, Y);
		color = mix(vec3(luma), color, SATURATION);

		return clamp(color, 0.0, 1.0);
	}

	void main() {
		vec2 ps = 1.0 / rubyTextureSize;
		vec2 fp = fract(vTexCoord * rubyTextureSize);

		vec2 dx = vec2(ps.x, 0.0);
		vec2 dy = vec2(0.0, ps.y);

		// === 8 amostras (3x3 sem o canto superior-esquerdo) ===
		vec3 B = texture2D(rubyTexture, vTexCoord      - dy).rgb;
		vec3 C = texture2D(rubyTexture, vTexCoord + dx - dy).rgb;
		vec3 D = texture2D(rubyTexture, vTexCoord - dx     ).rgb;
		vec3 E = texture2D(rubyTexture, vTexCoord          ).rgb;
		vec3 F = texture2D(rubyTexture, vTexCoord + dx     ).rgb;
		vec3 G = texture2D(rubyTexture, vTexCoord - dx + dy).rgb;
		vec3 H = texture2D(rubyTexture, vTexCoord      + dy).rgb;
		vec3 I = texture2D(rubyTexture, vTexCoord + dx + dy).rgb;

		// Aprox. do pixel A (canto superior-esquerdo) usando B
		vec3 A = B;

		// Luminancia
		vec4 b = vec4(dot(B, Y), dot(D, Y), dot(H, Y), dot(F, Y)) * XBR_Y_WEIGHT;
		vec4 c = vec4(dot(C, Y), dot(A, Y), dot(G, Y), dot(I, Y)) * XBR_Y_WEIGHT;
		vec4 e = vec4(dot(E, Y)) * XBR_Y_WEIGHT;
		vec4 d = b.yzwx;
		vec4 f = b.wxyz;
		vec4 g = c.zwxy;
		vec4 h = b.zwxy;
		vec4 i = c.wxyz;

		// Linhas para curvas
		vec4 Ao = vec4( 1.0, -1.0, -1.0,  1.0);
		vec4 Bo = vec4( 1.0,  1.0, -1.0, -1.0);
		vec4 Co = vec4( 1.5,  0.5, -0.5,  0.5);
		vec4 Ax = vec4( 1.0, -1.0, -1.0,  1.0);
		vec4 Bx = vec4( 0.5,  2.0, -0.5, -2.0);
		vec4 Cx = vec4( 1.0,  1.0, -0.5,  0.0);
		vec4 Ay = vec4( 1.0, -1.0, -1.0,  1.0);
		vec4 By = vec4( 2.0,  0.5, -2.0, -0.5);
		vec4 Cy = vec4( 2.0,  0.0, -1.0,  0.5);

		vec4 fx      = Ao * fp.y + Bo * fp.x;
		vec4 fx_left = Ax * fp.y + Bx * fp.x;
		vec4 fx_up   = Ay * fp.y + By * fp.x;

		// Restricoes de interpolacao
		bvec4 interp_restriction_lv1 = and4(notEqual(e, f), notEqual(e, h));
		bvec4 interp_restriction_lv2_left = and4(notEqual(e, g), notEqual(d, g));
		bvec4 interp_restriction_lv2_up   = and4(notEqual(e, c), notEqual(b, c));

		// Curvas suaves (smoothstep) - prioridade qualidade
		vec4 delta = vec4(CURVE_SOFTNESS);
		vec4 fx45 = smoothstep(Co - delta, Co + delta, fx);
		vec4 fx30 = smoothstep(Cx - delta, Cx + delta, fx_left);
		vec4 fx60 = smoothstep(Cy - delta, Cy + delta, fx_up);

		// Deteccao de bordas
		bvec4 edr = and4(
			lessThan(
				weighted_distance(e, c, g, i, h, f, h, f),
				weighted_distance(h, d, i, f, i, b, e, i)
			),
			interp_restriction_lv1
		);

		bvec4 edr_left = and4(
			lessThanEqual(XBR_LV2_COEFFICIENT * df(f, g), df(h, c)),
			interp_restriction_lv2_left
		);

		bvec4 edr_up = and4(
			greaterThanEqual(df(f, g), XBR_LV2_COEFFICIENT * df(h, c)),
			interp_restriction_lv2_up
		);

		// Novas cores com curvas
		bvec4 nc45 = and4(edr, bvec4(fx45));
		bvec4 nc30 = and4(edr, and4(edr_left, bvec4(fx30)));
		bvec4 nc60 = and4(edr, and4(edr_up,   bvec4(fx60)));

		bvec4 nc = bvec4(
			nc30.x || nc60.x || nc45.x,
			nc30.y || nc60.y || nc45.y,
			nc30.z || nc60.z || nc45.z,
			nc30.w || nc60.w || nc45.w
		);

		vec4 final45 = vec4(nc45) * fx45;
		vec4 final30 = vec4(nc30) * fx30;
		vec4 final60 = vec4(nc60) * fx60;
		vec4 maximo  = max(max(final45, final30), final60);

		bvec4 px = lessThanEqual(df(e, f), df(e, h));

		vec3 pix = E;
		float blend = 0.0;

		if (nc.x) {
			pix = px.x ? F : H;
			blend = maximo.x;
		} else if (nc.y) {
			pix = px.y ? B : F;
			blend = maximo.y;
		} else if (nc.z) {
			pix = px.z ? D : B;
			blend = maximo.z;
		} else if (nc.w) {
			pix = px.w ? H : D;
			blend = maximo.w;
		}

		// Mistura suave (curva em vez de degrau)
		vec3 res = mix(E, pix, clamp(blend, 0.0, 1.0));

		// Ajustes de cor
		res = color_adjust(res);

		// Efeito LCD (opacidade do grid) - 0.0 = desligado
		if (LCD_OPACITY > 0.001) {
			vec2 pixelDelta = ps / SCALE;
			vec2 pixel = fract(vTexCoord / pixelDelta);
			float lcd = 1.0 - LCD_OPACITY * (0.5 + 0.5 * sin(pixel.x * 6.28318) * sin(pixel.y * 6.28318));
			res *= lcd;
		}

		gl_FragColor = vec4(clamp(res, 0.0, 1.0), 1.0);
	}
]]></fragment>
</shader>
