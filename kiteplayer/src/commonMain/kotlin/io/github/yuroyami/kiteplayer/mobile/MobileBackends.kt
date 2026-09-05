package io.github.yuroyami.kiteplayer.mobile

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayerPlatform

/**
 * The standard backend pair for the platform this code runs on.
 *
 * This is convenience, not policy. A consumer that wants a different pairing builds [Backends]
 * by hand exactly as before; nothing in this module requires coming through here. On an
 * unavailable placeholder target this returns an empty [Backends]. Prefer
 * [KitePlayerPlatform.createOrNull] when the target may not be playable; passing this empty value
 * to `KitePlayer.create` deliberately produces its typed configuration error.
 */
public fun mobileBackends(): Backends = KitePlayerPlatform.backendsOrNull() ?: Backends()
