package io.github.yuroyami.kiteplayer

import dalvik.system.BaseDexClassLoader
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegMediaBackend
import io.github.yuroyami.kiteplayer.output.AndroidOutputBackend

internal actual val platformKitePlayerDefaults: KitePlayerPlatformDefaults =
    AndroidKitePlayerPlatformDefaults

private object AndroidKitePlayerPlatformDefaults : KitePlayerPlatformDefaults {
    override val availability: KitePlayerAvailability by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        androidKitePlayerAvailability(
            (KitePlayerPlatform::class.java.classLoader as? BaseDexClassLoader)
                ?.findLibrary(KITECODEC_JNI_LIBRARY),
        )
    }

    override val supportsPictureInPicture: Boolean
        get() = availability.isAvailable

    override fun backendsOrNull(): Backends? = if (availability.isAvailable) {
        Backends(
            backend = KiteFFmpegMediaBackend(),
            output = AndroidOutputBackend,
        )
    } else {
        null
    }
}

internal fun androidKitePlayerAvailability(jniPath: String?): KitePlayerAvailability =
    if (!jniPath.isNullOrBlank()) {
        KitePlayerAvailability.Available
    } else {
        KitePlayerAvailability.Unavailable(
            "KiteFFmpeg JNI is not packaged for this Android process ABI",
        )
    }

private const val KITECODEC_JNI_LIBRARY = "kitecodec_jni"
