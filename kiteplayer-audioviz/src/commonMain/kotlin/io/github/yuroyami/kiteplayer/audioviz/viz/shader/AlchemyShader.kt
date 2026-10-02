package io.github.yuroyami.kiteplayer.audioviz.viz.shader

/**
 * Alchemy's picture: the memory field as banded, lit ink with iso-lines, the core, the kick push
 * ring from the impulse list, and the Gas form's filaments. The field helpers come from
 * [io.github.yuroyami.kiteplayer.audioviz.viz.field.FieldShader], prepended by [ShaderPreset].
 */
internal object AlchemyShader {

    const val SOURCE: String = """
uniform float uInkSteps;
uniform float4 uGold;
uniform float uPush;
uniform float uGas;
uniform float uFold;

half4 main(float2 position) {
    float2 p = centred(camPoint(position));
    float radius = length(p);
    float2 ink = fieldAt(position);
    float2 slope = fieldSlope(position);
    float value = ink.x;
    float age = ink.y;
    // The palette walks with the genes and leans a little towards the song's key when it is known.
    float hue = uWalk + uKeyHue * 0.15;

    // Banded ink: the palette walked by the ink's age, held to flat steps, lit from the upper left.
    float stepped = bands(value, uInkSteps);
    float3 band = paletteCycled(0.15 + hue + age * 0.06 + stepped * 0.35);
    float light = lit(slope);
    float3 colour = band * stepped * (0.55 + 0.9 * light) * smoothstep(0.02, 0.12, value);

    // Iso-lines at every step: thin, bright, one pixel wide at 1080 lines.
    float halfWidth = max(0.6, uResolution.y / 1800.0);
    float lines = 0.0;
    for (int i = 1; i < 10; i++) {
        float level = float(i) / uInkSteps;
        if (level >= 1.0) break;
        lines = max(lines, isoLine(value, level, halfWidth, slope) * (0.4 + 0.6 * level));
    }
    float3 lineColour = paletteCycled(0.55 + hue + age * 0.06);
    colour = mix(colour, lineColour, lines * 0.9);

    // The Gas form: ridge filaments stretched along the flow, where the ink is.
    if (uGas > 0.01) {
        float2 f = float2(p.x * 2.5, p.y * 9.0);
        float n = fbm(f + float2(11.3, 5.7) + uMusicTime * 0.05);
        float v = (n - 0.5) * 3.2;
        float strand = pow(max(1.0 - abs(v), 0.0), 4.0);
        float wisp = pow(max(1.0 - abs(fract(n * 3.0 + 0.5) * 2.0 - 1.0), 0.0), 16.0);
        float3 gasColour = paletteCycled(0.8 + hue) * strand + paletteCycled(0.3 + hue) * wisp * 0.6;
        colour = mix(colour, colour + gasColour * (0.3 + value), uGas);
    }

    // The core: a small white star with a halo the kick widens.
    float pixels = radius * uResolution.y * 0.5;
    float coreSize = max(1.5, uResolution.y * 0.01) * (1.0 + 0.6 * uPush);
    float core = 1.0 - smoothstep(coreSize - 0.5, coreSize + 1.0, pixels);
    core += (0.5 + 0.5 * uPush) * exp(-pixels / (coreSize * 3.0));
    colour = mix(colour, float3(1.0), clamp(core, 0.0, 1.0));

    // The kick's push ring, from the newest low impulses: a thin bright front travelling outward.
    for (int i = 0; i < 4; i++) {
        if (float(i) >= uImpulseCount) break;
        float4 b = impulseB(i);
        if (abs(b.y * 3.0) > 0.5) continue;
        float ringAge = b.x * 8.0;
        if (ringAge > 0.8) continue;
        float4 a = impulseA(i);
        float front = radius - ringAge * 1.6;
        float ring = exp(-front * front * 900.0) * a.z * (1.0 - ringAge / 0.8);
        colour += paletteCycled(0.55 + hue) * ring * 1.5;
    }

    // The transmutation: gold inside the spreading front and inside the cooling one.
    float gold = (1.0 - smoothstep(uGold.x - 0.08, uGold.x, radius)) * (1.0 - smoothstep(uGold.y - 0.08, uGold.y, radius));
    float3 goldInk = float3(1.0, 0.78, 0.12) * (0.5 + 0.5 * stepped) + float3(1.0, 0.9, 0.62) * lines;
    colour = mix(colour, goldInk * max(stepped, lines), gold * step(0.02, value));

    // Faint stars and haze keep the negative space alive, and the whole follows the shared light.
    float star = pow(hash21(floor(position * 0.5) + uSeed), 80.0) * 0.3;
    colour += float3(star) + paletteCycled(0.15 + hue) * 0.03;
    return half4(clamp(toneMap(colour), 0.0, 1.0), 1.0);
}
"""
}
