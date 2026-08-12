enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        // KiteCodec and its Gradle plugin are not on Maven Central yet, so the FFmpeg backend
        // resolves them from a local publication. Host-only macOS work uses:
        //   cd ../KiteCodec && ./gradlew publishToMavenLocal -Pkitecodec.hostTargetsOnly=true
        // Mobile Apple work publishes the three local variants, then points phone links at the
        // generated no-network FFmpeg trees:
        //   cd ../KiteCodec && ./gradlew publishToMavenLocal -Pkitecodec.applePhoneTargetsOnly=true
        //   cd ../KitePlayer && ./gradlew \
        //     :kiteplayer-ffmpeg:linkDebugTestIosArm64 \
        //     :kiteplayer-ffmpeg:linkDebugTestIosSimulatorArm64 \
        //     -Pkitecodec.ffmpeg.localRoot="$PWD/../KiteCodec/native-libs" \
        //     --offline --refresh-dependencies --rerun-tasks
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

// :kiteplayer-rt is KitePlayer's real-time audio core, in C, with the symbol prefix `kprt_`. It
// publishes a declaration-free main klib required by the Kotlin publication model beside one
// callable surface: the `kitert` cinterop klib over `native/include/kite_rt.h`, plus the static
// archive that cinterop embeds per Kotlin/Native target. Its Kotlin wrapper lives in :kiteplayer-core,
// because that wrapper implements an internal interface of that module.
//
// It exists as its own module rather than inside KiteCodec because a lock-free audio ring has
// nothing to do with FFmpeg, and putting it there would make this player's real-time core a
// transitive consequence of a codec dependency. See KPKMP.md section 15.2, sub-phase B1.7.
include(":kiteplayer-rt")

// Modules added as their milestones land. Kept commented rather than absent so the intended
// module graph is visible from the build file.
include(":kiteplayer-ffmpeg")
include(":kiteplayer-output")
// include(":kiteplayer-libass")     // optional full ASS renderer
// include(":kiteplayer")            // umbrella artifact
// include(":kiteplayer-compose")    // Compose Multiplatform surface and controls
include(":kiteplayer-sample")
// The provisional Android assembly proof (S1.c.6). S1.d replaces its internals with the
// :kiteplayer-phone aggregate; the module itself stays.
include(":kiteplayer-sample-android")
