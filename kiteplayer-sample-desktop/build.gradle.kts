plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

/*
 * The Compose Desktop proof for the upload measurement. One window, one player built by
 * KitePlayerPlatform.createOrNull(), and KiteVideo drawing its frames as ordinary Compose
 * content. The modifier toggle is the whole point of D-6: clip, alpha, rotation and scale apply
 * to the video pixels, which a platform-view player cannot do.
 *
 * An application, not a library: no explicitApi, no ABI dump, nothing published.
 */
kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            // One dependency supplies the standard player and both presentation paths.
            implementation(project(":kiteplayer-compose"))
            implementation(compose.desktop.currentOs)
            implementation(compose.foundation)
        }
    }
}

// The default clip lives at the repository root, which a launched process cannot guess, so its
// absolute path is baked in here. A path argument still wins over it.
val defaultMedia = rootProject.layout.projectDirectory
    .file("testmedia/sync1080p30.mp4").asFile.absolutePath

// Measurement knobs, passed as Gradle properties so `run` needs no --args support:
//   ./gradlew :kiteplayer-sample-desktop:run -Pkiteplayer.sample.measure -Pkiteplayer.sample.frames=320
val measureFlags = listOf(
    "kiteplayer.sample.media",
    "kiteplayer.sample.measure",
    "kiteplayer.sample.frames",
    "kiteplayer.sample.repeats",
    "kiteplayer.sample.report",
).mapNotNull { key ->
    providers.gradleProperty(key).orNull?.let { value -> "-D$key=$value" }
}

// The run classpath, printed so the measurement can be repeated with no Gradle daemon in the
// picture: `java -cp "$(./gradlew -q :kiteplayer-sample-desktop:printRunClasspath)" ... MainKt`.
val runFiles = kotlin.jvm().compilations.getByName("main")
    .let { main -> main.output.allOutputs + main.runtimeDependencyFiles }

tasks.register("printRunClasspath") {
    dependsOn("jvmMainClasses")
    val classpath = runFiles
    doLast { println(classpath.joinToString(File.pathSeparator) { it.absolutePath }) }
}

compose.desktop {
    application {
        mainClass = "io.github.yuroyami.kiteplayer.sample.desktop.MainKt"
        jvmArgs += "-Dkiteplayer.sample.media.default=$defaultMedia"
        jvmArgs += measureFlags
    }
}
