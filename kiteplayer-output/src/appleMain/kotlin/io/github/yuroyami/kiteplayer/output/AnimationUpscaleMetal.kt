@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Metal.MTLCommandBufferProtocol
import platform.Metal.MTLDeviceProtocol
import platform.Metal.MTLLoadActionDontCare
import platform.Metal.MTLPixelFormatRGBA16Float
import platform.Metal.MTLPrimitiveTypeTriangleStrip
import platform.Metal.MTLRenderPassDescriptor
import platform.Metal.MTLRenderPipelineStateProtocol
import platform.Metal.MTLStorageModePrivate
import platform.Metal.MTLStoreActionStore
import platform.Metal.MTLTextureDescriptor
import platform.Metal.MTLTextureProtocol
import platform.Metal.MTLTextureUsageRenderTarget
import platform.Metal.MTLTextureUsageShaderRead
import kotlin.math.abs
import kotlin.math.roundToInt

/** One network compiled for a device: a pipeline for each convolution and one for the doubling. */
internal class MetalUpscalePipelines(
    val network: Anime4kNetwork,
    val convolutions: List<MTLRenderPipelineStateProtocol>,
    val doubling: MTLRenderPipelineStateProtocol,
)

/**
 * One Anime4K network on Metal (#421), for a [width] by [height] picture: its textures, and the
 * passes that run its convolutions at that size and then double the picture.
 *
 * The composer draws the picture into [source] with its top row at texture row 0, which is the
 * original's own picture space and Metal's too. [encode] then leaves the doubled picture in
 * [output], top row first as well.
 *
 * Every texture holds half floats: the convolutions are signed and run past 1, exactly as mpv
 * stores them. A convolution's texture is reused once its last reader has run.
 */
internal class AnimationUpscaleMetal(
    device: MTLDeviceProtocol,
    val pipelines: MetalUpscalePipelines,
    val width: Int,
    val height: Int,
) {
    private val network = pipelines.network

    /** Where the composer draws the picture before [encode]. */
    val source: MTLTextureProtocol = device.makeUpscaleTexture(width, height)

    /** The doubled picture after [encode], which the composer scales onto its target. */
    val output: MTLTextureProtocol = device.makeUpscaleTexture(width * 2, height * 2)

    private val layerTextures = List(network.slotCount) { device.makeUpscaleTexture(width, height) }

    /** Encodes every convolution and the doubling into [commands], after the pass that drew [source]. */
    fun encode(commands: MTLCommandBufferProtocol) {
        network.layers.forEachIndexed { index, layer ->
            val inputs = layer.inputs.map { input ->
                if (input == Anime4kTerm.SOURCE) source else layerTextures[network.slotOfLayer[input]]
            }
            draw(commands, pipelines.convolutions[index], layerTextures[network.slotOfLayer[index]], inputs)
        }
        val last = layerTextures[network.slotOfLayer[network.layers.lastIndex]]
        draw(commands, pipelines.doubling, output, listOf(source, last))
    }

    /** One pass: a quad over all of [target], reading [inputs] as textures 0 and up. */
    private fun draw(
        commands: MTLCommandBufferProtocol,
        pipeline: MTLRenderPipelineStateProtocol,
        target: MTLTextureProtocol,
        inputs: List<MTLTextureProtocol>,
    ) {
        val pass = MTLRenderPassDescriptor()
        val attachment = pass.colorAttachments.objectAtIndexedSubscript(0u)
        attachment.texture = target
        // The quad covers every texel, so nothing needs loading.
        attachment.loadAction = MTLLoadActionDontCare
        attachment.storeAction = MTLStoreActionStore
        val encoder = checkNotNull(commands.renderCommandEncoderWithDescriptor(pass)) {
            "Metal refused a render encoder for ${network.name}"
        }
        try {
            encoder.setRenderPipelineState(pipeline)
            WHOLE_PICTURE_QUAD.usePinned { pinned ->
                encoder.setVertexBytes(pinned.addressOf(0), (WHOLE_PICTURE_QUAD.size * 4).toULong(), atIndex = 0u)
            }
            inputs.forEachIndexed { index, texture -> encoder.setFragmentTexture(texture, atIndex = index.toULong()) }
            encoder.drawPrimitives(MTLPrimitiveTypeTriangleStrip, vertexStart = 0u, vertexCount = 4u)
        } finally {
            encoder.endEncoding()
        }
    }
}

/**
 * True when a [width] by [height] picture drawn through [quad] onto a target of [targetWidth] by
 * [targetHeight] pixels is enlarged past the networks' rule, [Anime4kNetwork.runsAt].
 *
 * The quad's texture basis says how many of the picture's texels each of its sides spans, which
 * covers a crop and a quarter turn. Its half-extent says how many target pixels it is drawn on.
 */
internal fun upscaleRunsAt(quad: FloatArray, width: Int, height: Int, targetWidth: Int, targetHeight: Int): Boolean =
    Anime4kNetwork.runsAt(
        sourceWidth = (abs(quad[2]) * width + abs(quad[4]) * height).roundToInt(),
        sourceHeight = (abs(quad[3]) * width + abs(quad[5]) * height).roundToInt(),
        outputWidth = (abs(quad[0]) * targetWidth).roundToInt(),
        outputHeight = (abs(quad[1]) * targetHeight).roundToInt(),
    )

/** A quad over the whole target with the picture upright as stored, its top row at texture row 0. */
internal val WHOLE_PICTURE_QUAD: FloatArray = floatArrayOf(1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f)

/** A half-float texture that only the GPU reads and writes. Every Apple GPU draws into and filters it. */
private fun MTLDeviceProtocol.makeUpscaleTexture(width: Int, height: Int): MTLTextureProtocol {
    val descriptor = MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(
        pixelFormat = MTLPixelFormatRGBA16Float,
        width = width.toULong(),
        height = height.toULong(),
        mipmapped = false,
    )
    descriptor.usage = MTLTextureUsageRenderTarget or MTLTextureUsageShaderRead
    descriptor.storageMode = MTLStorageModePrivate
    return checkNotNull(newTextureWithDescriptor(descriptor)) {
        "Metal refused a ${width}x$height texture for the animation upscaler"
    }
}
