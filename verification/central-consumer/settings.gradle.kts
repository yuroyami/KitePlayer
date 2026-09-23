// A consumer that knows nothing about this checkout. It builds the README's install lines from
// Maven Central and Google's repository alone. scripts/verify-central-consumer.sh runs it.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

rootProject.name = "central-consumer"

dependencyResolutionManagement {
    // Only the plugin versions come from the checkout, so the consumer builds with the same Kotlin
    // and Android Gradle plugin as the player. No repository here can serve this checkout's bytes.
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
    repositories {
        mavenCentral()
        google()
    }
}
