// The device tests of kiteplayer-libass, built as in its own Gradle build before the move: the shared test
// folders and android-device-test compile into one test APK, against the staged artifacts.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

val kiteplayerVersion = providers.gradleProperty("kiteplayerVersion").get()
val module = rootProject.layout.projectDirectory.dir("../../kiteplayer-libass")

// The tests use internal declarations of kiteplayer-libass, as they did inside its own build. The compiler
// sees them through the jar inside the staged Android archive, passed as a friend path.
val friendDir = layout.buildDirectory.dir("friend")
val friendClasses = tasks.register<Copy>("friendClasses") {
    val repository = providers.gradleProperty("verificationRepository").get()
    fileTree(repository) { include("io/github/yuroyami/kiteplayer-libass-android/$kiteplayerVersion/*.aar") }
        .forEach { from(zipTree(it)) { include("libs/*.jar"); eachFile { path = "classes.jar" } } }
    into(friendDir)
}
val friendJar = friendDir.map { it.file("classes.jar").asFile }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(friendClasses)
    compilerOptions.freeCompilerArgs.add(friendJar.map { "-Xfriend-paths=$it" })
}

kotlin {
    // As in the library builds: expect classes in the shared test folders.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    android {
        namespace = "io.github.yuroyami.kiteplayer.libass.devicetests"
        // The package of the test APK. CI pushes the clips into the storage of this package.
        // AGP 9.4 ignores applicationId in withDeviceTestBuilder and takes the package from here.
        testNamespace = "io.github.yuroyami.kiteplayer.libass.test"
        compileSdk { version = release(37) { minorApiLevel = 2 } }
        minSdk = 26
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }
    sourceSets {
        all { languageSettings.optIn("io.github.yuroyami.kiteplayer.KitePlayerInternalApi") }
        commonTest {
            kotlin.srcDir(module.dir("test"))
            dependencies {
                implementation("io.github.yuroyami:kiteplayer-libass:$kiteplayerVersion")
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        getByName("androidDeviceTest") {
            kotlin.srcDir(module.dir("android-device-test/kotlin"))
            dependencies {
                compileOnly(files(friendJar).builtBy(friendClasses))
                implementation(libs.androidx.test.core)
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.ext.junit)
            }
        }
    }
}
