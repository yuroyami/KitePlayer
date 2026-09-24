package io.github.yuroyami.kiteplayer.audioviz.viz.presets

/**
 * The line pass of [Contour]: the height grid read smoothly and drawn as contour lines at the
 * screen's own resolution.
 *
 * The grid arrives as a small picture, one texel per cell. Its height is split over the red and
 * green channels, so it keeps sixteen bits; blue holds how much the edge islets flicker. Four
 * hardware-blended reads give a cubic B-spline of the grid, which is smooth enough that a line can
 * slide by a fraction of a pixel. Eight more reads give that same surface's slope, which turns the
 * gap between a height and a level into a distance in pixels, so every line keeps its width
 * however steep the ground is. There are no loops per pixel.
 */
internal object ContourShader {

    const val SOURCE: String = """
uniform shader uGrid;
// The grid's size in cells, and how many pixels one cell covers.
uniform float2 uGridSize;
uniform float uCell;
// The height of the grid's full range, in levels. A line sits at every whole level from 1 up.
uniform float uTop;
// Sea level, in levels, and how many levels below it count as deep water.
uniform float uSea;
uniform float uDepth;
// Levels above the sea where the land lines start and finish turning white.
uniform float uWhiteFrom;
uniform float uWhiteTo;
uniform float uLight;
// Extra light at low tide, how far the sea lines have turned to land colour, and the coastline's presence.
uniform float uBoost;
uniform float uAmber;
uniform float uCoast;
uniform float uFlicker;
uniform float uGlow;
// Half widths in pixels of a plain line, an index line and the coastline, and the reach of the glow.
uniform float4 uWidths;
// Gaps in pixels between neighbouring lines at which a plain line is gone, and fully drawn.
uniform float2 uCrowd;
// Levels from one line to the next: 1 on a large frame, more on a small one, as a small printed map
// draws fewer contours.
uniform float uInterval;
uniform float3 uDeep;
uniform float3 uShallow;
uniform float3 uLand;
uniform float3 uPeak;

// One blended read of the grid's height, 0 to 1. Blending the two channels separately and joining
// them afterwards gives the same answer as blending the joined value, so the hardware stays exact.
float heightOf(half4 texel) {
    return (float(texel.r) * 65280.0 + float(texel.g) * 255.0) / 65535.0;
}

float heightAt(float2 point) {
    return heightOf(uGrid.eval(point));
}

// How much of this pixel line n covers; it sits at level n times the interval. Every fourth line is
// a thicker index line.
float cover(float n, float level, float steep) {
    if (n < 0.5) return 0.0;
    float index = step(mod(n + 0.5, 4.0), 1.0);
    float halfWidth = mix(uWidths.x, uWidths.y, index);
    float away = abs(level - n * uInterval) / steep;
    // Where the lines still crowd closer than a few pixels the plain ones give way, so a steep flank
    // reads as index lines rather than as a filled band.
    float room = mix(1.0, 4.0, index) * uInterval / steep;
    return clamp(halfWidth + 0.5 - away, 0.0, 1.0) * smoothstep(uCrowd.x, uCrowd.y, room);
}

// The colour of line n: deep blue to cyan under the sea, amber above it, white towards the summits.
float3 tint(float n) {
    float above = n * uInterval - uSea;
    float deep = clamp(-above / uDepth, 0.0, 1.0);
    float3 water = mix(uShallow, uDeep, deep);
    water = mix(water, uLand, uAmber);
    float3 land = mix(uLand, uPeak, smoothstep(uWhiteFrom, uWhiteTo, above));
    return mix(water, land, smoothstep(-0.5, 0.5, above));
}

// The glow round a summit ring: none below the white rings, all of it on the highest ones.
float halo(float n, float level, float steep) {
    if (n < 0.5) return 0.0;
    float hot = smoothstep(uWhiteFrom + 2.0, uWhiteTo + 1.0, n * uInterval - uSea);
    float away = max(abs(level - n * uInterval) / steep - uWidths.x, 0.0);
    return hot * exp(-away / uWidths.w);
}

half4 main(float2 position) {
    // Into grid cells, where the centre of texel i sits at i + 0.5.
    float2 g = (position - uResolution * 0.5) / uCell + uGridSize * 0.5;
    float2 c = g - 0.5;
    float2 i = floor(c);
    float2 f = c - i;
    float2 f2 = f * f;
    float2 f3 = f2 * f;
    float2 r = 1.0 - f;
    // Cubic B-spline weights for texels i - 1 to i + 2, paired so each pair is one blended read.
    float2 w0 = r * r * r / 6.0;
    float2 w1 = (3.0 * f3 - 6.0 * f2 + 4.0) / 6.0;
    float2 w3 = f3 / 6.0;
    float2 lowPair = w0 + w1;
    float2 highPair = 1.0 - lowPair;
    float2 lowAt = i - 0.5 + w1 / lowPair;
    float2 highAt = i + 1.5 + w3 / highPair;
    // The weights' slopes. The lower pair's add up to minus the upper pair's, and each pair keeps
    // one sign, so the slope is two blended reads a row as well.
    float2 slopePair = 0.5 + f - f2;
    float2 slopeLowAt = i - 0.5 + (2.0 * f - 1.5 * f2) / slopePair;
    float2 slopeHighAt = i + 1.5 + 0.5 * f2 / slopePair;

    half4 t00 = uGrid.eval(float2(lowAt.x, lowAt.y));
    half4 t10 = uGrid.eval(float2(highAt.x, lowAt.y));
    half4 t01 = uGrid.eval(float2(lowAt.x, highAt.y));
    half4 t11 = uGrid.eval(float2(highAt.x, highAt.y));
    float height = lowPair.y * (lowPair.x * heightOf(t00) + highPair.x * heightOf(t10)) +
        highPair.y * (lowPair.x * heightOf(t01) + highPair.x * heightOf(t11));
    float flicker = lowPair.y * (lowPair.x * float(t00.b) + highPair.x * float(t10.b)) +
        highPair.y * (lowPair.x * float(t01.b) + highPair.x * float(t11.b));
    float across = slopePair.x * (
        lowPair.y * (heightAt(float2(slopeHighAt.x, lowAt.y)) - heightAt(float2(slopeLowAt.x, lowAt.y))) +
        highPair.y * (heightAt(float2(slopeHighAt.x, highAt.y)) - heightAt(float2(slopeLowAt.x, highAt.y))));
    float down = slopePair.y * (
        lowPair.x * (heightAt(float2(lowAt.x, slopeHighAt.y)) - heightAt(float2(lowAt.x, slopeLowAt.y))) +
        highPair.x * (heightAt(float2(highAt.x, slopeHighAt.y)) - heightAt(float2(highAt.x, slopeLowAt.y))));

    float level = height * uTop;
    // Levels per pixel, from the same smoothed surface the height came from.
    float steep = max(length(float2(across, down)) * uTop / uCell, 0.00001);
    float below = floor(level / uInterval);
    float3 colour = tint(below) * cover(below, level, steep);
    colour = mix(colour, tint(below + 1.0), cover(below + 1.0, level, steep));
    float shore = clamp(uWidths.z + 0.5 - abs(level - uSea) / steep, 0.0, 1.0) * uCoast;
    colour = mix(colour, uPeak, shore);
    colour *= uLight * (1.0 + uBoost) * (1.0 + uFlicker * flicker);
    float heat = max(halo(below, level, steep), halo(below + 1.0, level, steep));
    colour += mix(uLand, uPeak, 0.6) * heat * uGlow * uLight;
    return half4(clamp(colour, 0.0, 1.0), 1.0);
}
"""
}
