enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        // KiteCodec and its Gradle plugin are not on Maven Central yet, so the FFmpeg backend
        // resolves them from a local publication. Run this in the KiteCodec checkout first:
        //   ./gradlew publishToMavenLocal -Pkitecodec.hostTargetsOnly=true
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "KitePlayer-KMP"

// :kiteplayer-core is the engine. Pure Kotlin, coroutines and atomicfu only. It holds every
// playback decision: the player class, the session loop, the clock, sync, queues, buffering,
// the seek state machine and track selection. It calls no platform API and holds one internal
// expect declaration, the single-thread dispatchers its workers are confined to, so it
// compiles for every target it declares and is fully testable with a virtual clock.
include(":kiteplayer-core")

// :kiteplayer-subtitles parses subtitle formats. Pure Kotlin. Today that is SubRip and nothing
// else: no cue is timed, laid out or drawn, and the player does not read a subtitle track. Cue
// layout and rasterisation are in KPKMP.md section 11.
include(":kiteplayer-subtitles")

// Modules added as their milestones land. Kept commented rather than absent so the intended
// module graph is visible from the build file.
include(":kiteplayer-ffmpeg")
include(":kiteplayer-output")
// include(":kiteplayer-libass")     // optional full ASS renderer
// include(":kiteplayer")            // umbrella artifact
// include(":kiteplayer-compose")    // Compose Multiplatform surface and controls
include(":kiteplayer-sample")
