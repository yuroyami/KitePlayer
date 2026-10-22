package io.github.yuroyami.kiteplayer

/**
 * A device test that reads clips which `adb push` puts on the device first. The Gradle device-test
 * run skips every class with this annotation. The CI emulator job then pushes the clips and runs
 * these classes in a step of its own. That step names each class of this module, so a new class
 * must be added to it in `ci.yml`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class NeedsPushedMedia
