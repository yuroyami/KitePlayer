package io.github.yuroyami.kiteplayer

/**
 * A device test that reads clips which `adb push` puts on the device first. The Gradle device-test
 * run skips every class with this annotation. The CI emulator job then pushes the clips and runs
 * every class with it in a step of its own.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class NeedsPushedMedia
