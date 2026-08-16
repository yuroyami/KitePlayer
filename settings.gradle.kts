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

// The mobile stack is split by capability. Native views remain Compose-free and work from Android
// XML/UIKit directly; mobile assembles the default FFmpeg/output/view stack; Compose interop only
// hosts those views; Compose video draws frames through Compose itself.
include(":kiteplayer-view")
include(":kiteplayer-mobile")
include(":kiteplayer-compose-interop")
include(":kiteplayer-compose-video")

// Source-compatibility umbrellas for the local 0.0.2 coordinates. New modules never depend on
// these leaves, so the old packaging cannot dictate the clean target matrices.
include(":kiteplayer-phone")
include(":kiteplayer-compose")
include(":kiteplayer-libass")        // optional full ASS renderer (phase L, owner-pulled 2026-08-16)
// Ktor byte suppliers and the Kotlin adaptive layer (17.12 M1's network half): https with the
// OS supplying TLS, and DASH manifests parsed in commonMain. Optional; pure Kotlin.
include(":kiteplayer-network")
// include(":kiteplayer")            // umbrella artifact
include(":kiteplayer-sample")
// The Android assembly and XML-inflation proof for :kiteplayer-mobile and :kiteplayer-view.
include(":kiteplayer-sample-android")
