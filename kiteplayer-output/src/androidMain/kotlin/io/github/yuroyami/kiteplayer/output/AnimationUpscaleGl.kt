package io.github.yuroyami.kiteplayer.output

import android.opengl.GLES20
import android.opengl.GLES30

/**
 * One Anime4K network on a GLES2 context (#67): its convolutions at the source's size, then the
 * step that doubles the picture.
 *
 * The caller draws the picture into [sourceFramebuffer], [width] by [height], with the picture's
 * TOP row at texture row 0, which is the original's own picture space. [run] then leaves the
 * doubled picture in [outputTexture], top row first as well, for the blit to draw from.
 *
 * The convolutions write half floats, because they are signed and run past 1, exactly as mpv
 * stores them. A context that cannot render to half floats cannot run the networks, and [create]
 * says so. Every call happens on the thread that holds the context, and every texture is let go by
 * [releaseTextures] or [close].
 */
internal class AnimationUpscaleGl private constructor(
    val network: Anime4kNetwork,
    private val formats: UpscaleFormats,
    private val convolutions: List<Pass>,
    private val doubling: Pass,
) : AutoCloseable {

    /** One linked program and where its inputs go. */
    class Pass(val program: Int, samplerNames: List<String>, sizeName: String) {
        val position: Int = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoord: Int = GLES20.glGetAttribLocation(program, "aTexCoord")
        val samplers: IntArray = samplerNames.map { GLES20.glGetUniformLocation(program, it) }.toIntArray()
        val size: Int = GLES20.glGetUniformLocation(program, sizeName)

        init {
            // The size alone may be absent: a 1x1 convolution never steps a texel, so its compiler
            // drops the uniform, and setting an absent uniform does nothing.
            check(position >= 0 && texCoord >= 0 && samplers.all { it >= 0 }) {
                "an animation upscaler pass lost part of its interface"
            }
        }
    }

    /** Which texture each layer writes: a layer's texture is reused once its last reader has run. */
    private val slotOfLayer = IntArray(network.layers.size)
    private val slotCount: Int

    init {
        val free = ArrayDeque<Int>()
        var slots = 0
        for (layer in network.layers.indices) {
            slotOfLayer[layer] = free.removeFirstOrNull() ?: slots++
            for (earlier in 0 until layer) {
                if (network.lastReaders[earlier] == layer) free.addLast(slotOfLayer[earlier])
            }
        }
        slotCount = slots
    }

    var width: Int = 0
        private set
    var height: Int = 0
        private set

    /** Where the caller draws the picture, top row first, before [run]. */
    var sourceFramebuffer: Int = 0
        private set

    /** The doubled picture after [run], top row first, sampled with bilinear. */
    var outputTexture: Int = 0
        private set

    private var sourceTexture = 0
    private var outputFramebuffer = 0
    private val layerTextures = IntArray(slotCount)
    private val layerFramebuffers = IntArray(slotCount)

    /** Allocates the textures for a [width] by [height] source, or keeps them when they fit. */
    fun resize(width: Int, height: Int) {
        require(width > 0 && height > 0) { "an animation upscaler needs a picture, not ${width}x$height" }
        if (width == this.width && height == this.height && sourceTexture != 0) return
        releaseTextures()
        try {
            sourceTexture = formats.pictureTexture(width, height)
            sourceFramebuffer = framebufferFor(sourceTexture)
            for (slot in 0 until slotCount) {
                layerTextures[slot] = formats.layerTexture(width, height)
                layerFramebuffers[slot] = framebufferFor(layerTextures[slot])
            }
            outputTexture = formats.pictureTexture(width * 2, height * 2)
            outputFramebuffer = framebufferFor(outputTexture)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            upscaleGlCheck("allocate the animation upscaler's ${width}x$height textures")
            this.width = width
            this.height = height
        } catch (failure: Throwable) {
            releaseTextures()
            throw failure
        }
    }

    /** Runs every convolution and the doubling. Leaves framebuffer 0 and texture unit 0 bound. */
    fun run() {
        check(sourceTexture != 0) { "the animation upscaler has no textures" }
        network.layers.forEachIndexed { index, layer ->
            val pass = convolutions[index]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, layerFramebuffers[slotOfLayer[index]])
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(pass.program)
            layer.inputs.forEachIndexed { unit, input ->
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
                val texture = if (input == Anime4kTerm.SOURCE) sourceTexture else layerTextures[slotOfLayer[input]]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                GLES20.glUniform1i(pass.samplers[unit], unit)
            }
            GLES20.glUniform2f(pass.size, 1f / width, 1f / height)
            drawQuad(pass)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
        GLES20.glViewport(0, 0, width * 2, height * 2)
        GLES20.glUseProgram(doubling.program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture)
        GLES20.glUniform1i(doubling.samplers[0], 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layerTextures[slotOfLayer[network.layers.lastIndex]])
        GLES20.glUniform1i(doubling.samplers[1], 1)
        GLES20.glUniform2f(doubling.size, width.toFloat(), height.toFloat())
        drawQuad(doubling)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        upscaleGlCheck("run ${network.name}")
    }

    /** Lets the textures go and keeps the programs, for a picture that no longer needs the network. */
    fun releaseTextures() {
        val framebuffers = (layerFramebuffers + sourceFramebuffer + outputFramebuffer).filter { it != 0 }
        val textures = (layerTextures + sourceTexture + outputTexture).filter { it != 0 }
        if (framebuffers.isNotEmpty()) GLES20.glDeleteFramebuffers(framebuffers.size, framebuffers.toIntArray(), 0)
        if (textures.isNotEmpty()) GLES20.glDeleteTextures(textures.size, textures.toIntArray(), 0)
        layerFramebuffers.fill(0)
        layerTextures.fill(0)
        sourceFramebuffer = 0
        sourceTexture = 0
        outputFramebuffer = 0
        outputTexture = 0
        width = 0
        height = 0
    }

    override fun close() {
        releaseTextures()
        convolutions.forEach { GLES20.glDeleteProgram(it.program) }
        GLES20.glDeleteProgram(doubling.program)
    }

    private fun drawQuad(pass: Pass) {
        GlState.VERTICES.position(0)
        GlState.TEX_COORDS.position(0)
        GLES20.glEnableVertexAttribArray(pass.position)
        GLES20.glEnableVertexAttribArray(pass.texCoord)
        GLES20.glVertexAttribPointer(pass.position, 2, GLES20.GL_FLOAT, false, 0, GlState.VERTICES)
        GLES20.glVertexAttribPointer(pass.texCoord, 2, GLES20.GL_FLOAT, false, 0, GlState.TEX_COORDS)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pass.position)
        GLES20.glDisableVertexAttribArray(pass.texCoord)
    }

    private fun framebufferFor(texture: Int): Int {
        val framebuffer = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            error("an animation upscaler texture cannot be drawn into: status 0x${status.toString(16)}")
        }
        return framebuffer
    }

    companion object {
        /**
         * Compiles [network] for the current context. Throws, naming the reason, when the context
         * cannot render to half floats or a pass does not compile; nothing is left allocated then.
         */
        fun create(network: Anime4kNetwork): AnimationUpscaleGl {
            val formats = UpscaleFormats.probe()
            val programs = mutableListOf<Int>()
            try {
                val convolutions = network.layers.map { layer ->
                    val program = linkUpscaleProgram(Anime4kGlsl.VERTEX_SHADER, Anime4kGlsl.convolution(layer))
                    programs += program
                    Pass(program, layer.inputs.indices.map(Anime4kGlsl::inputSampler), "uPt")
                }
                val doublingProgram = linkUpscaleProgram(Anime4kGlsl.VERTEX_SHADER, Anime4kGlsl.DEPTH_TO_SPACE)
                programs += doublingProgram
                val doubling = Pass(doublingProgram, listOf("uSource", "uLast"), "uLastSize")
                return AnimationUpscaleGl(network, formats, convolutions, doubling)
            } catch (failure: Throwable) {
                programs.forEach(GLES20::glDeleteProgram)
                throw failure
            }
        }
    }
}

/**
 * An upscaled frame, as the renderer draws it, and as the device test drives it with an ordinary
 * texture in place of MediaCodec's.
 *
 * First [sourceBlit] draws the picture at its own size into the network's source, top row first,
 * through [sourceMatrix]; debanding runs there, on the source, where the bands are. Then the
 * network doubles it. Last, [outputBlit] draws the doubled picture into [outputFramebuffer] at
 * [outputWidth] by [outputHeight], with the kernel, linear light, the colour controls and the
 * dither, exactly as the plain blit would draw the source.
 */
internal fun drawUpscaled(
    upscale: AnimationUpscaleGl,
    sourceBlit: BlitProgram,
    sourceTarget: Int,
    sourceTexture: Int,
    sourceMatrix: FloatArray,
    outputBlit: BlitProgram,
    outputFramebuffer: Int,
    outputWidth: Int,
    outputHeight: Int,
    adjust: FloatArray?,
    ditherStep: Float,
    debandThreshold: Float,
    debandRange: Float,
    debandGrain: Float,
    debandSeed: Float,
    bicubic: Boolean,
    linearLight: Boolean,
) {
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, upscale.sourceFramebuffer)
    GLES20.glViewport(0, 0, upscale.width, upscale.height)
    sourceBlit.draw(
        sourceTarget,
        sourceTexture,
        sourceMatrix,
        GlState.TEX_COORDS_TOP_FIRST,
        upscale.width,
        upscale.height,
        adjust = null,
        ditherStep = 0f,
        debandThreshold = debandThreshold,
        debandRange = debandRange,
        debandGrain = debandGrain,
        debandSeed = debandSeed,
        bicubic = false,
        linearLight = false,
    )
    upscale.run()
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
    GLES20.glViewport(0, 0, outputWidth, outputHeight)
    outputBlit.draw(
        GLES20.GL_TEXTURE_2D,
        upscale.outputTexture,
        GlState.TOP_FIRST_TEXTURE_MATRIX,
        GlState.TEX_COORDS,
        upscale.width * 2,
        upscale.height * 2,
        adjust,
        ditherStep,
        debandThreshold = 0f,
        debandRange = debandRange,
        debandGrain = 0f,
        debandSeed = debandSeed,
        bicubic = bicubic,
        linearLight = linearLight,
    )
}

/**
 * The texture formats this context offers the networks: half floats for the convolutions, which are
 * signed, and for the picture and its double when half floats can also be filtered, else 8 bits.
 */
internal class UpscaleFormats private constructor(
    private val halfInternalFormat: Int,
    private val halfType: Int,
    private val halfFilterable: Boolean,
) {
    /** A convolution's output: half floats, read only at texel centres, so never filtered. */
    fun layerTexture(width: Int, height: Int): Int =
        texture(width, height, halfInternalFormat, halfType, GLES20.GL_NEAREST)

    /** The picture or its double, which the doubling and the blit read with bilinear. */
    fun pictureTexture(width: Int, height: Int): Int =
        if (halfFilterable) {
            texture(width, height, halfInternalFormat, halfType, GLES20.GL_LINEAR)
        } else {
            texture(width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, GLES20.GL_LINEAR)
        }

    companion object {
        /** GL_HALF_FLOAT_OES, which differs from GLES3's GL_HALF_FLOAT. */
        private const val HALF_FLOAT_OES = 0x8D61

        /**
         * GLES3 has sized half-float textures in core and can filter them; drawing into them still
         * takes EXT_color_buffer_half_float or EXT_color_buffer_float until 3.2. A GLES2 context
         * needs OES_texture_half_float to hold them and the same colour-buffer extension to draw.
         * Each candidate is proven by a framebuffer that reports complete, not by the strings.
         */
        fun probe(): UpscaleFormats {
            val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty().split(' ').toSet()
            val major = Regex("""OpenGL ES (\d+)""").find(version)?.groupValues?.get(1)?.toIntOrNull() ?: 2
            val candidates = buildList {
                if (major >= 3) add(UpscaleFormats(GLES30.GL_RGBA16F, GLES30.GL_HALF_FLOAT, true))
                if ("GL_OES_texture_half_float" in extensions) {
                    add(UpscaleFormats(GLES20.GL_RGBA, HALF_FLOAT_OES, "GL_OES_texture_half_float_linear" in extensions))
                }
            }
            val usable = candidates.firstOrNull { it.drawable() }
            // A refused candidate can leave an error behind; it is not the caller's.
            drainGlErrors()
            return usable ?: error(
                "this GPU cannot draw into half-float textures ($version); the animation upscaler needs them",
            )
        }

        private fun texture(width: Int, height: Int, internalFormat: Int, type: Int, filter: Int): Int {
            val texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, GLES20.GL_RGBA, type, null,
            )
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            return texture
        }
    }

    private fun drawable(): Boolean {
        drainGlErrors()
        val texture = layerTexture(4, 4)
        val framebuffer = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
        return try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0,
            )
            GLES20.glGetError() == GLES20.GL_NO_ERROR &&
                GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        }
    }
}

private fun linkUpscaleProgram(vertexSource: String, fragmentSource: String): Int {
    fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "GLES could not create an animation upscaler shader" }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("an animation upscaler shader did not compile: $log")
        }
        return shader
    }
    val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
    val fragment = try {
        compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    } catch (failure: Throwable) {
        GLES20.glDeleteShader(vertex)
        throw failure
    }
    try {
        val program = GLES20.glCreateProgram()
        check(program != 0) { "GLES could not create an animation upscaler program" }
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            error("an animation upscaler program did not link: $log")
        }
        return program
    } finally {
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
    }
}

/** Bounded, because a lost context may report an error on every call. */
private fun drainGlErrors() {
    repeat(16) { if (GLES20.glGetError() == GLES20.GL_NO_ERROR) return }
}

private fun upscaleGlCheck(operation: String) {
    val error = GLES20.glGetError()
    check(error == GLES20.GL_NO_ERROR) { "GLES could not $operation: 0x${error.toString(16)}" }
}
