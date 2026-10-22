// The Android device tests of the KitePlayer libraries and of the sample app. The Kotlin Toolchain has no instrumented
// test support, so this Gradle build runs them against the staged artifacts of this checkout.
// Run it through scripts/run-device-tests.sh, which stages the artifacts first.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "device-tests"

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
    repositories {
        // The KitePlayer artifacts come only from the staged copy of this checkout.
        exclusiveContent {
            forRepository {
                maven {
                    name = "verification"
                    url = uri(providers.gradleProperty("verificationRepository")
                        .orElse(rootDir.resolve("../../build/verification-maven").absolutePath).get())
                }
            }
            filter { includeModuleByRegex("io\\.github\\.yuroyami", "kiteplayer.*") }
        }
        google()
        mavenCentral()
    }
}

include(":io", ":output", ":ffmpeg", ":libass", ":compose-video", ":sample-android")
