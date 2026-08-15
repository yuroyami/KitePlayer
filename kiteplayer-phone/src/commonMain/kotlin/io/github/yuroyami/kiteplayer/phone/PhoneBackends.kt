package io.github.yuroyami.kiteplayer.phone

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.mobile.mobileBackends

/** Use [mobileBackends] from `kiteplayer-mobile`. */
@Deprecated("Use mobileBackends() from kiteplayer-mobile", ReplaceWith("mobileBackends()", "io.github.yuroyami.kiteplayer.mobile.mobileBackends"))
public fun phoneBackends(): Backends = mobileBackends()
