package io.github.yuroyami.kiteplayer.phone

import io.github.yuroyami.kiteplayer.Backends

/**
 * The standard backend pair for the platform this code runs on: the FFmpeg media backend and the
 * platform output backend.
 *
 * This is convenience, not policy. A consumer that wants a different pairing builds [Backends]
 * by hand exactly as before; nothing in this module requires coming through here.
 */
public expect fun phoneBackends(): Backends
