import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

/*
 * The Compose Desktop proof for the upload measurement. One window, one player built by
 * KitePlayer(), and KiteVideo drawing its frames as ordinary Compose
 * content. The modifier toggle is the point of that screen: clip, alpha, rotation and scale apply
 * to the video pixels, which a platform-view player cannot do.
 *
 * A bare `run` opens on the audio visualiser instead, playing the sample songs; `--modifiers` or
 * the measurement flags bring back the video screen above.
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
            implementation(project(":kiteplayer-sample-shared"))
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

// The songs the visualiser offers: -Pkiteplayer.sample.song names one and only one, else
// kiteplayer.sample.song in the root local.properties, else every song in the shared media folder.
// The folder's absolute path is baked in, because the run task starts in this module's directory.
val sampleSong: String? = providers.gradleProperty("kiteplayer.sample.song")
    .orElse(
        providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.map { text ->
            Properties().apply { load(text.reader()) }.getProperty("kiteplayer.sample.song").orEmpty()
        },
    )
    .orNull?.takeIf { it.isNotBlank() }
val sampleSongFolder = rootProject.layout.projectDirectory.dir("kiteplayer-sample-shared/media").asFile.absolutePath

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
        jvmArgs += "-Dkiteplayer.sample.songs=$sampleSongFolder"
        if (sampleSong != null) jvmArgs += "-Dkiteplayer.sample.song=$sampleSong"
    }
}
