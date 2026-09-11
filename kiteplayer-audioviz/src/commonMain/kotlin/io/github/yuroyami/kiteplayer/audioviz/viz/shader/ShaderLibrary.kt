package io.github.yuroyami.kiteplayer.audioviz.viz.shader

/**
 * The block of shader source every drawing here starts with.
 *
 * Two jobs. It declares the values the library hands to every program, so a shader can read the
 * bass or the beat position without arranging anything. And it carries the handful of functions
 * that almost every one of them needs: noise, a palette lookup, a couple of distance functions
 * and a tone map. Without it each drawing would open with sixty lines of the same maths.
 *
 * The language is SkSL. It reads like GLSL with two differences worth knowing: the entry point is
 * `half4 main(float2 position)` where the position is in pixels, and loops need bounds the
 * compiler can work out in advance.
 */
internal object ShaderLibrary {

    /** How many spectrum bars a shader sees, whatever the analyser was set to. */
    const val BANDS = 64

    /** How many waveform points a shader sees. */
    const val SCOPE = 128

    /** How many steps the colour ramp is handed over as. */
    const val PALETTE = 64

    /** How many past spectra the history picture keeps. About four seconds at sixty frames a second. */
    const val HISTORY = 256

    val HEADER: String = """
uniform float2 uResolution;
uniform float uTime;
uniform float uMusicTime;
uniform float uDelta;

uniform float uLevel;
uniform float uBass;
uniform float uMid;
uniform float uTreble;
uniform float uEnergy;
uniform float uMood;
uniform float uDrive;
uniform float uDensity;

uniform float uBeat;
uniform float uPulse;
uniform float uKick;
uniform float uSnare;
uniform float uHat;

uniform float uBpm;
uniform float uBeatPhase;
uniform float uBarPhase;
uniform float uPhrasePhase;
uniform float uBeatIn;

uniform float uCentroid;
uniform float uFlatness;
uniform float uWidth;
uniform float uKeyHue;
uniform float uSeed;

// The drawing's camera: pan across and down as shares of the screen, zoom, and roll in radians.
uniform float4 uCam;
// A colour offset that walks one turn every eight phrases.
uniform float uWalk;

// The readings arrive as small pictures rather than as lists of numbers, because this language
// will not let a program pick an entry of a list by anything it works out while running. See
// ShaderData for why that matters and what it costs.
uniform shader uBandsTex;
uniform shader uScopeTex;
uniform shader uPaletteTex;
uniform shader uHistoryTex;
uniform float uHistoryRow;

// Screen position mapped so that 0,0 is the middle and one unit is half the screen height.
// Working in these keeps a drawing the same shape on any window.
float2 centred(float2 position) {
    return (position - uResolution * 0.5) / (uResolution.y * 0.5);
}

// The same with y pointing up the screen, for anything seen through a camera. Skia counts rows
// downward, so a scene built on the plain version comes out upside down.
float2 view(float2 position) {
    float2 uv = centred(position);
    return float2(uv.x, -uv.y);
}

// The pixel moved back through the camera, so a scene slides, zooms and turns with the drawing.
float2 camPoint(float2 position) {
    float2 middle = uResolution * 0.5;
    float2 p = position - middle - uCam.xy * uResolution;
    float s = sin(-uCam.w);
    float c = cos(-uCam.w);
    p = float2(p.x * c - p.y * s, p.x * s + p.y * c) / max(uCam.z, 0.25);
    return p + middle;
}

float2 rotate(float2 point, float angle) {
    float s = sin(angle);
    float c = cos(angle);
    return float2(point.x * c - point.y * s, point.x * s + point.y * c);
}

// A repeatable pseudo random number from a position. Not a good generator, but it costs three
// operations and nothing here is deciding anything important.
float hash21(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

// Value noise: random numbers at whole positions, smoothly blended between.
float noise2(float2 p) {
    float2 cell = floor(p);
    float2 into = fract(p);
    into = into * into * (3.0 - 2.0 * into);
    float a = hash21(cell);
    float b = hash21(cell + float2(1.0, 0.0));
    float c = hash21(cell + float2(0.0, 1.0));
    float d = hash21(cell + float2(1.0, 1.0));
    return mix(mix(a, b, into.x), mix(c, d, into.x), into.y);
}

// Several octaves of noise added together, each half the size and half the strength of the last.
// This is what gives clouds and smoke their detail at every scale.
float fbm(float2 p) {
    float total = 0.0;
    float weight = 0.5;
    for (int step = 0; step < 5; step++) {
        total += noise2(p) * weight;
        p = p * 2.02;
        weight *= 0.5;
    }
    return total;
}

// Reads the spectrum at a position from 0 to 1. The half is because a pixel's colour sits at its
// middle, and the hardware blends between neighbours on the way out, so this interpolates for free.
float band(float where) {
    return uBandsTex.eval(float2(0.5 + clamp(where, 0.0, 1.0) * float($BANDS - 1), 0.5)).r;
}

// The same, folded in half, so a shape drawn round a circle has no seam where the ends meet.
float bandFolded(float where) {
    float wrapped = fract(clamp(where, 0.0, 1.0));
    return band(wrapped <= 0.5 ? wrapped * 2.0 : (1.0 - wrapped) * 2.0);
}

// The waveform, back in the range it started in: zero sits at half brightness in the strip.
float scopeAt(float where) {
    float stored = uScopeTex.eval(float2(0.5 + clamp(where, 0.0, 1.0) * float($SCOPE - 1), 0.5)).r;
    return stored * 2.0 - 1.0;
}

// The spectrum as it was a moment ago, which laid out on screen is a spectrogram. An age of 0 is
// now and 1 is about four seconds back. Rows are written round and round, and uHistoryRow says
// which one is next, so nothing is ever shifted.
float history(float where, float age) {
    float row = uHistoryRow - 1.0 - clamp(age, 0.0, 1.0) * float($HISTORY - 2);
    float lower = floor(row);
    float x = 0.5 + clamp(where, 0.0, 1.0) * float($BANDS - 1);
    // Two rows blended by hand, because where the rows wrap round the picture's own blending would
    // clamp at its edge and leave a seam.
    float older = uHistoryTex.eval(float2(x, mod(lower, float($HISTORY)) + 0.5)).r;
    float newer = uHistoryTex.eval(float2(x, mod(lower + 1.0, float($HISTORY)) + 0.5)).r;
    return mix(older, newer, row - lower);
}

// The palette read as a ramp from 0 to 1.
float3 palette(float where) {
    return uPaletteTex.eval(float2(0.5 + clamp(where, 0.0, 1.0) * float($PALETTE - 1), 0.5)).rgb;
}

// The palette treated as a loop, so a rising number cycles through it forever.
float3 paletteCycled(float where) {
    return palette(fract(where));
}

// The palette read there and back, so a rising number cycles through it with no seam.
float3 paletteLoop(float where) {
    return palette(abs(fract(where) * 2.0 - 1.0));
}

// Squashes bright values into the visible range instead of clipping them to white. Anything that
// adds light needs this, or every overlap turns into a flat white patch.
float3 toneMap(float3 colour) {
    float3 x = max(float3(0.0), colour);
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

// Distance from a point to the surface of a sphere, which is all a raymarcher needs to draw one.
float sdSphere(float3 point, float radius) {
    return length(point) - radius;
}

float sdBox(float3 point, float3 bounds) {
    float3 outside = abs(point) - bounds;
    return length(max(outside, float3(0.0))) + min(max(outside.x, max(outside.y, outside.z)), 0.0);
}

float sdTorus(float3 point, float ring, float tube) {
    float2 onRing = float2(length(point.xz) - ring, point.y);
    return length(onRing) - tube;
}

// Joins two shapes with a soft weld rather than a crease, which is what makes blobs merge.
float smoothMin(float a, float b, float amount) {
    float blend = clamp(0.5 + 0.5 * (b - a) / amount, 0.0, 1.0);
    return mix(b, a, blend) - amount * blend * (1.0 - blend);
}

// Fades a colour into the background with distance.
float3 fogged(float3 colour, float3 ground, float distance, float density) {
    return mix(colour, ground, clamp(1.0 - exp(-distance * density), 0.0, 1.0));
}
"""
}
