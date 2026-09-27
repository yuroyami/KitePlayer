package io.github.yuroyami.kiteplayer

/**
 * A device test that reads clips which `adb push` puts on the device first. The CI emulator job
 * pushes none, so it skips every class with this annotation.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class NeedsPushedMedia
