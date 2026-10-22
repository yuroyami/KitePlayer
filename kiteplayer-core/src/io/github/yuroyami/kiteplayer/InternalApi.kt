package io.github.yuroyami.kiteplayer

/**
 * Marks declarations that are public only so that KitePlayer's own modules can share them.
 *
 * KitePlayer's modules are released together, at one version, so a declaration that one of them
 * uses from another can change or go in any release, without notice and without a deprecation.
 * Nothing behind this annotation is meant for an application. The bounded XML reader that the
 * subtitle and network modules share is the first (#492).
 *
 * A KitePlayer module that uses one opts in once, in its build file, for all of its source sets.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Shared between KitePlayer's own modules, which ship together, with no compatibility " +
        "promise to anyone else. It can change in any release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPEALIAS)
public annotation class KitePlayerInternalApi
