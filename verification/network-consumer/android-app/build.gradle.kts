plugins {
    alias(libs.plugins.android.application)
}

val stagedVersion = providers.gradleProperty("kiteplayerVersion").get()
val withNetwork = providers.gradleProperty("withNetwork").orElse("false").map { it.toBoolean() }
layout.buildDirectory.set(rootProject.layout.projectDirectory.dir(
    "build/" + (if (withNetwork.get()) "with-network" else "without-network") + "/android-app",
))

android {
    namespace = "io.github.yuroyami.kiteplayer.verification.network"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.yuroyami.kiteplayer.verification.network"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "probe"
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            // A local install key does not disable R8; debuggable remains false.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    // AGP 9 tracks Kotlin directories separately from its Java-only source filter.
    sourceSets.getByName("main").kotlin.srcDir(rootProject.layout.projectDirectory.dir("src/commonMain/kotlin"))
}

dependencies {
    // The exact same core-only source is shrunk with and without the optional artifact.
    implementation("io.github.yuroyami:kiteplayer-core:$stagedVersion")
    if (withNetwork.get()) {
        implementation("io.github.yuroyami:kiteplayer-network:$stagedVersion")
    }
}
