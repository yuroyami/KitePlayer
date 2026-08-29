enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        // mavenLocal is GONE from here, not made optional (SOL-B6). No plugin in this build comes
        // from the sibling repository: every id in the version catalog is JetBrains, Android or
        // vanniktech. It sat FIRST anyway, so any locally published artifact matching a plugin
        // coordinate would have quietly won over the real one, for no benefit at all.
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

/*
 * SOL-B6: mavenLocal is OPT-IN for dependencies, because when it is on it wins SILENTLY.
 *
 * RIGHT NOW mavenLocal is REQUIRED to build, and that is temporary. The sibling renamed itself to
 * KiteFFmpeg on 2026-08-29 and restarted its version line at 0.1.0, which Maven Central has never
 * served: Central carries the old `kitecodec-core` coordinates up to 0.1.3 and nothing under the
 * new name until the owner publishes. So until that publish, every build here needs the flag below
 * and resolves the locally published `kiteffmpeg-core:0.1.0`.
 *
 * Why it stays opt-in rather than becoming unconditional: when mavenLocal is on it is consulted
 * FIRST and it wins SILENTLY. The moment Central serves a version string this working tree also
 * builds, a local publication replaces Central's bytes with the working tree's under the same
 * name, and nothing in the build or the log tells them apart. A snapshot suffix would at least be
 * visible; an identical release version is not.
 *
 * Turn it on deliberately when developing the two repositories together, and it says so out loud:
 *   ./gradlew -Pkiteplayer.useMavenLocal=true <task>
 */
val useMavenLocal = providers.gradleProperty("kiteplayer.useMavenLocal").orNull == "true"

dependencyResolutionManagement {
    repositories {
        if (useMavenLocal) mavenLocal()
        google()
        mavenCentral()
    }
}

if (useMavenLocal) {
    println(
        "[KitePlayer] mavenLocal is ENABLED and is consulted FIRST, so a locally published " +
            "kiteffmpeg-core shadows the one Maven Central serves under the same version. " +
            "Drop -Pkiteplayer.useMavenLocal to resolve released artifacts only.",
    )
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

// :kiteplayer-subtitles parses subtitle formats. Pure Kotlin: SubRip, WebVTT and the ASS
// dialogue tier. Cue timing, layout and rasterisation live in the engine and :kiteplayer-output.
include(":kiteplayer-subtitles")

// :kiteplayer-rt is KitePlayer's real-time audio core, in C, with the symbol prefix `kprt_`. It
// publishes a declaration-free main klib required by the Kotlin publication model beside one
// callable surface: the `kitert` cinterop klib over `native/include/kite_rt.h`, plus the static
// archive that cinterop embeds per Kotlin/Native target. Its Kotlin wrapper lives in :kiteplayer-core,
// because that wrapper implements an internal interface of that module.
//
// It exists as its own module rather than inside KiteFFmpeg because a lock-free audio ring has
// nothing to do with FFmpeg, and putting it there would make this player's real-time core a
// transitive consequence of a codec dependency.
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
include(":kiteplayer-compose-ui")

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
// The Compose Desktop assembly and the KV-5 measurement harness (17.13 W-05). It is the only
// place where the desktop JVM backend, the desktop output half and KiteVideo all run at once,
// which is what makes the per-frame upload cost measurable instead of assumed.
include(":kiteplayer-sample-desktop")
// The KV-6 measurement harness (17.14 X-01), and nothing else. S6 is gated on one unmeasured
// number, the per-frame cost of converting and drawing a 1080p frame on wasm with ONE thread, and
// this module exists to produce it before any binding work is committed to.
include(":kiteplayer-sample-web")
