package io.github.yuroyami.kiteplayer.audioviz

/**
 * Marks the toolkit drawings are written with: the sound analysis, meshes, cameras, motion,
 * grounds, warps, the finishing pass, and the low-level surfaces that draw them.
 *
 * It is public so a drawing can be written outside this module, and it changes more often than the
 * parts an app uses to show one.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "The toolkit for writing drawings. It changes more often than the rest of the visualiser. " +
        "Opt in with @OptIn(AudioVizAuthoringApi::class) to write a drawing of your own.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPEALIAS)
public annotation class AudioVizAuthoringApi
