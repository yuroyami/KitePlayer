plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

/*
 * The one screen the desktop, Android and iOS samples share: a song playing on repeat, drawn by the
 * audio visualiser, with the drawing browser, the settings and simple transport controls over it.
 * Video still shows as video. Each sample hosts the screen and owns its player.
 *
 * An application part, not a library: no explicitApi, no ABI dump, nothing published.
 */
kotlin {
    jvmToolchain(21)

    jvm()
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "io.github.yuroyami.kiteplayer.sample.shared"
        compileSdk = 37
        minSdk = 26
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kiteplayer-compose"))
            api(project(":kiteplayer-audioviz"))
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(compose.foundation)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
