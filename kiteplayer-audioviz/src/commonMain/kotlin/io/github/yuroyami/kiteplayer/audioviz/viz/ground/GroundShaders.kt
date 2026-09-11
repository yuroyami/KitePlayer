package io.github.yuroyami.kiteplayer.audioviz.viz.ground

/**
 * The shader source for every ground.
 *
 * Each one is cheap on purpose: a few sines, a hash or two, and reads of the shared noise picture
 * instead of noise worked out per pixel. The tests run them on the processor, where that matters.
 */
internal object GroundShaders {

    /** Shared by every ground, after the library header. */
    const val HEADER: String = """
uniform float uPhase;
uniform float uTravel;
uniform float uDim;
uniform shader uNoiseTex;

// Centred and aspect correct after the camera: 0,0 in the middle, 1 is half the screen height.
float2 groundUv(float2 position) {
    return centred(camPoint(position));
}

// Smooth random value from the shared tiling picture. One unit is one tile.
float tex(float2 p) {
    return uNoiseTex.eval(p * 256.0).r;
}

float3 tint(float where) {
    return paletteLoop(where + uWalk);
}

// The dim colour every ground sits on, so no pixel of it is ever the bare background.
float3 groundBase(float2 uv) {
    return palette(0.12) * (0.2 + 0.04 * uv.y) + palette(0.0) * 0.04;
}

// Fine grain crawling two ways at once, so every pixel of a ground changes a little all the time.
float shimmer(float2 uv) {
    return tex(uv * 1.7 + float2(uPhase * 1.9, -uPhase * 1.3)) - tex(uv * 1.3 - float2(uPhase * 1.1, uPhase * 1.7));
}

// A little grain added everywhere, so even the darkest part of a ground is never still.
float3 grain(float2 uv) {
    return palette(0.4) * 0.06 * shimmer(uv * 0.8);
}

// For textures added on top: no grain, since the ground under them already has it.
half4 detailOut(float3 colour) {
    return half4(clamp(colour * uDim, 0.0, 1.0), 1.0);
}

half4 groundOut(float3 colour, float2 uv) {
    return half4(clamp((colour) * uDim, 0.0, 1.0), 1.0);
}
"""

    fun source(kind: GroundKind): String = when (kind) {
        GroundKind.Plasma -> PLASMA
        GroundKind.Cloud -> CLOUD
        GroundKind.Stars -> STARS
        GroundKind.Grid -> GRID
        GroundKind.Rings -> RINGS
        GroundKind.Spectrogram -> SPECTROGRAM
        GroundKind.Water -> WATER
        GroundKind.Voronoi -> VORONOI
        GroundKind.Rays -> RAYS
        GroundKind.Hatch -> HATCH
        GroundKind.Rain -> RAIN
        GroundKind.Fog -> FOG
    }

    private const val PLASMA = """
half4 main(float2 position) {
    float2 uv = groundUv(position);
    float t = uPhase * 2.2;
    float field = sin(uv.x * (2.6 + uMid) + t) + sin(uv.y * 2.3 - t * 0.8)
        + sin((uv.x + uv.y) * 1.9 + t * 1.3) + sin(length(uv) * (3.4 + 2.0 * uBass) - t * 1.7);
    float lit = field * 0.125 + 0.5;
    float3 colour = tint(field * 0.1 + t * 0.02) * (0.07 + 0.17 * lit * lit) * (0.75 + 0.45 * uEnergy);
    return groundOut(groundBase(uv) + colour * (1.0 + 0.35 * shimmer(uv)) + tint(0.3 + lit * 0.2) * 0.03 * shimmer(uv * 1.3), uv);
}
"""

    private const val CLOUD = """
half4 main(float2 position) {
    float2 uv = groundUv(position);
    float2 q = uv * 0.32 + float2(uPhase * 0.16, uPhase * 0.07);
    float n = tex(q) * 0.62 + tex(q * 2.03 + float2(0.37, 0.11) - float2(uPhase * 0.2, -uPhase * 0.05)) * 0.38;
    float lit = smoothstep(0.2, 0.9, n);
    float3 colour = tint(n * 0.45 + uPhase * 0.02) * (0.05 + 0.2 * lit * lit) * (0.7 + 0.5 * uEnergy);
    return groundOut(groundBase(uv) + colour * (1.0 + 0.4 * shimmer(uv)) + tint(0.3 + n * 0.2) * 0.022 * shimmer(uv * 1.3), uv);
}
"""

    private const val STARS = """
half4 main(float2 position) {
    float2 uv = groundUv(position);
    // Mist that drifts and shimmers everywhere, so no stretch of sky between the stars holds still.
    float mist = tex(uv * 0.3 + float2(uPhase * 0.3, uPhase * 0.1));
    float3 colour = groundBase(uv) + tint(0.25 + mist * 0.3) * (0.04 + 0.16 * mist) * (1.0 + 0.5 * shimmer(uv))
        + tint(0.4) * 0.045 * shimmer(uv * 1.2);
    for (int layer = 0; layer < 2; layer++) {
        float depth = 1.0 + float(layer);
        float2 q = uv * (8.0 * depth) + float2(uPhase * 1.3 / depth, uPhase * 0.22);
        float2 cell = floor(q);
        float h = hash21(cell + float(layer) * 17.0);
        float2 at = cell + 0.2 + 0.6 * fract(float2(h * 13.1, h * 71.7));
        float reach = length(q - at);
        float twinkle = 0.5 + 0.5 * sin(uPhase * 6.0 + h * 40.0);
        float star = smoothstep(0.11 + 0.06 * uKick, 0.0, reach) * step(0.45, h) * twinkle;
        colour += tint(h) * star * (1.1 / depth);
    }
    return groundOut(colour, uv);
}
"""

    private const val GRID = """
half4 main(float2 position) {
    float2 uv = groundUv(position);
    float away = abs(uv.y) + 0.04;
    float depth = 0.5 / away;
    float2 q = float2(uv.x * depth, depth + uTravel);
    float2 cell = floor(q);
    float2 f = abs(fract(q) - 0.5);
    float line = smoothstep(0.44, 0.5, max(f.x, f.y));
    float lit = band(fract(abs(cell.x) * 0.11 + 0.1));
    float near = clamp(1.4 - depth * 0.12, 0.0, 1.0);
    float3 colour = groundBase(uv) + tint(abs(cell.x) * 0.05 + uPhase * 0.02) * (line * 0.3 + lit * lit * 0.14) * near;
    return groundOut(colour * (1.0 + 0.25 * shimmer(uv)) + palette(0.8) * 0.05 * exp(-away * 9.0), uv);
}
"""

    private const val RINGS = """
half4 main(float2 position) {
    float2 uv = groundUv(position);
    float2 source = float2(sin(uPhase * 0.5) * 0.55, cos(uPhase * 0.41) * 0.35);
    float reach = length(uv - source);
    float wave = fract(reach * 2.6 - uTravel * 0.5);
    float ring = smoothstep(0.0, 0.1 + 0.12 * uBass, wave) * smoothstep(0.55, 0.15, wave);
    float3 colour = groundBase(uv) + tint(reach * 0.25 - uTravel * 0.04) * ring * (0.1 + 0.08 * uEnergy);
    return groundOut(colour * (1.0 + 0.3 * shimmer(uv)) + palette(0.2) * 0.05 * tex(uv * 0.3 + uPhase * 0.05), uv);
}
"""

    private const val SPECTROGRAM = """
half4 main(float2 position) {
    float2 uv = groundUv(position);
    float away = abs(uv.y) + 0.05;
    float depth = 0.55 / away;
    float age = clamp(depth * 0.09 - 0.05, 0.0, 1.0);
    float where = clamp(uv.x / (depth * 0.9) * 0.5 + 0.5, 0.0, 1.0);
    float level = history(where, age);
    float fade = 1.0 - age * 0.8;
    float3 colour = groundBase(uv) + tint(where * 0.5 + uPhase * 0.03) * (0.04 + 0.26 * level * level) * fade;
    return groundOut(colour * (1.0 + 0.35 * shimmer(uv)) + palette(0.7) * 0.04 * exp(-away * 10.0), uv);
}
"""

    private const val WATER = """
half4 main(float2 position) {
    float2 uv = groundUv(position) * 1.4;
    float2 q = uv + float2(uPhase * 0.18, uPhase * 0.1);
    float n = tex(q * 0.45);
    float wave = sin(q.x * 3.6 + n * 6.0 + uPhase * 2.5) * sin(q.y * 3.1 - n * 5.0 + uPhase * 2.0);
    float caustic = pow(1.0 - abs(wave), 5.0);
    float3 colour = groundBase(uv) + tint(0.35 + n * 0.2) * (0.04 + 0.08 * n)
        + palette(0.85) * caustic * (0.06 + 0.12 * uTreble);
    return groundOut(colour * (1.0 + 0.3 * shimmer(uv)), uv);
}
"""

    private const val VORONOI = """
half4 main(float2 position) {
    float2 uv = groundUv(position) * 2.2 + float2(uPhase * 0.15, uPhase * 0.05);
    float2 cell = floor(uv);
    float2 corner = cell + step(0.5, fract(uv)) - 1.0;
    float best = 9.0;
    float second = 9.0;
    float id = 0.0;
    for (int j = 0; j < 2; j++) {
        for (int i = 0; i < 2; i++) {
            float2 c = corner + float2(float(i), float(j));
            float h = hash21(c);
            float2 wobble = abs(fract(float2(h * 3.1, h * 7.3) + uPhase * float2(0.22, 0.16)) * 2.0 - 1.0);
            float2 seed = c + 0.15 + 0.7 * wobble;
            float d = length(uv - seed);
            if (d < best) { second = best; best = d; id = h; } else if (d < second) { second = d; }
        }
    }
    float edge = smoothstep(0.1, 0.0, second - best);
    float lit = band(id);
    float3 colour = groundBase(uv) + tint(id * 0.6) * (0.03 + 0.1 * lit) + tint(id + 0.5) * edge * (0.12 + 0.2 * lit);
    return groundOut(colour * (1.0 + 0.3 * shimmer(uv * 0.5)), uv);
}
"""

    private const val RAYS = """
half4 main(float2 position) {
    float2 uv = groundUv(position);
    float2 source = float2(sin(uPhase * 0.45) * 0.6, -0.3 + cos(uPhase * 0.37) * 0.3);
    float2 d = uv - source;
    float reach = length(d);
    float2 heading = d / max(reach, 0.001);
    float angle = atan(d.y, d.x);
    // The noise is read round a circle and the tint from the heading, so nothing jumps where the angle wraps.
    float wobble = tex(heading * 0.35 + float2(uPhase * 0.08, 0.0)) * 7.0;
    float rays = pow(0.5 + 0.5 * sin(angle * 9.0 + wobble + uPhase * 2.2), 3.0);
    float glow = exp(-reach * 1.1);
    float3 colour = groundBase(uv) + tint(heading.y * 0.15 + uPhase * 0.03) * (0.04 + 0.13 * rays) * (0.35 + glow) * (0.7 + 0.5 * uEnergy);
    return groundOut(colour * (1.0 + 0.3 * shimmer(uv)), uv);
}
"""

    private const val HATCH = """
half4 main(float2 position) {
    float2 uv = groundUv(position);
    float angle = 0.6 + uBarPhase * 1.5708;
    float2 across = float2(cos(angle), sin(angle));
    float lines = smoothstep(0.7, 1.0, sin(dot(uv, across) * 60.0 + uPhase * 5.0));
    float2 g = fract(uv * 16.0 + float2(uPhase * 0.3, uPhase * 0.1)) - 0.5;
    float dots = smoothstep(0.16 + 0.22 * uTreble, 0.08, length(g));
    float3 colour = groundBase(uv) + tint(0.55) * (lines * 0.07 + dots * 0.06)
        + palette(0.3) * 0.06 * tex(uv * 0.25 + uPhase * 0.04);
    return groundOut(colour * (1.0 + 0.25 * shimmer(uv)), uv);
}
"""

    private const val RAIN = """
half4 main(float2 position) {
    float2 uv = groundUv(position);
    float3 colour = groundBase(uv) + palette(0.25) * 0.06 * tex(uv * 0.22 + float2(0.0, uPhase * 0.05));
    for (int layer = 0; layer < 2; layer++) {
        float depth = 1.0 + float(layer) * 0.8;
        float2 q = float2(uv.x * 28.0 * depth + uv.y * 3.0, uv.y * 2.5 * depth - uPhase * 6.0 / depth);
        float column = floor(q.x);
        float h = hash21(float2(column, float(layer) * 5.0));
        float y = fract(q.y + h * 7.0);
        float streak = smoothstep(0.0, 0.04, y) * smoothstep(0.4, 0.0, y) * smoothstep(0.5, 0.15, abs(fract(q.x) - 0.5));
        colour += tint(0.6 + h * 0.2) * streak * step(0.3, h) * (0.2 / depth);
    }
    return groundOut(colour * (1.0 + 0.3 * shimmer(uv)), uv);
}
"""

    private const val FOG = """
half4 main(float2 position) {
    float2 uv = groundUv(position);
    float2 q = uv * 0.24 + float2(uPhase * 0.12, -uPhase * 0.07);
    float n = tex(q) * 0.65 + tex(q * 2.1 + float2(0.5, 0.2) + float2(-uPhase * 0.16, uPhase * 0.09)) * 0.35;
    float3 colour = groundBase(uv) + tint(0.2 + n * 0.3) * (0.04 + 0.13 * n * n);
    return groundOut(colour * (1.0 + 0.45 * shimmer(uv)), uv);
}
"""
}
