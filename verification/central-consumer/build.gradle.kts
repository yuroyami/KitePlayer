plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// The coordinates of the README's install lines. The script reads them from the README and passes
// them in, so the consumer and the README cannot drift apart.
val installLines = providers.gradleProperty("installLines").get().split(' ').filter { it.isNotBlank() }

kotlin {
    jvmToolchain(21)
    jvm()
    android {
        namespace = "io.github.yuroyami.kiteplayer.verification.central"
        compileSdk = 36
        minSdk = 26
    }
    sourceSets {
        commonMain.dependencies {
            installLines.forEach { implementation(it) }
        }
    }
}

// Prints whether the player's backend loads on this machine, from the resolved artifacts alone.
val jvmMain = kotlin.jvm().compilations.getByName("main")
tasks.register<JavaExec>("runJvmProbe") {
    dependsOn("jvmMainClasses")
    classpath = jvmMain.output.allOutputs + jvmMain.runtimeDependencyFiles
    mainClass.set("consumer.MainKt")
}
