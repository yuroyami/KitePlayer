@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.VideoScale
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

struct AdjustUniforms {
    float m[9];         // row-major 3x3 over unit-domain RGB: the engine's one colour-matrix law
    float offset[3];    // unit-domain translation per channel
    int   enabled;      // 0 skips the multiply entirely, keeping identity bit-exact for the instrument
};

struct QualityUniforms {
    int   flags;        // bit 0 dither. Later rungs claim the higher bits; 0 is the pre-17.21 write.
    float ditherScale;  // one output step, so the pattern is exactly +/- half a step
    float pad0;
    float pad1;
};

struct ToneUniforms {
    int   mode;         // 0 off (bit-exact SDR path), 1 PQ, 2 HLG
    float srcPeak;      // source peak in nits; the EETF's source anchor
    int   gamut2020;    // 1 converts BT.2020 primaries to BT.709 in linear light
    float pad;
};

/* An 8x8 ordered Bayer matrix, the classic recursive construction, as values in 0..63.
 *
 * Ordered rather than blue noise because it needs no texture and no upload: this is the last
 * instruction before an 8-bit write, and the whole point is that it costs nothing. The pattern is
 * centred below (value + 0.5) / 64 - 0.5, so it adds and subtracts equally and cannot shift the
 * average brightness of a flat area, which a naive 0..1 pattern would. */
constant float KP_BAYER8[64] = {
     0.0, 32.0,  8.0, 40.0,  2.0, 34.0, 10.0, 42.0,
    48.0, 16.0, 56.0, 24.0, 50.0, 18.0, 58.0, 26.0,
    12.0, 44.0,  4.0, 36.0, 14.0, 46.0,  6.0, 38.0,
    60.0, 28.0, 52.0, 20.0, 62.0, 30.0, 54.0, 22.0,
     3.0, 35.0, 11.0, 43.0,  1.0, 33.0,  9.0, 41.0,
    51.0, 19.0, 59.0, 27.0, 49.0, 17.0, 57.0, 25.0,
    15.0, 47.0,  7.0, 39.0, 13.0, 45.0,  5.0, 37.0,
    63.0, 31.0, 55.0, 23.0, 61.0, 29.0, 53.0, 21.0
};

/* Half an output step of ordered noise, keyed on the pixel's own position on the target.
 *
 * Applied to every channel with the SAME offset on purpose: an independent offset per channel
 * dithers chroma as well as luma and shows up as coloured speckle on grey ramps, which is worse
 * than the banding it removes. */
static inline float3 kp_dither(float3 rgb, float2 position, float step) {
    uint x = uint(position.x) & 7u;
    uint y = uint(position.y) & 7u;
    float pattern = (KP_BAYER8[y * 8u + x] + 0.5) / 64.0 - 0.5;
    return rgb + pattern * step;
}

// SMPTE ST 2084 (PQ) constants.
constant float KP_PQ_M1 = 0.1593017578125;
constant float KP_PQ_M2 = 78.84375;
constant float KP_PQ_C1 = 0.8359375;
constant float KP_PQ_C2 = 18.8515625;
constant float KP_PQ_C3 = 18.6875;

// PQ electrical 0..1 -> linear luminance as a fraction of 10000 nits.
static inline float3 kp_pq_decode(float3 e) {
    float3 p = pow(max(e, 0.0), float3(1.0 / KP_PQ_M2));
    return pow(max(p - KP_PQ_C1, 0.0) / (KP_PQ_C2 - KP_PQ_C3 * p), float3(1.0 / KP_PQ_M1));
}

static inline float kp_pq_encode1(float y) {
    float p = pow(max(y, 0.0), KP_PQ_M1);
    return pow((KP_PQ_C1 + KP_PQ_C2 * p) / (1.0 + KP_PQ_C3 * p), KP_PQ_M2);
}

static inline float kp_pq_decode1(float e) {
    float p = pow(max(e, 0.0), 1.0 / KP_PQ_M2);
    return pow(max(p - KP_PQ_C1, 0.0) / (KP_PQ_C2 - KP_PQ_C3 * p), 1.0 / KP_PQ_M1);
}

// BT.2390 EETF: maps one luminance in nits from [0, srcPeak] into [0, 203] (SDR reference
// white per BT.2408), working in normalized PQ space. This is the same operator mpv defaults
// to; below the knee luminance passes through unchanged, above it a Hermite spline rolls off.
static float kp_eetf_nits(float nits, float srcPeak) {
    float srcPq = kp_pq_encode1(srcPeak / 10000.0);
    float dstPq = kp_pq_encode1(203.0 / 10000.0);
    float e1 = clamp(kp_pq_encode1(nits / 10000.0) / srcPq, 0.0, 1.0);
    float maxLum = dstPq / srcPq;
    float ks = 1.5 * maxLum - 0.5;
    float e2 = e1;
    if (e1 > ks) {
        float t = (e1 - ks) / (1.0 - ks);
        float t2 = t * t;
        float t3 = t2 * t;
        e2 = (2.0 * t3 - 3.0 * t2 + 1.0) * ks
           + (t3 - 2.0 * t2 + t) * (1.0 - ks)
           + (-2.0 * t3 + 3.0 * t2) * maxLum;
    }
    return kp_pq_decode1(e2 * srcPq) * 10000.0;
}

// Applies the HDR-to-SDR law to one gamma-domain RGB sample. Returns SDR gamma 2.2 RGB.
static float3 kp_tone_map(float3 rgb, constant ToneUniforms &tone) {
    float3 nits;
    if (tone.mode == 1) {
        nits = kp_pq_decode(rgb) * 10000.0;
    } else {
        // HLG inverse OETF to scene light, then the BT.2100 OOTF at Lw = 1000 nits.
        float3 e = max(rgb, 0.0);
        float3 lo = e * e * (1.0 / 3.0);
        float3 hi = (exp((e - 0.55991073) / 0.17883277) + 0.28466892) * (1.0 / 12.0);
        float3 scene = select(hi, lo, e <= float3(0.5));
        float ys = dot(scene, float3(0.2627, 0.6780, 0.0593));
        nits = scene * pow(max(ys, 1e-6), 0.2) * 1000.0;
    }
    if (tone.gamut2020 != 0) {
        // BT.2020 -> BT.709 primaries in linear light (columns of the standard matrix).
        const float3x3 to709 = float3x3(
            float3(1.6605, -0.1246, -0.0182),
            float3(-0.5876, 1.1329, -0.1006),
            float3(-0.0728, -0.0083, 1.1187));
        nits = max(to709 * nits, 0.0);
    }
    float luma = dot(nits, float3(0.2126, 0.7152, 0.0722));
    float mapped = kp_eetf_nits(luma, tone.srcPeak);
    float ratio = luma > 1e-4 ? mapped / luma : 1.0;
    float3 sdr = clamp(nits * ratio * (1.0 / 203.0), 0.0, 1.0);
    return pow(sdr, float3(1.0 / 2.2));
}

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
    constant ColorUniforms &c [[buffer(0)]],
    constant AdjustUniforms &adj [[buffer(1)]],
    constant ToneUniforms &tone [[buffer(2)]],
    constant QualityUniforms &q [[buffer(3)]]
) {
    constexpr sampler s(mag_filter::linear, min_filter::linear, address::clamp_to_edge);
    float3 rgb;
    if (c.mode == 2) {
        rgb = planeA.sample(s, in.texcoord).rgb;
    } else {
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
        rgb = clamp(float3(r, g, b) / 255.0, 0.0, 1.0);
    }
    if (tone.mode != 0) {
        rgb = kp_tone_map(rgb, tone);
    }
    if (adj.enabled != 0) {
        float3 p = rgb;
        rgb = clamp(float3(
            adj.m[0] * p.x + adj.m[1] * p.y + adj.m[2] * p.z + adj.offset[0],
            adj.m[3] * p.x + adj.m[4] * p.y + adj.m[5] * p.z + adj.offset[1],
            adj.m[6] * p.x + adj.m[7] * p.y + adj.m[8] * p.z + adj.offset[2]
        ), 0.0, 1.0);
    }
    /* Last, after every stage that could have produced an off-grid value: tone mapping, the eq
     * matrix and the YUV conversion all land between output steps, and this is the only place that
     * knows what a step is worth. Clamped again because the pattern can push a 0.0 or a 1.0 out of
     * range by half a step. */
    if ((q.flags & 1) != 0) {
        rgb = clamp(kp_dither(rgb, in.position.xy, q.ditherScale), 0.0, 1.0);
    }
    return float4(rgb, 1.0);
}

fragment float4 kp_overlay(
    VertexOut in [[stage_in]],
    texture2d<float> image [[texture(0)]]
) {
    constexpr sampler s(mag_filter::linear, min_filter::linear, address::clamp_to_edge);
    return image.sample(s, in.texcoord);
}
"""

/**
 * AdjustUniforms with enabled=0: the shader skips the multiply, keeping the unadjusted path
 * bit-exact for the colour instrument. One shared array, because it never changes.
 */
internal val DISABLED_ADJUST_UNIFORMS: FloatArray = FloatArray(13)

/** ToneUniforms with mode=0: the SDR path skips tone mapping entirely, bit-exact. */
internal val DISABLED_TONE_UNIFORMS: FloatArray = FloatArray(4)

/**
 * Packs the HDR-to-SDR law's uniforms from the frame's declared colour. SDR transfers return
 * the disabled block, which is what keeps every existing SDR pixel bit-exact.
 *
 * srcPeak is 1000 nits for both transfers: PQ mastering metadata (SMPTE ST 2086 / CTA-861.3)
 * is not plumbed through [ColorSpaceInfo] yet, and 1000 is both HLG's nominal peak and the
 * commonest PQ mastering level. Recorded as the honest limit of this first tone-mapping pass.
 */
/**
 * QualityUniforms for [quality], targeting a write of [targetBits] bits per channel (17.21).
 *
 * The dither step is one output step, `1 / (2^bits - 1)`, because the pattern's job is to spread a
 * value across the two grid points it falls between. Reading it from the target rather than
 * assuming 8 keeps a future 10-bit surface honest for free.
 */
internal fun packQualityUniforms(
    quality: io.github.yuroyami.kiteplayer.RenderQuality,
    targetBits: Int = 8,
): FloatArray {
    var flags = 0
    if (quality.dither) flags = flags or 1
    val levels = (1 shl targetBits) - 1
    return floatArrayOf(
        Float.fromBits(flags),
        1f / levels,
        0f,
        0f,
    )
}

/** QualityUniforms with every flag clear: the pre-17.21 write, bit for bit. */
internal val DISABLED_QUALITY_UNIFORMS: FloatArray = packQualityUniforms(
    io.github.yuroyami.kiteplayer.RenderQuality.Off,
)

internal fun packToneUniforms(colorSpace: ColorSpaceInfo): FloatArray {
    val mode = when (colorSpace.transfer) {
        io.github.yuroyami.kiteplayer.spi.ColorTransfer.Pq -> 1
        io.github.yuroyami.kiteplayer.spi.ColorTransfer.Hlg -> 2
        else -> return DISABLED_TONE_UNIFORMS
    }
    val gamut = if (colorSpace.primaries == io.github.yuroyami.kiteplayer.spi.ColorPrimaries.Bt2020) 1 else 0
    return floatArrayOf(
        Float.fromBits(mode),
        1000f,
        Float.fromBits(gamut),
        0f,
    )
}

/**
 * Packs the engine's one colour-matrix law into the shader's AdjustUniforms layout: nine
 * row-major 3x3 coefficients, three unit-domain offsets, and the enabled flag as int bits. The
 * 4x5 matrix's alpha row and column are dropped because the shader owns alpha as constant 1.
 */
internal fun packAdjustUniforms(adjustments: io.github.yuroyami.kiteplayer.VideoAdjustments): FloatArray {
    if (adjustments.isIdentity) return DISABLED_ADJUST_UNIFORMS
    val m = adjustments.toColorMatrix()
    return floatArrayOf(
        m[0], m[1], m[2], m[5], m[6], m[7], m[10], m[11], m[12],
        m[4], m[9], m[14],
        Float.fromBits(1),
    )
}

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
                ColorMatrix.Bt601, ColorMatrix.Bt470bg, ColorMatrix.Smpte170m -> MetalColorUniforms(
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

/**
 * The compiled shader library and both pipeline states, shared per device and target format
 * (17.11 SOL-P7).
 *
 * These are immutable and owned by the Metal device, so one set serves every composer on it. They
 * are never released: a device outlives every renderer built on it, and the old per-composer
 * compile is what this replaces.
 */
internal class MetalPipelines private constructor(
    val picture: MTLRenderPipelineStateProtocol,
    val overlay: MTLRenderPipelineStateProtocol,
) {
    companion object {
        private val lock = kotlinx.atomicfu.locks.SynchronizedObject()
        private val cache = mutableMapOf<Pair<ULong, ULong>, MetalPipelines>()

        fun of(device: MTLDeviceProtocol, targetFormat: ULong): MetalPipelines =
            kotlinx.atomicfu.locks.synchronized(lock) {
                cache.getOrPut(device.registryID to targetFormat) {
                    val library = device.compileKitePlayerLibrary()
                    MetalPipelines(
                        picture = device.makePicturePipeline(library, targetFormat),
                        overlay = device.makeOverlayPipeline(library, targetFormat),
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
        // Overlay pixels arrive PREMULTIPLIED (the RgbaBitmap contract, unified 2026-08-17),
        // so the source RGB factor is One: multiplying by source alpha again darkened every
        // antialiased edge and translucent cue (audit F-ALPHA1's Metal half).
        attachment.sourceRGBBlendFactor = MTLBlendFactorOne
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
 *
 * [mode] decides only what happens between the display shape and the viewport shape: Fit takes
 * the smaller axis ratio and letterboxes, Fill takes the larger and lets the viewport's own
 * scissor crop the overhang, Stretch takes both axes whole. Pixel aspect and rotation are
 * applied before any of them, so no mode can draw the pixels the wrong shape by accident.
 */
internal fun quadUniformsFor(
    frame: VideoFrame,
    viewportWidth: Int,
    viewportHeight: Int,
    mode: VideoScale = VideoScale.Fit,
    transform: io.github.yuroyami.kiteplayer.VideoTransform = io.github.yuroyami.kiteplayer.VideoTransform.Identity,
): FloatArray {
    val size = frame.size
    val sarNum = size.pixelAspectNumerator.takeIf { it > 0 } ?: 1
    val sarDen = size.pixelAspectDenominator.takeIf { it > 0 } ?: 1
    val storedWidth = size.width.toFloat() * sarNum / sarDen
    val storedHeight = size.height.toFloat()
    val turn = normalizedQuarterTurn(frame.rotationDegrees)
    val quarterTurn = turn == 90 || turn == 270
    // The forced aspect describes the picture AS PRESENTED, after the turn, and only its ratio
    // matters to the fit: the same words as the other two geometries, so no drift.
    val aspect = transform.aspectOverride?.takeIf { it > 0f && it.isFinite() }
    val displayWidth = aspect ?: if (quarterTurn) storedHeight else storedWidth
    val displayHeight = if (aspect != null) 1f else if (quarterTurn) storedWidth else storedHeight
    var ndcX: Float
    var ndcY: Float
    when (mode) {
        VideoScale.Stretch -> {
            ndcX = 1f
            ndcY = 1f
        }
        else -> {
            val fitScale = minOf(viewportWidth / displayWidth, viewportHeight / displayHeight)
            val fillScale = maxOf(viewportWidth / displayWidth, viewportHeight / displayHeight)
            val scale = if (mode == VideoScale.Fill) fillScale else fitScale
            ndcX = displayWidth * scale / viewportWidth
            ndcY = displayHeight * scale / viewportHeight
        }
    }
    // Zoom about the centre, then pan by a fraction of the drawn size (2*ndc is the quad's own
    // NDC extent). NDC y points up while the engine's pan convention is positive-down, hence
    // the sign. The viewport clips the overhang, as it already does for Fill.
    ndcX *= transform.zoom
    ndcY *= transform.zoom
    val offsetX = transform.panX * 2f * ndcX
    val offsetY = -transform.panY * 2f * ndcY
    // Clockwise picture rotation = sampling basis turned the opposite way.
    val basis = when (turn) {
        90 -> floatArrayOf(0f, -1f, 1f, 0f)
        180 -> floatArrayOf(-1f, 0f, 0f, -1f)
        270 -> floatArrayOf(0f, 1f, -1f, 0f)
        else -> floatArrayOf(1f, 0f, 0f, 1f)
    }
    return floatArrayOf(ndcX, ndcY, basis[0], basis[1], basis[2], basis[3], offsetX, offsetY)
}

/**
 * Container rotation reduced to one of 0, 90, 180, 270. The same law KiteVideo's geometry
 * applies: normalize modulo 360 first (so -90 means 270 and 450 means 90), and read anything
 * that is not a quarter turn as unrotated (17.11 SOL-R8).
 */
internal fun normalizedQuarterTurn(rotationDegrees: Int): Int {
    val normalised = ((rotationDegrees % 360) + 360) % 360
    return if (normalised == 90 || normalised == 180 || normalised == 270) normalised else 0
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
