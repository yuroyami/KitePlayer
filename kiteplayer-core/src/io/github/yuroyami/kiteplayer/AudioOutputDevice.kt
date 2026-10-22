package io.github.yuroyami.kiteplayer

/**
 * An audio output device that a player can play through.
 *
 * The desktop JVM and Apple output backends list these and can bind a player to one. On Android
 * the operating system owns the route, so there is no list to choose from there.
 *
 * @property id names the device to the backend that listed it. It stays the same across runs where
 *     the platform allows: the desktop JVM uses the mixer name and a Mac uses CoreAudio's device UID.
 * @property name the name to show a person.
 * @property isDefault true for the device the system plays through when nothing is chosen.
 */
public data class AudioOutputDevice(
    val id: String,
    val name: String,
    val isDefault: Boolean,
)
