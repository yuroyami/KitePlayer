package io.github.yuroyami.kiteplayer

/**
 * Whether the picture is dimmed while it flashes more than three times in a second over a large
 * part of it (#500). See [KitePlayer.setFlashGuard] and `docs/video-flash-guard.md`.
 */
public enum class FlashGuard {
    /** Never dimmed. */
    Off,

    /** Dimmed while a flashing run lasts, whatever the platform's setting says. */
    On,

    /**
     * As the platform's own setting says, which is Apple's Dim Flashing Lights, and as [Off] on a
     * platform with no such setting.
     */
    FollowSystem,
}
