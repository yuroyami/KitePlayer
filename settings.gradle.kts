enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "KitePlayer-KMP"

// :kiteplayer-core is the engine. Pure Kotlin, coroutines and atomicfu only. It holds every
// playback decision: the clock, sync, queues, buffering, the seek state machine, track
// selection and subtitle timing. It has no platform dependency and no expect declaration, so
// it compiles for every target Kotlin supports and is fully testable with a virtual clock.
include(":kiteplayer-core")

// :kiteplayer-subtitles parses subtitle formats and lays cues out. Pure Kotlin. Glyph
// rasterisation is delegated to the platform through a TextRasterizer, so no font engine
// ships here.
include(":kiteplayer-subtitles")

// Modules added as their milestones land. Kept commented rather than absent so the intended
// module graph is visible from the build file.
// include(":kiteplayer-ffmpeg")     // KiteCodec-backed source and decoders
// include(":kiteplayer-output")     // audio sinks and video renderers, per platform
// include(":kiteplayer-libass")     // optional full ASS renderer
// include(":kiteplayer")            // umbrella artifact
// include(":kiteplayer-compose")    // Compose Multiplatform surface and controls
// include(":kiteplayer-sample")     // CLI and Compose demo
