@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.AudioContent
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionModeMoviePlayback
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.maximumOutputNumberOfChannels
import platform.AVFAudio.setActive
import platform.AVFAudio.setPreferredOutputNumberOfChannels
import platform.AVFAudio.setSupportsMultichannelContent
import platform.Foundation.NSError

internal actual fun platformAppleAudioSessionController(): AppleAudioSessionController =
    IosAppleAudioSessionController

private object IosAppleAudioSessionController : AppleAudioSessionController {
    private val session: AVAudioSession get() = AVAudioSession.sharedInstance()

    override fun setPlaybackCategory(content: AudioContent, mixesWithOthers: Boolean) {
        call("configuring AVAudioSession for playback") { error ->
            session.setCategory(
                category = AVAudioSessionCategoryPlayback,
                mode = when (content) {
                    AudioContent.Music -> AVAudioSessionModeDefault
                    AudioContent.Speech -> AVAudioSessionModeSpokenAudio
                    AudioContent.Movie, AudioContent.Automatic -> AVAudioSessionModeMoviePlayback
                },
                options = if (mixesWithOthers) AVAudioSessionCategoryOptionMixWithOthers else 0uL,
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

    override fun setMultichannelContent(offered: Boolean) {
        call("declaring multichannel content to AVAudioSession") { error ->
            session.setSupportsMultichannelContent(offered, error = error)
        }
    }

    override fun maximumOutputChannels(): Int = session.maximumOutputNumberOfChannels.toInt()

    override fun preferOutputChannels(channels: Int) {
        call("asking AVAudioSession for output channels") { error ->
            session.setPreferredOutputNumberOfChannels(channels.toLong(), error = error)
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
