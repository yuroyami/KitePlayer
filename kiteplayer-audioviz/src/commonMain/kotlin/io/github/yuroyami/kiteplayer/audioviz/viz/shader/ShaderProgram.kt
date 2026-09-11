package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi

/**
 * A small program that runs once for every pixel, compiled from source at runtime.
 *
 * This is the one thing that changes what the library can draw rather than how well. Everything
 * else here builds a picture out of lines and circles, which means the cost grows with how much
 * detail is wanted and a full screen of swirling colour costs a full screen of shapes. A shader
 * is handed a pixel and asked what colour it should be, and the graphics card asks a few million
 * times at once. Plasma, flowing noise, fractals and raymarched tunnels all become one draw call.
 *
 * The language is SkSL, which is close enough to GLSL to read. Write a `main` that takes a
 * position and returns a colour:
 *
 * ```
 * half4 main(float2 position) {
 *     float2 uv = position / uResolution;
 *     return half4(uv.x, uv.y, 0.5, 1.0);
 * }
 * ```
 *
 * Set the values it reads with [uniform] and [child], then hand [brush] to any Compose draw call.
 * Values persist between frames, so only what changed has to be set again.
 *
 * Not every device can run one. Android gained the ability in version 33, and anything older
 * reports [available] as false, so a drawing that needs a shader has to say what it does instead.
 */
@AudioVizAuthoringApi
public expect class ShaderProgram(source: String) {

    /** False when this device cannot run shaders at all, or when [source] did not compile. */
    public val available: Boolean

    /** What the compiler said, with line numbers, or null when it compiled. */
    public val error: String?

    public fun uniform(name: String, value: Float)

    public fun uniform(name: String, x: Float, y: Float)

    public fun uniform(name: String, x: Float, y: Float, z: Float)

    public fun uniform(name: String, x: Float, y: Float, z: Float, w: Float)

    /** Sets an array of values, for anything declared as `uniform float3 name[8]` or similar. */
    public fun uniforms(name: String, values: FloatArray)

    /**
     * Hands the shader a picture to read, declared as `uniform shader name`.
     *
     * Read it with `name.eval(coordinate)`, where the coordinate is in the picture's own pixels.
     * [tiled] repeats the picture past its edges instead of holding the edge pixels.
     */
    public fun child(name: String, image: ImageBitmap, tiled: Boolean = false)

    /** Something to paint with, or null when this device cannot run the program. */
    public fun brush(): Brush?
}

/** Whether this device can run shaders at all. False on Android before version 13. */
internal expect val runtimeShadersSupported: Boolean
