@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.Metal.MTLBlendFactorOne
import platform.Metal.MTLBlendFactorOneMinusSourceAlpha
import platform.Metal.MTLBlendFactorSourceAlpha
import platform.Metal.MTLDeviceProtocol
import platform.Metal.MTLLibraryProtocol
import platform.Metal.MTLPixelFormatBGRA8Unorm
import platform.Metal.MTLPixelFormatR16Unorm
import platform.Metal.MTLPixelFormatR8Unorm
import platform.Metal.MTLPixelFormatRG16Unorm
import platform.Metal.MTLPixelFormatRG8Unorm
import platform.Metal.MTLPixelFormatRGBA8Unorm
import platform.Metal.MTLRenderPipelineDescriptor
import platform.Metal.MTLRenderPipelineStateProtocol
import platform.Metal.MTLTextureDescriptor
import platform.Metal.MTLTextureProtocol
import platform.Metal.MTLTextureUsageRenderTarget
import platform.Metal.MTLTextureUsageShaderRead

/**
 * How a Metal renderer reads a frame's pixels (S2.c). The output module never depends on the
 * FFmpeg backend, so a consumer that owns both sides supplies this resolver, exactly the way the
 * CG renderers take their `convert` lambda. The two shapes are the two truths a frame can have:
 * a CVPixelBuffer straight from VideoToolbox (zero copies), or software planes read out of the
 * decoder's buffers (one memcpy per plane, no colour conversion on the CPU, which is law 2 of
 * KPKMP 17.9: YUV until the GPU).
 */
public fun interface MetalPictureResolver {
    /** Null refuses the frame; the renderer counts it failed rather than guessing. */
    public fun resolve(frame: VideoFrame): MetalPicture?
}

public sealed class MetalPicture {
    /** An IOSurface-backed CVPixelBuffer. The renderer wraps its planes with no copy. */
    public class CorePixelBuffer(public val buffer: COpaquePointer) : MetalPicture()

    /** Decoded planes copied out of the frame, still in their native pixel format. */
    public class SoftwarePlanes(
        public val width: Int,
        public val height: Int,
        public val format: PlayerPixelFormat,
        public val planes: List<Plane>,
    ) : MetalPicture() {
        init {
            require(width > 0 && height > 0) { "SoftwarePlanes needs positive dimensions, got ${width}x$height" }
            require(planes.isNotEmpty()) { "SoftwarePlanes needs at least one plane" }
        }

        public class Plane(
            public val bytes: ByteArray,
            public val bytesPerRow: Int,
            public val rows: Int,
        ) {
            init {
                // The upload path hands `bytes` to Metal pinned, so the declared geometry must
                // never describe more storage than the array actually holds: Metal would read
                // past the pin. Checked in Long because bytesPerRow * rows can overflow Int.
                require(bytesPerRow > 0) { "bytesPerRow must be positive, got $bytesPerRow" }
                require(rows > 0) { "rows must be positive, got $rows" }
                require(bytes.size.toLong() >= bytesPerRow.toLong() * rows.toLong()) {
                    "plane storage is ${bytes.size} bytes but bytesPerRow=$bytesPerRow x rows=$rows " +
                        "declares ${bytesPerRow.toLong() * rows.toLong()}"
                }
            }
        }
    }
}

/**
 * The Metal shading language source, compiled at runtime through the Metal API (an owner-fixed
 * point of 17.4.8: no .metallib toolchain enters the build).
 *
 * The colour math is DELIBERATELY the same arithmetic as SoftwareConverter's Coefficients, in
 * the same 0..255 working space, so the GPU and CPU paths can be compared pixel for pixel by the
 * colour instrument. Mode selects the plane layout; the matrix and ranges arrive as uniforms.
 */
internal const val METAL_SHADER_SOURCE: String = """
#include <metal_stdlib>
using namespace metal;

struct VertexOut {
    float4 position [[position]];
    float2 texcoord;
};

struct QuadUniforms {
    float2 scale;      // NDC half-extent of the quad
    float2 texRotate0; // texcoord basis row 0 (rotation)
    float2 texRotate1; // texcoord basis row 1
    float2 offset;     // NDC centre of the quad
};

struct ColorUniforms {
    float lumaOffset;   // 0..255 space
    float lumaScale;
    float chromaScale;
    float rCr;
    float gCb;
    float gCr;
    float bCb;
    float sampleScale;  // normalizes 10-bit payloads to 0..1
    int   mode;         // 0 = three planes, 1 = biplanar, 2 = packed rgba
};

vertex VertexOut kp_vertex(uint id [[vertex_id]], constant QuadUniforms &quad [[buffer(0)]]) {
    // One triangle strip: (-1,-1) (1,-1) (-1,1) (1,1), texcoords with y down.
    float2 corners[4] = { float2(-1.0, -1.0), float2(1.0, -1.0), float2(-1.0, 1.0), float2(1.0, 1.0) };
    float2 texbase[4] = { float2(0.0, 1.0), float2(1.0, 1.0), float2(0.0, 0.0), float2(1.0, 0.0) };
    VertexOut out;
    out.position = float4(corners[id] * quad.scale + quad.offset, 0.0, 1.0);
    float2 centered = texbase[id] - float2(0.5, 0.5);
    out.texcoord = float2(
        dot(centered, quad.texRotate0),
        dot(centered, quad.texRotate1)
    ) + float2(0.5, 0.5);
    return out;
}

fragment float4 kp_picture(
    VertexOut in [[stage_in]],
    texture2d<float> planeA [[texture(0)]],
    texture2d<float> planeB [[texture(1)]],
    texture2d<float> planeC [[texture(2)]],
    constant ColorUniforms &c [[buffer(0)]]
) {
    constexpr sampler s(mag_filter::linear, min_filter::linear, address::clamp_to_edge);
    if (c.mode == 2) {
        return float4(planeA.sample(s, in.texcoord).rgb, 1.0);
    }
    float rawY;
    float rawCb;
    float rawCr;
    if (c.mode == 1) {
        rawY = planeA.sample(s, in.texcoord).r;
        float2 uv = planeB.sample(s, in.texcoord).rg;
        rawCb = uv.x;
        rawCr = uv.y;
    } else {
        rawY = planeA.sample(s, in.texcoord).r;
        rawCb = planeB.sample(s, in.texcoord).r;
        rawCr = planeC.sample(s, in.texcoord).r;
    }
    float y = (rawY * c.sampleScale * 255.0 - c.lumaOffset) * c.lumaScale;
    float cb = (rawCb * c.sampleScale * 255.0 - 128.0) * c.chromaScale;
    float cr = (rawCr * c.sampleScale * 255.0 - 128.0) * c.chromaScale;
    float r = y + c.rCr * cr;
    float g = y - c.gCb * cb - c.gCr * cr;
    float b = y + c.bCb * cb;
    return float4(clamp(float3(r, g, b) / 255.0, 0.0, 1.0), 1.0);
}

fragment float4 kp_overlay(
    VertexOut in [[stage_in]],
    texture2d<float> image [[texture(0)]]
) {
    constexpr sampler s(mag_filter::linear, min_filter::linear, address::clamp_to_edge);
    return image.sample(s, in.texcoord);
}
"""

/** The nine colour uniforms, mirrored from SoftwareConverter so both paths agree numerically. */
internal class MetalColorUniforms private constructor(
    val lumaOffset: Float,
    val lumaScale: Float,
    val chromaScale: Float,
    val rCr: Float,
    val gCb: Float,
    val gCr: Float,
    val bCb: Float,
) {
    fun packWith(sampleScale: Float, mode: Int): FloatArray = floatArrayOf(
        lumaOffset, lumaScale, chromaScale, rCr, gCb, gCr, bCb, sampleScale,
        Float.fromBits(mode), // reinterpreted as int in the shader's layout
    )

    companion object {
        fun of(colorSpace: ColorSpaceInfo): MetalColorUniforms {
            val offset = if (colorSpace.fullRange) 0f else 16f
            val lumaScale = if (colorSpace.fullRange) 1f else 255f / 219f
            val chromaScale = if (colorSpace.fullRange) 1f else 255f / 224f
            return when (colorSpace.matrix) {
                ColorMatrix.Bt601 -> MetalColorUniforms(
                    offset, lumaScale, chromaScale,
                    rCr = 1.402f, gCb = 0.344136f, gCr = 0.714136f, bCb = 1.772f,
                )
                ColorMatrix.Smpte240m -> MetalColorUniforms(
                    offset, lumaScale, chromaScale,
                    rCr = 1.576f, gCb = 0.2266f, gCr = 0.4769f, bCb = 1.826f,
                )
                ColorMatrix.Bt2020Ncl, ColorMatrix.Bt2020Cl -> MetalColorUniforms(
                    offset, lumaScale, chromaScale,
                    rCr = 1.4746f, gCb = 0.164553f, gCr = 0.571353f, bCb = 1.8814f,
                )
                else -> MetalColorUniforms(
                    offset, lumaScale, chromaScale,
                    rCr = 1.5748f, gCb = 0.187324f, gCr = 0.468124f, bCb = 1.8556f,
                )
            }
        }
    }
}

/** Compiles [METAL_SHADER_SOURCE] and reports the compiler's own words when it refuses. */
internal fun MTLDeviceProtocol.compileKitePlayerLibrary(): MTLLibraryProtocol = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    val library = newLibraryWithSource(METAL_SHADER_SOURCE, options = null, error = error.ptr)
    checkNotNull(library) {
        "Metal shader compilation failed: ${error.value?.localizedDescription ?: "no diagnostic"}"
    }
}

internal fun MTLDeviceProtocol.makePicturePipeline(
    library: MTLLibraryProtocol,
    targetFormat: ULong,
): MTLRenderPipelineStateProtocol = makePipeline(library, "kp_picture", targetFormat, blended = false)

internal fun MTLDeviceProtocol.makeOverlayPipeline(
    library: MTLLibraryProtocol,
    targetFormat: ULong,
): MTLRenderPipelineStateProtocol = makePipeline(library, "kp_overlay", targetFormat, blended = true)

private fun MTLDeviceProtocol.makePipeline(
    library: MTLLibraryProtocol,
    fragment: String,
    targetFormat: ULong,
    blended: Boolean,
): MTLRenderPipelineStateProtocol = memScoped {
    val descriptor = MTLRenderPipelineDescriptor()
    descriptor.vertexFunction = library.newFunctionWithName("kp_vertex")
    descriptor.fragmentFunction = library.newFunctionWithName(fragment)
    val attachment = descriptor.colorAttachments.objectAtIndexedSubscript(0u)
    attachment.pixelFormat = targetFormat
    if (blended) {
        attachment.blendingEnabled = true
        attachment.sourceRGBBlendFactor = MTLBlendFactorSourceAlpha
        attachment.destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha
        attachment.sourceAlphaBlendFactor = MTLBlendFactorOne
        attachment.destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha
    }
    val error = alloc<ObjCObjectVar<NSError?>>()
    val state = newRenderPipelineStateWithDescriptor(descriptor, error = error.ptr)
    checkNotNull(state) {
        "Metal pipeline '$fragment' failed: ${error.value?.localizedDescription ?: "no diagnostic"}"
    }
}

/** A plain shader-readable texture holding one uploaded plane. */
internal fun MTLDeviceProtocol.makePlaneTexture(
    format: ULong,
    width: Int,
    height: Int,
): MTLTextureProtocol {
    val descriptor = MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(
        pixelFormat = format,
        width = width.toULong(),
        height = height.toULong(),
        mipmapped = false,
    )
    descriptor.usage = MTLTextureUsageShaderRead
    return checkNotNull(newTextureWithDescriptor(descriptor)) { "Metal refused a ${width}x$height plane texture" }
}

/** An offscreen render target, which is also what the colour instrument reads back. */
internal fun MTLDeviceProtocol.makeTargetTexture(
    width: Int,
    height: Int,
    format: ULong = MTLPixelFormatBGRA8Unorm,
): MTLTextureProtocol {
    val descriptor = MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(
        pixelFormat = format,
        width = width.toULong(),
        height = height.toULong(),
        mipmapped = false,
    )
    descriptor.usage = MTLTextureUsageRenderTarget or MTLTextureUsageShaderRead
    // Shared storage so tests and screenshots read the pixels straight back on Apple silicon.
    descriptor.storageMode = platform.Metal.MTLStorageModeShared
    return checkNotNull(newTextureWithDescriptor(descriptor)) { "Metal refused a ${width}x$height render target" }
}

/**
 * The per-format plane recipe: which Metal formats hold each plane, how chroma dimensions derive
 * from the picture's, the 10-bit normalization, and the shader mode.
 */
internal data class PlaneRecipe(
    val formats: List<ULong>,
    val chromaShiftX: Int,
    val chromaShiftY: Int,
    val sampleScale: Float,
    val mode: Int,
)

internal fun planeRecipeFor(format: PlayerPixelFormat): PlaneRecipe? = when (format) {
    PlayerPixelFormat.Yuv420p -> PlaneRecipe(
        listOf(MTLPixelFormatR8Unorm, MTLPixelFormatR8Unorm, MTLPixelFormatR8Unorm), 1, 1, 1f, 0,
    )
    PlayerPixelFormat.Yuv422p -> PlaneRecipe(
        listOf(MTLPixelFormatR8Unorm, MTLPixelFormatR8Unorm, MTLPixelFormatR8Unorm), 1, 0, 1f, 0,
    )
    PlayerPixelFormat.Yuv444p -> PlaneRecipe(
        listOf(MTLPixelFormatR8Unorm, MTLPixelFormatR8Unorm, MTLPixelFormatR8Unorm), 0, 0, 1f, 0,
    )
    // Low-aligned ten bit: the value sits in the low bits of sixteen, so a normalized sample is
    // value/65535 and the shader multiplies by 65535/1023 to reach 0..1.
    PlayerPixelFormat.Yuv420p10le -> PlaneRecipe(
        listOf(MTLPixelFormatR16Unorm, MTLPixelFormatR16Unorm, MTLPixelFormatR16Unorm), 1, 1, 65535f / 1023f, 0,
    )
    PlayerPixelFormat.Yuv422p10le -> PlaneRecipe(
        listOf(MTLPixelFormatR16Unorm, MTLPixelFormatR16Unorm, MTLPixelFormatR16Unorm), 1, 0, 65535f / 1023f, 0,
    )
    PlayerPixelFormat.Nv12 -> PlaneRecipe(
        listOf(MTLPixelFormatR8Unorm, MTLPixelFormatRG8Unorm), 1, 1, 1f, 1,
    )
    // High-aligned ten bit (P010): value in the high bits, so the correction is 65535/65472.
    PlayerPixelFormat.P010le -> PlaneRecipe(
        listOf(MTLPixelFormatR16Unorm, MTLPixelFormatRG16Unorm), 1, 1, 65535f / 65472f, 1,
    )
    PlayerPixelFormat.Rgba -> PlaneRecipe(listOf(MTLPixelFormatRGBA8Unorm), 0, 0, 1f, 2)
    PlayerPixelFormat.Bgra -> PlaneRecipe(listOf(MTLPixelFormatBGRA8Unorm), 0, 0, 1f, 2)
    else -> null
}

/** Uploads one tightly strided plane. replaceRegion honours bytesPerRow, so no row loop exists. */
internal fun MTLTextureProtocol.uploadPlane(plane: MetalPicture.SoftwarePlanes.Plane) {
    // The region covers the whole texture, so the plane must actually carry that many rows and
    // each row must span the texture's width at this format's element size. Without these checks
    // Metal reads bytesPerRow * height bytes from the pinned array regardless of its length.
    val elementBytes = when (pixelFormat) {
        MTLPixelFormatR8Unorm -> 1L
        MTLPixelFormatRG8Unorm, MTLPixelFormatR16Unorm -> 2L
        MTLPixelFormatRG16Unorm, MTLPixelFormatRGBA8Unorm, MTLPixelFormatBGRA8Unorm -> 4L
        else -> error("uploadPlane does not know the element size of Metal pixel format $pixelFormat")
    }
    require(plane.rows.toULong() >= height) {
        "plane has ${plane.rows} rows but the texture needs $height"
    }
    require(plane.bytesPerRow.toLong() >= width.toLong() * elementBytes) {
        "plane stride ${plane.bytesPerRow} is narrower than $width texels x $elementBytes bytes"
    }
    require(plane.bytes.size.toLong() >= plane.bytesPerRow.toLong() * height.toLong()) {
        "plane storage ${plane.bytes.size} cannot cover stride ${plane.bytesPerRow} x $height rows"
    }
    plane.bytes.usePinned { pinned ->
        replaceRegion(
            region = platform.Metal.MTLRegionMake2D(0u, 0u, width, height),
            mipmapLevel = 0u,
            withBytes = pinned.addressOf(0),
            bytesPerRow = plane.bytesPerRow.toULong(),
        )
    }
}

/**
 * The letterbox and rotation arithmetic, one place, pure. The quad scale is the picture's NDC
 * half-extent inside the viewport; the texcoord basis turns the sampling by the frame's own
 * clockwise rotation, so the stored picture is read turned rather than the geometry rebuilt.
 */
internal fun quadUniformsFor(
    frame: VideoFrame,
    viewportWidth: Int,
    viewportHeight: Int,
): FloatArray {
    val size = frame.size
    val sarNum = size.pixelAspectNumerator.takeIf { it > 0 } ?: 1
    val sarDen = size.pixelAspectDenominator.takeIf { it > 0 } ?: 1
    val storedWidth = size.width.toFloat() * sarNum / sarDen
    val storedHeight = size.height.toFloat()
    val quarterTurn = frame.rotationDegrees == 90 || frame.rotationDegrees == 270
    val displayWidth = if (quarterTurn) storedHeight else storedWidth
    val displayHeight = if (quarterTurn) storedWidth else storedHeight
    val scale = minOf(viewportWidth / displayWidth, viewportHeight / displayHeight)
    val ndcX = displayWidth * scale / viewportWidth
    val ndcY = displayHeight * scale / viewportHeight
    // Clockwise picture rotation = sampling basis turned the opposite way.
    val basis = when (frame.rotationDegrees) {
        90 -> floatArrayOf(0f, -1f, 1f, 0f)
        180 -> floatArrayOf(-1f, 0f, 0f, -1f)
        270 -> floatArrayOf(0f, 1f, -1f, 0f)
        else -> floatArrayOf(1f, 0f, 0f, 1f)
    }
    return floatArrayOf(ndcX, ndcY, basis[0], basis[1], basis[2], basis[3], 0f, 0f)
}

/** Overlay quad placement in viewport pixels, converted to the same NDC space. */
internal fun overlayQuadUniforms(
    image: OverlayImage,
    overlay: SubtitleOverlay,
    viewportWidth: Int,
    viewportHeight: Int,
): FloatArray {
    // Overlay coordinates are authored against the overlay's own viewport; scale to the real one.
    val sx = viewportWidth.toFloat() / overlay.viewportWidth.coerceAtLeast(1)
    val sy = viewportHeight.toFloat() / overlay.viewportHeight.coerceAtLeast(1)
    val left = image.x * sx
    val top = image.y * sy
    val width = image.bitmap.width * sx
    val height = image.bitmap.height * sy
    val ndcHalfW = width / viewportWidth
    val ndcHalfH = height / viewportHeight
    val centerX = (left + width / 2f) / viewportWidth * 2f - 1f
    val centerY = 1f - (top + height / 2f) / viewportHeight * 2f
    return floatArrayOf(ndcHalfW, ndcHalfH, 1f, 0f, 0f, 1f, centerX, centerY)
}
