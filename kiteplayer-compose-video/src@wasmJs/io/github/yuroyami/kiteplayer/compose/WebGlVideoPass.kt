@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.compose

import io.github.yuroyami.kiteplayer.VideoAdjustments
import io.github.yuroyami.kiteplayer.spi.SphericalLens
import kotlin.js.JsAny

/**
 * One [KiteVideo] node's GL objects in Compose's own WebGL2 context, and the pass that draws with
 * them into the node's render target (#125).
 *
 * The target is the size of the node in device pixels, so Compose draws it at one to one and this
 * pass does all the placing and scaling: the picture as one quad from [videoQuad], scaled by
 * bilinear filtering or by Catmull-Rom in the shader, with the picture controls and the flash
 * guard's dimming as one colour matrix on the picture alone, and then each subtitle image blended
 * over it, premultiplied, as the overlay law asks. The letterbox is cleared to transparent.
 *
 * A 360 degree picture whose view is projected is drawn instead as the view into it over the whole
 * target (#460): each pixel reads the picture where [SphericalLens] says, sampled bilinearly, with
 * the same colour matrix, and where a tile has no picture the target stays transparent.
 *
 * The picture arrives as one browser array of RGBA that the codec module filled, and goes to the
 * GPU in one `texSubImage2D`, so no pixel of it is ever in Kotlin memory. Every GL call is in the
 * JavaScript below, a handful of crossings per frame, so nothing here holds a GL object in Kotlin
 * beyond the one state handle.
 *
 * GL state is the hard part of sharing a context. Skia leaves its own state bound, so the pass
 * binds its own vertex array, unbinds any sampler object and pixel-unpack buffer, and sets every
 * switch it depends on before it draws. Compose's render target then tells Skia that all of it is
 * stale, so nothing set here leaks into Compose's drawing.
 *
 * Belongs to the thread Compose draws on, which on the web is the only thread.
 */
internal class WebGlVideoPass(context: JsAny) {

    private val state: JsAny? = glvCreate(context)

    /** Why the GL objects could not be made, or null when they were. */
    val failure: String? = if (state == null) "this context is not WebGL2" else glvFailure(state)

    /** The generation of the picture the texture holds, so an unchanged picture is not uploaded again. */
    private var uploadedPicture = -1L
    private var uploadedOverlay = -1L
    private var released = false

    /**
     * Draws [picture] and [overlay] into the render target, bound by the caller, of [width] by
     * [height] device pixels. Must run inside `WebGLRenderTarget.render`.
     *
     * @return the milliseconds the picture's upload took, NaN when nothing was uploaded.
     */
    fun draw(
        width: Int,
        height: Int,
        picture: WebStagedPicture?,
        overlay: WebOverlay?,
        look: WebLook,
    ): Double {
        val s = state ?: return Double.NaN
        if (released || failure != null || width <= 0 || height <= 0) return Double.NaN
        var uploadMillis = Double.NaN
        if (picture != null && picture.generation != uploadedPicture) {
            uploadMillis = glvUploadPicture(s, picture.pixels, picture.size.width, picture.size.height)
            if (uploadMillis < 0) return Double.NaN
            uploadedPicture = picture.generation
        }
        val overlayGeneration = overlay?.generation ?: 0L
        if (overlayGeneration != uploadedOverlay) {
            val items = overlay?.items.orEmpty()
            glvBeginOverlay(s, items.size)
            items.forEachIndexed { index, item ->
                glvOverlayImage(s, index, item.pixels, item.width, item.height, item.x, item.y)
            }
            uploadedOverlay = overlayGeneration
        }

        val sphere = look.sphere
        if (picture != null && sphere != null) {
            putSphere(s, SphericalLens(sphere.projection, sphere.view, width.toFloat(), height.toFloat()), picture)
            putColour(s, look, picture)
            glvDraw(s, width, height, SPHERE, look.cubic, overlayScaleX(overlay, width), overlayScaleY(overlay, height))
            return uploadMillis
        }
        val layout = picture?.let {
            videoLayout(
                areaWidth = width,
                areaHeight = height,
                size = it.size.cropped(it.crop),
                rotationDegrees = it.rotationDegrees,
                mode = look.scaleMode,
                transform = look.transform,
                mirrored = it.mirrored,
            )
        }
        if (picture != null && layout != null) {
            val quad = videoQuad(layout, picture.size.width, picture.size.height, picture.crop)
            for (corner in 0 until 4) {
                // Device pixels, y down, to clip space, y up. The target's texture is adopted with
                // its origin at the bottom left, so clip space's top is the top of what Compose draws.
                glvPut4(
                    s,
                    corner * 4,
                    quad.x[corner] / width * 2f - 1f,
                    1f - quad.y[corner] / height * 2f,
                    quad.u[corner],
                    quad.v[corner],
                )
            }
            // The shown part's outermost texel centres: no sample reaches into what the crop removed.
            val imageWidth = picture.size.width.toFloat()
            val imageHeight = picture.size.height.toFloat()
            val crop = picture.crop
            glvPut4(
                s,
                CLAMP_AT,
                ((crop?.left ?: 0) + 0.5f) / imageWidth,
                ((crop?.top ?: 0) + 0.5f) / imageHeight,
                (picture.size.width - (crop?.right ?: 0) - 0.5f) / imageWidth,
                (picture.size.height - (crop?.bottom ?: 0) - 0.5f) / imageHeight,
            )
            putColour(s, look, picture)
        }
        val mode = if (picture != null && layout != null) FLAT else NONE
        glvDraw(s, width, height, mode, look.cubic, overlayScaleX(overlay, width), overlayScaleY(overlay, height))
        return uploadMillis
    }

    /** The picture controls with the flash guard's dimming, as the colour rows and offsets. */
    private fun putColour(s: JsAny, look: WebLook, picture: WebStagedPicture) {
        val matrix = glColorMatrix(look.adjustments, picture.dim)
        for (row in 0 until 4) {
            glvPut4(s, ROWS_AT + row * 4, matrix[row * 5], matrix[row * 5 + 1], matrix[row * 5 + 2], matrix[row * 5 + 3])
        }
        glvPut4(s, OFFSET_AT, matrix[4], matrix[9], matrix[14], matrix[19])
    }

    /**
     * The view's law, as [SphericalLens] gives it once for the draw: the centre, the scale and
     * whether the picture wraps, the turn's three rows, the picture's bounds on the sphere, and the
     * part of the texture the picture is, in texels.
     */
    private fun putSphere(s: JsAny, lens: SphericalLens, picture: WebStagedPicture) {
        val m = lens.rotation
        glvPut4(s, LENS_AT, lens.width / 2f, lens.height / 2f, lens.scale.toFloat(), if (lens.wraps) 1f else 0f)
        for (row in 0 until 3) {
            glvPut4(s, TURN_AT + row * 4, m[row * 3].toFloat(), m[row * 3 + 1].toFloat(), m[row * 3 + 2].toFloat(), 0f)
        }
        glvPut4(
            s,
            BOUNDS_AT,
            lens.pictureLeft.toFloat(),
            lens.pictureTop.toFloat(),
            lens.pictureWidth.toFloat(),
            lens.pictureHeight.toFloat(),
        )
        val crop = picture.crop
        val left = crop?.left ?: 0
        val top = crop?.top ?: 0
        glvPut4(
            s,
            SOURCE_AT,
            left.toFloat(),
            top.toFloat(),
            (picture.size.width - left - (crop?.right ?: 0)).toFloat(),
            (picture.size.height - top - (crop?.bottom ?: 0)).toFloat(),
        )
        // Repeated across only where the picture's own edges meet, so a sample at the seam blends them.
        glvSetJoined(s, lens.wraps && (crop == null || crop.isEmpty))
    }

    private fun overlayScaleX(overlay: WebOverlay?, width: Int): Float =
        if (overlay != null && overlay.viewportWidth > 0) width.toFloat() / overlay.viewportWidth else 1f

    private fun overlayScaleY(overlay: WebOverlay?, height: Int): Float =
        if (overlay != null && overlay.viewportHeight > 0) height.toFloat() / overlay.viewportHeight else 1f

    /**
     * Deletes the GL objects. Called when the node leaves composition, outside a render, so the
     * caller tells Compose's renderer afterwards that the GL state it knew is stale.
     */
    fun release() {
        if (released) return
        released = true
        state?.let(::glvRelease)
    }

    private companion object {
        const val CLAMP_AT = 16
        const val ROWS_AT = 20
        const val OFFSET_AT = 36
        const val LENS_AT = 40
        const val TURN_AT = 44
        const val BOUNDS_AT = 56
        const val SOURCE_AT = 60

        // What glvDraw draws under the overlay: nothing, the flat picture, or the view into it.
        const val NONE = 0
        const val FLAT = 1
        const val SPHERE = 2
    }
}

/** What the pass draws the picture with, read from the renderer once per draw. */
internal class WebLook(
    val scaleMode: io.github.yuroyami.kiteplayer.VideoScale,
    val transform: io.github.yuroyami.kiteplayer.VideoTransform,
    val adjustments: VideoAdjustments,
    /** 1 for Catmull-Rom, 0 for bilinear. */
    val cubic: Int,
    /** The 360 degree picture to draw a view into, or null for the flat picture (#460). */
    val sphere: SphereView? = null,
)

/**
 * The picture controls with the flash guard's [dim] folded in, as the shader applies them: four rows
 * of five, the offsets in the unit domain. [dimmedColorMatrix] is the same matrix in Compose's
 * convention, whose offsets are in the 0 to 255 domain, so this only undoes that scale.
 */
internal fun glColorMatrix(adjustments: VideoAdjustments, dim: Float): FloatArray {
    val values = dimmedColorMatrix(adjustments, dim)
    for (row in 0 until 4) values[row * 5 + 4] /= 255f
    return values
}

/*
 * The JavaScript half. Shaders are GLSL ES 3.00, which WebGL2 has and Compose's context is: the
 * vertex shader takes its corner from gl_VertexID, so there are no vertex buffers to bind, and the
 * empty vertex array keeps Skia's own attribute state untouched.
 */

@JsFun(
    """(gl) => {
      if (!gl || typeof gl.bindVertexArray !== 'function' || typeof gl.bindSampler !== 'function') return null;
      const s = { gl: gl, failure: null, u: new Float32Array(64), overlays: [], pw: 0, ph: 0 };
      const vertex = '#version 300 es\n' +
        'uniform vec4 uCorner[4];\n' +
        'out vec2 vUv;\n' +
        'void main() { vec4 c = uCorner[gl_VertexID]; vUv = c.zw; gl_Position = vec4(c.xy, 0.0, 1.0); }\n';
      const picture = '#version 300 es\n' +
        'precision highp float;\n' +
        'uniform sampler2D uImage;\n' +
        'uniform vec2 uSize;\n' +
        'uniform vec4 uClamp;\n' +
        'uniform int uCubic;\n' +
        'uniform vec4 uRow[4];\n' +
        'uniform vec4 uOffset;\n' +
        'in vec2 vUv;\n' +
        'out vec4 outColor;\n' +
        'vec4 tap(vec2 texel) { return texture(uImage, clamp(texel / uSize, uClamp.xy, uClamp.zw)); }\n' +
        'vec4 weights(float t) {\n' +
        '  float t2 = t * t; float t3 = t2 * t;\n' +
        '  return vec4(-0.5 * t3 + t2 - 0.5 * t, 1.5 * t3 - 2.5 * t2 + 1.0, -1.5 * t3 + 2.0 * t2 + 0.5 * t, 0.5 * t3 - 0.5 * t2);\n' +
        '}\n' +
        'vec4 catmullRom(vec2 uv) {\n' +
        '  vec2 p = uv * uSize - 0.5; vec2 f = fract(p); vec2 base = floor(p) + 0.5;\n' +
        '  vec4 wx = weights(f.x); vec4 wy = weights(f.y); vec4 sum = vec4(0.0);\n' +
        '  for (int j = 0; j < 4; j++) {\n' +
        '    float y = base.y + float(j - 1);\n' +
        '    vec4 row = tap(vec2(base.x - 1.0, y)) * wx.x + tap(vec2(base.x, y)) * wx.y +\n' +
        '      tap(vec2(base.x + 1.0, y)) * wx.z + tap(vec2(base.x + 2.0, y)) * wx.w;\n' +
        '    sum += row * wy[j];\n' +
        '  }\n' +
        '  return sum;\n' +
        '}\n' +
        'void main() {\n' +
        '  vec4 c = uCubic == 1 ? catmullRom(vUv) : texture(uImage, clamp(vUv, uClamp.xy, uClamp.zw));\n' +
        '  c = clamp(c, 0.0, 1.0);\n' +
        '  vec4 o = clamp(vec4(dot(uRow[0], c), dot(uRow[1], c), dot(uRow[2], c), dot(uRow[3], c)) + uOffset, 0.0, 1.0);\n' +
        '  outColor = vec4(o.rgb * o.a, o.a);\n' +
        '}\n';
      // The view into a 360 degree picture (#460): SphericalLens for each pixel, from the pixel's
      // centre counted down from the top of the target, with the turn handed in as three rows.
      const sphere = '#version 300 es\n' +
        'precision highp float;\n' +
        'uniform sampler2D uImage;\n' +
        'uniform vec2 uSize;\n' +
        'uniform vec2 uTarget;\n' +
        'uniform vec4 uLens;\n' +
        'uniform vec4 uTurn[3];\n' +
        'uniform vec4 uBounds;\n' +
        'uniform vec4 uSource;\n' +
        'uniform vec4 uRow[4];\n' +
        'uniform vec4 uOffset;\n' +
        'out vec4 outColor;\n' +
        'void main() {\n' +
        '  vec2 coord = vec2(gl_FragCoord.x, uTarget.y - gl_FragCoord.y);\n' +
        '  vec3 ray = vec3(uLens.z * (coord.x - uLens.x), uLens.z * (uLens.y - coord.y), -1.0);\n' +
        '  vec3 d = vec3(dot(uTurn[0].xyz, ray), dot(uTurn[1].xyz, ray), dot(uTurn[2].xyz, ray));\n' +
        '  float across = 0.5 + atan(d.x, -d.z) * 0.15915494309;\n' +
        '  float down = 0.5 - atan(d.y, length(d.xz)) * 0.31830988618;\n' +
        '  if (uLens.w > 0.5) { across = fract(across); }\n' +
        '  float u = (across - uBounds.x) / uBounds.z;\n' +
        '  float v = (down - uBounds.y) / uBounds.w;\n' +
        '  if (u < 0.0 || u > 1.0 || v < 0.0 || v > 1.0) { outColor = vec4(0.0); return; }\n' +
        '  vec4 c = clamp(texture(uImage, vec2(uSource.x + u * uSource.z, uSource.y + v * uSource.w) / uSize), 0.0, 1.0);\n' +
        '  vec4 o = clamp(vec4(dot(uRow[0], c), dot(uRow[1], c), dot(uRow[2], c), dot(uRow[3], c)) + uOffset, 0.0, 1.0);\n' +
        '  outColor = vec4(o.rgb * o.a, o.a);\n' +
        '}\n';
      const overlay = '#version 300 es\n' +
        'precision mediump float;\n' +
        'uniform sampler2D uImage;\n' +
        'in vec2 vUv;\n' +
        'out vec4 outColor;\n' +
        'void main() { outColor = texture(uImage, vUv); }\n';
      const compile = (type, source) => {
        const shader = gl.createShader(type);
        gl.shaderSource(shader, source);
        gl.compileShader(shader);
        if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) { s.failure = 'a KiteVideo shader did not compile: ' + gl.getShaderInfoLog(shader); }
        return shader;
      };
      const link = (fragmentSource) => {
        const v = compile(gl.VERTEX_SHADER, vertex);
        const f = compile(gl.FRAGMENT_SHADER, fragmentSource);
        const p = gl.createProgram();
        gl.attachShader(p, v); gl.attachShader(p, f); gl.linkProgram(p);
        gl.deleteShader(v); gl.deleteShader(f);
        if (!s.failure && !gl.getProgramParameter(p, gl.LINK_STATUS)) { s.failure = 'a KiteVideo program did not link: ' + gl.getProgramInfoLog(p); }
        return p;
      };
      s.pp = link(picture);
      s.op = link(overlay);
      s.sp = link(sphere);
      s.spu = {
        corner: gl.getUniformLocation(s.sp, 'uCorner'), image: gl.getUniformLocation(s.sp, 'uImage'),
        size: gl.getUniformLocation(s.sp, 'uSize'), target: gl.getUniformLocation(s.sp, 'uTarget'),
        lens: gl.getUniformLocation(s.sp, 'uLens'), turn: gl.getUniformLocation(s.sp, 'uTurn'),
        bounds: gl.getUniformLocation(s.sp, 'uBounds'), source: gl.getUniformLocation(s.sp, 'uSource'),
        row: gl.getUniformLocation(s.sp, 'uRow'), offset: gl.getUniformLocation(s.sp, 'uOffset'),
      };
      s.joined = false;
      s.ppu = {
        corner: gl.getUniformLocation(s.pp, 'uCorner'), image: gl.getUniformLocation(s.pp, 'uImage'),
        size: gl.getUniformLocation(s.pp, 'uSize'), clamp: gl.getUniformLocation(s.pp, 'uClamp'),
        cubic: gl.getUniformLocation(s.pp, 'uCubic'), row: gl.getUniformLocation(s.pp, 'uRow'),
        offset: gl.getUniformLocation(s.pp, 'uOffset'),
      };
      s.opu = { corner: gl.getUniformLocation(s.op, 'uCorner'), image: gl.getUniformLocation(s.op, 'uImage') };
      s.vao = gl.createVertexArray();
      s.texture = () => {
        const t = gl.createTexture();
        gl.bindTexture(gl.TEXTURE_2D, t);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        return t;
      };
      s.unpack = () => {
        gl.bindBuffer(gl.PIXEL_UNPACK_BUFFER, null);
        gl.pixelStorei(gl.UNPACK_ALIGNMENT, 4);
        gl.pixelStorei(gl.UNPACK_ROW_LENGTH, 0);
        gl.pixelStorei(gl.UNPACK_SKIP_ROWS, 0);
        gl.pixelStorei(gl.UNPACK_SKIP_PIXELS, 0);
        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
        gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
        gl.activeTexture(gl.TEXTURE0);
      };
      s.unpack();
      s.picture = s.texture();
      gl.bindTexture(gl.TEXTURE_2D, null);
      return s;
    }""",
)
private external fun glvCreate(context: JsAny): JsAny?

@JsFun("(s) => s.failure")
private external fun glvFailure(state: JsAny): String?

@JsFun("(s, at, a, b, c, d) => { const u = s.u; u[at] = a; u[at + 1] = b; u[at + 2] = c; u[at + 3] = d; }")
private external fun glvPut4(state: JsAny, at: Int, a: Float, b: Float, c: Float, d: Float)

/**
 * Uploads the staged picture. The texture is reallocated only when the picture's size changes;
 * every other frame is one `texSubImage2D` over it. Answers the call's milliseconds on the page's
 * clock, or -1 when the browser refused the size, which only an allocation can do.
 */
@JsFun(
    """(s, pixels, w, h) => {
      const gl = s.gl;
      s.unpack();
      gl.bindTexture(gl.TEXTURE_2D, s.picture);
      const started = performance.now();
      if (s.pw !== w || s.ph !== h) {
        while (gl.getError() !== gl.NO_ERROR) {}
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
        if (gl.getError() !== gl.NO_ERROR) { s.pw = 0; s.ph = 0; return -1; }
        s.pw = w; s.ph = h;
      } else {
        gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, w, h, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
      }
      return performance.now() - started;
    }""",
)
private external fun glvUploadPicture(state: JsAny, pixels: JsAny, width: Int, height: Int): Double

/** Keeps the first [count] overlay textures for reuse and deletes the rest. */
@JsFun(
    """(s, count) => {
      const gl = s.gl;
      while (s.overlays.length > count) gl.deleteTexture(s.overlays.pop().t);
    }""",
)
private external fun glvBeginOverlay(state: JsAny, count: Int)

/** One subtitle image, premultiplied RGBA, into the overlay texture at [index]. */
@JsFun(
    """(s, index, pixels, w, h, x, y) => {
      const gl = s.gl;
      s.unpack();
      let o = s.overlays[index];
      if (!o) { o = { t: s.texture() }; s.overlays[index] = o; } else { gl.bindTexture(gl.TEXTURE_2D, o.t); }
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
      o.w = w; o.h = h; o.x = x; o.y = y;
    }""",
)
private external fun glvOverlayImage(state: JsAny, index: Int, pixels: JsAny, width: Int, height: Int, x: Int, y: Int)

/**
 * The pass: clear to transparent, then the picture as [mode] says, from what is already put: the
 * flat picture from the corners, clamp, colour rows and offsets, or the view into it over the whole
 * target from the lens, the bounds and the same colour rows. Then every overlay image over it at its
 * own place scaled from the overlay's viewport, rounded as the threaded draw rounds it.
 */
@JsFun(
    """(s, w, h, mode, cubic, sx, sy) => {
      const gl = s.gl, u = s.u;
      gl.viewport(0, 0, w, h);
      gl.disable(gl.SCISSOR_TEST); gl.disable(gl.DEPTH_TEST); gl.disable(gl.STENCIL_TEST);
      gl.disable(gl.CULL_FACE); gl.disable(gl.BLEND); gl.disable(gl.POLYGON_OFFSET_FILL);
      gl.disable(gl.SAMPLE_ALPHA_TO_COVERAGE); gl.disable(gl.SAMPLE_COVERAGE); gl.disable(gl.RASTERIZER_DISCARD);
      gl.colorMask(true, true, true, true);
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.bindVertexArray(s.vao);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindSampler(0, null);
      if (mode === 2 && s.pw > 0) {
        gl.useProgram(s.sp);
        gl.uniform4fv(s.spu.corner, new Float32Array([-1, 1, 0, 0, 1, 1, 1, 0, -1, -1, 0, 1, 1, -1, 1, 1]));
        gl.uniform2f(s.spu.size, s.pw, s.ph);
        gl.uniform2f(s.spu.target, w, h);
        gl.uniform4fv(s.spu.lens, u.subarray(40, 44));
        gl.uniform4fv(s.spu.turn, u.subarray(44, 56));
        gl.uniform4fv(s.spu.bounds, u.subarray(56, 60));
        gl.uniform4fv(s.spu.source, u.subarray(60, 64));
        gl.uniform4fv(s.spu.row, u.subarray(20, 36));
        gl.uniform4fv(s.spu.offset, u.subarray(36, 40));
        gl.uniform1i(s.spu.image, 0);
        gl.bindTexture(gl.TEXTURE_2D, s.picture);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, s.joined ? gl.REPEAT : gl.CLAMP_TO_EDGE);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      }
      if (mode === 1 && s.pw > 0) {
        gl.useProgram(s.pp);
        gl.uniform4fv(s.ppu.corner, u.subarray(0, 16));
        gl.uniform4fv(s.ppu.clamp, u.subarray(16, 20));
        gl.uniform4fv(s.ppu.row, u.subarray(20, 36));
        gl.uniform4fv(s.ppu.offset, u.subarray(36, 40));
        gl.uniform2f(s.ppu.size, s.pw, s.ph);
        gl.uniform1i(s.ppu.cubic, cubic);
        gl.uniform1i(s.ppu.image, 0);
        gl.bindTexture(gl.TEXTURE_2D, s.picture);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      }
      if (s.overlays.length > 0) {
        gl.enable(gl.BLEND);
        gl.blendEquation(gl.FUNC_ADD);
        gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
        gl.useProgram(s.op);
        gl.uniform1i(s.opu.image, 0);
        const corners = new Float32Array(16);
        for (const o of s.overlays) {
          const left = Math.round(o.x * sx), top = Math.round(o.y * sy);
          const right = left + Math.max(1, Math.round(o.w * sx)), bottom = top + Math.max(1, Math.round(o.h * sy));
          const x0 = left / w * 2 - 1, x1 = right / w * 2 - 1, y0 = 1 - top / h * 2, y1 = 1 - bottom / h * 2;
          corners.set([x0, y0, 0, 0, x1, y0, 1, 0, x0, y1, 0, 1, x1, y1, 1, 1]);
          gl.uniform4fv(s.opu.corner, corners);
          gl.bindTexture(gl.TEXTURE_2D, o.t);
          gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
        }
        gl.disable(gl.BLEND);
      }
      gl.bindTexture(gl.TEXTURE_2D, null);
      gl.bindVertexArray(null);
      gl.useProgram(null);
    }""",
)
private external fun glvDraw(state: JsAny, width: Int, height: Int, mode: Int, cubic: Int, overlayScaleX: Float, overlayScaleY: Float)

/** Whether the next view samples the picture repeated across, where its own left and right edges meet (#460). */
@JsFun("(s, joined) => { s.joined = joined; }")
private external fun glvSetJoined(state: JsAny, joined: Boolean)

@JsFun(
    """(s) => {
      const gl = s.gl;
      for (const o of s.overlays) gl.deleteTexture(o.t);
      s.overlays = [];
      if (s.picture) gl.deleteTexture(s.picture);
      if (s.vao) gl.deleteVertexArray(s.vao);
      if (s.pp) gl.deleteProgram(s.pp);
      if (s.op) gl.deleteProgram(s.op);
      if (s.sp) gl.deleteProgram(s.sp);
      s.picture = null; s.vao = null; s.pp = null; s.op = null; s.sp = null; s.pw = 0; s.ph = 0;
    }""",
)
private external fun glvRelease(state: JsAny)
