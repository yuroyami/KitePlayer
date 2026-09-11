plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kiteplayer-audioviz draws what the player is playing when there is no picture: seventy eight
 * drawings in eleven families, a director that changes them on the song's phrases, palettes, and
 * each drawing's recipe. Its analyser takes plain samples, so it needs nothing from a decoder or an
 * audio device.
 *
 * The desktop and iOS draw through Skia, so their runtime shaders, meshes and pixel images are
 * written once, in the skiko source set. Android has its own classes for the same jobs.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {}

    // ShaderProgram is an expect class; the flag only silences the beta note the compiler still prints for that shape.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("skiko") {
                withJvm()
                withIos()
            }
        }
    }

    iosArm64()
    iosSimulatorArm64()
    jvm()

    android {
        namespace = "io.github.yuroyami.kiteplayer.audioviz"
        compileSdk = 37
        minSdk = 26
    }

    sourceSets {
        all {
            // The drawing toolkit is public for authors outside this module; inside it, it is simply the code.
            languageSettings.optIn("io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi")
        }
        commonMain.dependencies {
            api(project(":kiteplayer-core"))
            api(compose.runtime)
            api(compose.ui)
            implementation(compose.foundation)
            implementation(libs.kite3d)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            // Skia's native library, which is what actually rasterises in these tests.
            implementation(compose.desktop.currentOs)
            // The default desktop player, for the probe that times the analysis against real playback.
            implementation(project(":kiteplayer"))
        }
    }
}

/*
 * The rendering suites draw every drawing for seconds of two test songs and measure the pixels. They
 * take about twenty five minutes, so the gate runs everything else and these run on their own:
 * ./gradlew :kiteplayer-audioviz:audiovizSurvey
 */
val renderingSuites = listOf(
    "*.DensityTest",
    "*.ScreenMotionTest",
    "*.ChangeTest",
    "*.MoodRenderTest",
    "*.DrawCostTest",
    "*.RecipeTest",
    "*.ContactSheetTest",
    "*.FamilySheetTest",
    "*.PostSheetTest",
)

val jvmTest = tasks.named<Test>("jvmTest") {
    filter { renderingSuites.forEach { excludeTestsMatching(it) } }
}

tasks.register<Test>("audiovizSurvey") {
    group = "verification"
    description = "Runs the audio visualiser's rendering suites: motion, density, change, mood, cost and the contact sheets."
    testClassesDirs = files(jvmTest.map { it.testClassesDirs })
    classpath = files(jvmTest.map { it.classpath })
    filter { renderingSuites.forEach { includeTestsMatching(it) } }
    // Every run renders for real; a cached pass would prove nothing about the drawings.
    outputs.upToDateWhen { false }
}
