package io.github.yuroyami.kiteplayer.network

import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

internal actual fun temporaryDirectory(): String = NSTemporaryDirectory().trimEnd('/') + "/kite-segments-" + NSUUID().UUIDString
