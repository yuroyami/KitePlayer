/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.buildplugins.klibabi

import org.jetbrains.amper.plugins.Configurable

@Configurable
interface KlibAbiCheckSettings {
    /**
     * Platforms of the merged klib ABI dump, such as `iosArm64`. The check reads the klibs that
     * `./kotlin build` wrote, so it needs a build of each platform first.
     */
    val klibPlatforms: List<String>

    /** Directory of the committed klib ABI dump, relative to the module directory. The file is `<module>.klib.api`. */
    val klibDumpDir: String get() = "api"
}
