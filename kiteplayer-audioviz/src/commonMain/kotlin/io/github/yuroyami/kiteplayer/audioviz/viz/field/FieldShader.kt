package io.github.yuroyami.kiteplayer.audioviz.viz.field

/**
 * The shader side of [MemoryField]: the grid read smoothly, its slope, and the three ways a
 * drawing makes a small grid look sharp at the screen's own resolution. Contour's line pass is
 * the model: a cubic B-spline from four blended reads, and a slope that turns the gap between a
 * value and a level into pixels, so a line keeps its width however steep the ink is.
 */
internal object FieldShader {

    const val SOURCE: String = """
uniform shader uField;
uniform float2 uFieldSize;

// The grid cell this pixel falls in, with the centre of texel i at i + 0.5.
float2 fieldCell(float2 position) {
    return position / uResolution * uFieldSize;
}

// Ink (0 to 1) in x and age in seconds in y, read with a cubic B-spline over four blended reads.
float2 fieldAt(float2 position) {
    float2 c = fieldCell(position) - 0.5;
    float2 i = floor(c);
    float2 f = c - i;
    float2 f2 = f * f;
    float2 f3 = f2 * f;
    float2 r = 1.0 - f;
    float2 w0 = r * r * r / 6.0;
    float2 w1 = (3.0 * f3 - 6.0 * f2 + 4.0) / 6.0;
    float2 w3 = f3 / 6.0;
    float2 lowPair = w0 + w1;
    float2 highPair = 1.0 - lowPair;
    float2 lowAt = i - 0.5 + w1 / lowPair;
    float2 highAt = i + 1.5 + w3 / highPair;
    half4 t00 = uField.eval(float2(lowAt.x, lowAt.y));
    half4 t10 = uField.eval(float2(highAt.x, lowAt.y));
    half4 t01 = uField.eval(float2(lowAt.x, highAt.y));
    half4 t11 = uField.eval(float2(highAt.x, highAt.y));
    float2 v = lowPair.y * (lowPair.x * float2(t00.rg) + highPair.x * float2(t10.rg)) +
        highPair.y * (lowPair.x * float2(t01.rg) + highPair.x * float2(t11.rg));
    return float2(v.x, v.y * 8.0);
}

// How much the ink changes per pixel across and down, from the same smooth surface.
float2 fieldSlope(float2 position) {
    float2 nudge = uResolution / uFieldSize * 0.5;
    float across = fieldAt(position + float2(nudge.x, 0.0)).x - fieldAt(position - float2(nudge.x, 0.0)).x;
    float down = fieldAt(position + float2(0.0, nudge.y)).x - fieldAt(position - float2(0.0, nudge.y)).x;
    return float2(across / (2.0 * nudge.x), down / (2.0 * nudge.y));
}

// How much of this pixel a line at [level] covers, halfWidth pixels wide, whatever the slope.
float isoLine(float value, float level, float halfWidth, float2 slope) {
    float steep = max(length(slope), 0.00001);
    return clamp(halfWidth + 0.5 - abs(value - level) / steep, 0.0, 1.0);
}

// The value held to [steps] flat bands, which is what a palette-cycled ink wants.
float bands(float value, float steps) {
    return floor(clamp(value, 0.0, 0.9999) * steps) / steps;
}

// Light from the upper left on a surface whose height is the ink, 0.5 flat, more on the lit side.
float lit(float2 slope) {
    float2 n = slope * 40.0;
    return clamp(0.5 + 0.5 * dot(normalize(float3(-n.x, -n.y, 1.0)), normalize(float3(-0.5, -0.6, 0.7))), 0.0, 1.0);
}

// The extra channel, read with the same cubic B-spline as the ink. 0 for a field without one.
float fieldExtra(float2 position) {
    float2 c = fieldCell(position) - 0.5;
    float2 i = floor(c);
    float2 f = c - i;
    float2 f2 = f * f;
    float2 f3 = f2 * f;
    float2 r = 1.0 - f;
    float2 w0 = r * r * r / 6.0;
    float2 w1 = (3.0 * f3 - 6.0 * f2 + 4.0) / 6.0;
    float2 w3 = f3 / 6.0;
    float2 lowPair = w0 + w1;
    float2 highPair = 1.0 - lowPair;
    float2 lowAt = i - 0.5 + w1 / lowPair;
    float2 highAt = i + 1.5 + w3 / highPair;
    float t00 = float(uField.eval(float2(lowAt.x, lowAt.y)).b);
    float t10 = float(uField.eval(float2(highAt.x, lowAt.y)).b);
    float t01 = float(uField.eval(float2(lowAt.x, highAt.y)).b);
    float t11 = float(uField.eval(float2(highAt.x, highAt.y)).b);
    return lowPair.y * (lowPair.x * t00 + highPair.x * t10) + highPair.y * (lowPair.x * t01 + highPair.x * t11);
}

// How much the extra channel changes per pixel across and down.
float2 fieldExtraSlope(float2 position) {
    float2 nudge = uResolution / uFieldSize * 0.5;
    float across = fieldExtra(position + float2(nudge.x, 0.0)) - fieldExtra(position - float2(nudge.x, 0.0));
    float down = fieldExtra(position + float2(0.0, nudge.y)) - fieldExtra(position - float2(0.0, nudge.y));
    return float2(across / (2.0 * nudge.x), down / (2.0 * nudge.y));
}
"""
}
