@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeMoviePlayback
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSError

internal actual fun platformAppleAudioSessionController(): AppleAudioSessionController =
    IosAppleAudioSessionController

private object IosAppleAudioSessionController : AppleAudioSessionController {
    private val session: AVAudioSession get() = AVAudioSession.sharedInstance()

    override fun setPlaybackCategory() {
        call("configuring AVAudioSession for playback") { error ->
            session.setCategory(
                category = AVAudioSessionCategoryPlayback,
                mode = AVAudioSessionModeMoviePlayback,
                options = 0uL,
                error = error,
            )
        }
    }

    override fun setActive(active: Boolean, notifyOthers: Boolean) {
        val action = if (active) "activating" else "deactivating"
        call("$action AVAudioSession") { error ->
            if (notifyOthers) {
                session.setActive(
                    active = active,
                    withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                    error = error,
                )
            } else {
                session.setActive(active = active, error = error)
            }
        }
    }

    private inline fun call(
        action: String,
        operation: (kotlinx.cinterop.CPointer<ObjCObjectVar<NSError?>>) -> Boolean,
    ) {
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            error.value = null
            if (!operation(error.ptr)) {
                val detail = error.value?.localizedDescription
                throw IllegalStateException(
                    if (detail.isNullOrBlank()) "$action failed" else "$action failed: $detail",
                )
            }
        }
    }
}
