pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

rootProject.name = "network-consumer"

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
    repositories {
        // Never let Central or Maven Local replace the exact staged bytes under the same version.
        exclusiveContent {
            forRepository {
                maven {
                    name = "verification"
                    url = uri(providers.gradleProperty("verificationRepository")
                        .orElse(rootDir.resolve("../../build/verification-maven").absolutePath).get())
                }
            }
            filter { includeGroup("io.github.yuroyami") }
        }
        mavenCentral()
        google()
    }
}

// Android is opt-in so JVM/Native/Web distribution probes need no Android SDK configuration.
if (providers.gradleProperty("withAndroid").orElse("false").get().toBoolean()) {
    include(":android-app")
}
