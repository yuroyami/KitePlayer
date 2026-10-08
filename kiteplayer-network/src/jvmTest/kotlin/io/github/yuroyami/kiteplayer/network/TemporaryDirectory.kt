package io.github.yuroyami.kiteplayer.network

import java.nio.file.Files

internal actual fun temporaryDirectory(): String = Files.createTempDirectory("kite-segments").toString()
