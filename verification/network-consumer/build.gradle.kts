import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    // AGP and Kotlin must share the root plugin classloader. Loading AGP only in the app leaves
    // Kotlin's Android target unable to see AGP's BaseVariant when the built-in Kotlin setup runs.
    alias(libs.plugins.android.application).apply(false)
    alias(libs.plugins.kotlin.multiplatform)
}

val stagedVersion = providers.gradleProperty("kiteplayerVersion").get()
val withNetwork = providers.gradleProperty("withNetwork").orElse("false").map { it.toBoolean() }

// Separate outputs prevent a dependency toggle from reusing another consumer's optimized binary.
layout.buildDirectory.set(layout.projectDirectory.dir(
    if (withNetwork.get()) "build/with-network" else "build/without-network",
))

kotlin {
    jvmToolchain(21)
    jvm()
    macosArm64 {
        binaries.executable {
            baseName = "network-probe"
            entryPoint = "probe.main"
        }
    }
    iosSimulatorArm64 {
        binaries.executable {
            baseName = "network-probe"
            entryPoint = "probe.main"
        }
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation("io.github.yuroyami:kiteplayer-core:$stagedVersion")
            if (withNetwork.get()) {
                implementation("io.github.yuroyami:kiteplayer-network:$stagedVersion")
            }
        }
    }
}

// JVM remains an ordinary external consumer. No project dependency or provider reference is used.
val jvmMain = kotlin.jvm().compilations.getByName("main")
tasks.register<JavaExec>("runJvmProbe") {
    dependsOn("jvmMainClasses")
    classpath = jvmMain.output.allOutputs + jvmMain.runtimeDependencyFiles
    mainClass.set("probe.MainKt")
    args(
        providers.gradleProperty("probeUrl").orElse("http://localhost:8765/media").get(),
        providers.gradleProperty("expectNetwork").orElse("true").get(),
    )
}
