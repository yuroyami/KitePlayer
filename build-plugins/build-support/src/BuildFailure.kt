/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

/** A build step that cannot go on. The message says what is wrong and how to fix it. */
class BuildFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
