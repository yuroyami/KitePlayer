// The sample app, built by Gradle from the same sources as kiteplayer-sample-android and
// kiteplayer-sample-shared, so its instrumented tests have an app to run in. The Kotlin Toolchain
// builds no instrumented test APK for an app. The assets are the ones that the toolchain's
// sample-media plugin wrote into kiteplayer-sample-android/assets.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val kiteplayerVersion = providers.gradleProperty("kiteplayerVersion").get()
val repo = rootProject.layout.projectDirectory.dir("../..")
val app = repo.dir("kiteplayer-sample-android")

android {
    namespace = "io.github.yuroyami.kiteplayer.sample.android"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "io.github.yuroyami.kiteplayer.sample.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // The DSL type, as in the library modules: its source sets have the `directories` sets.
    val dslSourceSets = (this as com.android.build.api.dsl.ApplicationExtension).sourceSets
    dslSourceSets.getByName("main") {
        manifest.srcFile(app.file("src/AndroidManifest.xml"))
        kotlin.directories += app.dir("src").asFile.path
        kotlin.directories += repo.dir("kiteplayer-sample-shared/src").asFile.path
        res.directories += app.dir("res").asFile.path
        assets.directories += app.dir("assets").asFile.path
    }
    dslSourceSets.getByName("androidTest") {
        kotlin.directories += app.dir("android-device-test/kotlin").asFile.path
    }
    packaging { jniLibs { useLegacyPackaging = false } }
}

// The instrumented tests carry a rotated clip of their own, so picture in picture can be checked
// with a portrait picture. It stays out of the app.
abstract class TestClip : DefaultTask() {
    @get:InputFile abstract val clip: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        clip.get().asFile.copyTo(outputDirectory.get().file(clip.get().asFile.name).asFile, overwrite = true)
    }
}
val rotatedTestClip = tasks.register<TestClip>("rotatedTestClip") {
    clip.set(repo.file("testmedia/rotated90ccw.mp4"))
    outputDirectory.set(layout.buildDirectory.dir("generated/testClipAssets"))
}
androidComponents.onVariants { variant ->
    variant.deviceTests.values.forEach { deviceTest ->
        deviceTest.sources.assets?.addGeneratedSourceDirectory(rotatedTestClip, TestClip::outputDirectory)
    }
}

dependencies {
    implementation("io.github.yuroyami:kiteplayer-compose:$kiteplayerVersion")
    implementation("io.github.yuroyami:kiteplayer-compose-interop:$kiteplayerVersion")
    implementation("io.github.yuroyami:kiteplayer-compose-video:$kiteplayerVersion")
    implementation("io.github.yuroyami:kiteplayer-audioviz:$kiteplayerVersion")
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
