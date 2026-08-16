package io.github.yuroyami.kiteplayer.internal

/**
 * Reads a LOCAL text file whole, or answers null when it cannot (S4.e: external subtitle files).
 *
 * Local means a filesystem path. Network fetching is parked with KPKMP 17.8, and the browser
 * targets have no filesystem, so their actuals answer null and the caller warns typed instead of
 * pretending. UTF-8 with an optional BOM, which is what every subtitle file in the wild is.
 */
internal expect fun readExternalTextOrNull(path: String): String?
