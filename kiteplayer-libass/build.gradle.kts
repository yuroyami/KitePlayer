import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import io.github.yuroyami.kiteplayer.buildtools.BuildLibassHostJniTask
import io.github.yuroyami.kiteplayer.buildtools.FetchAssChainTask
import io.github.yuroyami.kiteplayer.buildtools.MergeAssChainTask
import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kiteplayer-libass is the typesetting engine: libass and its chain (HarfBuzz, FreeType, FriBidi)
 * behind the engine's SubtitleTypesetter interface. Adding it installs a provider the core
 * discovers, and every ASS and SSA track is then typeset instead of drawn by the Kotlin dialogue
 * tier. The standard entry points (:kiteplayer, :kiteplayer-compose, :kiteplayer-mobile) include
 * it, so most applications never name this module.
 *
 * One C driver, three bindings. native/src/kite_ass.h owns every libass call and the packed-buffer
 * conversion; Kotlin/Native includes it through cinterop, Android and the desktop JVM through the
 * JNI adapter beside it, and the web through the export table linked into kiteass.mjs (see the web
 * module section below). Only the js variant carries no engine, by design: it is the unavailable
 * facade that lets the standard entry points keep one dependency graph on every target.
 *
 * THE CHAIN. Cross-built in the sibling repository as static archives, one install per target.
 * A build finds a target's chain in this order, and says which it used:
 *   1. -Pkiteplayer.libass.root=<dir>, a KiteFFmpeg native-libs/deps tree.
 *   2. The sibling checkout, ../KiteFFmpeg/native-libs/deps, which the maintainer's machine has.
 *   3. A download from the KiteFFmpeg release named below, pinned by SHA-256 in ass-chain.sha256.
 * The four archives are merged into ONE libkiteass.a per target and embedded in the klib, so a
 * consumer links without knowing the chain exists. The Android AAR carries one adapter .so per ABI
 * whose chain exists; the jvm jar carries one adapter per desktop host whose toolchain this
 * machine has (see hostJniTriples below).
 */

/** The KiteFFmpeg release whose assets are `ass-chain-<target>.zip`; see FetchAssChainTask. */
val assChainReleaseTag = "ass-chain-r1"

val chainRootProperty: File? = providers.gradleProperty("kiteplayer.libass.root")
    .map { File(it).absoluteFile.normalize() }
    .orNull
val siblingChainRoot: File = rootDir.resolve("../KiteFFmpeg/native-libs/deps").normalize()

/** `<target dir name>` to the pinned SHA-256 of its release asset. Missing pin means "no download". */
val chainPins: Map<String, String> = file("ass-chain.sha256").takeIf { it.isFile }?.readLines()
    .orEmpty()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .associate { line ->
        val (sha, asset) = line.split(Regex("\\s+"), limit = 2)
        asset.removePrefix("ass-chain-").removeSuffix(".zip") to sha
    }

val konanDataDirProvider = providers.environmentVariable("KONAN_DATA_DIR")
    .orElse(providers.systemProperty("user.home").map { home -> "$home/.konan" })
    .map { path -> File(path) }

fun gradleSuffix(dirName: String): String =
    dirName.split('-').joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }

/** One target's chain: where its headers and archives are, and the task that puts them there. */
class Chain(val dirName: String, val root: File, val producer: TaskProvider<*>?) {
    val includeDir: File get() = root.resolve("include")
    val libDir: File get() = root.resolve("lib")
}

val chains = mutableMapOf<String, Chain?>()

/** The chain for one target directory name, or null when nothing can supply it. */
fun chainFor(dirName: String): Chain? = chains.getOrPut(dirName) {
    val local = listOfNotNull(chainRootProperty, siblingChainRoot.takeIf { it.isDirectory })
        .map { it.resolve("$dirName/ass-chain") }
        .firstOrNull { it.resolve("lib/libass.a").isFile }
    if (local != null) {
        logger.info("[kiteplayer-libass] $dirName: ass chain at $local")
        return@getOrPut Chain(dirName, local, null)
    }
    val pin = chainPins[dirName]
    if (pin == null) {
        logger.lifecycle(
            "[kiteplayer-libass] $dirName: no ass chain locally and no pin in ass-chain.sha256, so this " +
                "target is skipped. Build it with KiteFFmpeg's :kiteffmpeg:buildAssChainFor${gradleSuffix(dirName)}.",
        )
        return@getOrPut null
    }
    val fetched = layout.buildDirectory.dir("ass-chain/$dirName").get().asFile
    val fetch = tasks.register<FetchAssChainTask>("fetchAssChain${gradleSuffix(dirName)}") {
        targetDirName.set(dirName)
        releaseTag.set(assChainReleaseTag)
        expectedSha256.set(pin)
        outputDir.set(fetched)
    }
    Chain(dirName, fetched, fetch)
}

val merges = mutableMapOf<String, TaskProvider<MergeAssChainTask>>()

/** The merged single-archive task for one konan target, shared between cinterop and the JVM adapter. */
fun mergeFor(konanTargetName: String, chain: Chain): TaskProvider<MergeAssChainTask> = merges.getOrPut(konanTargetName) {
    tasks.register<MergeAssChainTask>("mergeAssChain${gradleSuffix(konanTargetName.replace('_', '-'))}") {
        chainLibDir.set(chain.libDir)
        chain.producer?.let { dependsOn(it) }
        this.konanTargetName.set(konanTargetName)
        konanDataDir.fileProvider(konanDataDirProvider)
        outputDir.set(layout.buildDirectory.dir("ass-chain-merged/$konanTargetName"))
    }
}

/** Konan target name to the chain directory the sibling builds for it. */
val nativeChainDirs = mapOf(
    "macos_arm64" to "macos-arm64",
    "ios_arm64" to "ios-arm64",
    "ios_simulator_arm64" to "ios-simulator-arm64",
    "linux_x64" to "linux-x64",
    "linux_arm64" to "linux-arm64",
    "mingw_x64" to "mingw-x64",
)

/**
 * The Android NDK, for the JNI adapter. local.properties is consulted as well as the environment,
 * because that is where this project records its SDK and the NDK lives inside it.
 */
fun resolveNdk(): File? {
    sequenceOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK_LATEST_HOME")
        .mapNotNull(System::getenv)
        .map(::File)
        .firstOrNull { it.isDirectory }
        ?.let { return it }
    val fromLocalProperties: File? = rootProject.file("local.properties")
        .takeIf { it.isFile }
        ?.let { file ->
            val loaded = Properties()
            file.inputStream().use { stream -> loaded.load(stream) }
            loaded.getProperty("sdk.dir")?.let { File(it) }
        }
    val sdkDirs: List<File> = listOfNotNull(
        fromLocalProperties,
        File(System.getProperty("user.home"), "Library/Android/sdk"),
        File(System.getProperty("user.home"), "Android/Sdk"),
    )
    return sdkDirs.map { it.resolve("ndk") }.firstOrNull { it.isDirectory }
        ?.listFiles { f: File -> f.isDirectory }?.maxByOrNull { it.name }
}

// The media fixtures live at the repo root; a test's working directory is not something to rely on.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    environment("KITEPLAYER_TESTMEDIA", rootDir.resolve("testmedia").absolutePath)
    environment("SIMCTL_CHILD_KITEPLAYER_TESTMEDIA", rootDir.resolve("testmedia").absolutePath)
}
tasks.withType<Test>().configureEach {
    environment("KITEPLAYER_TESTMEDIA", rootDir.resolve("testmedia").absolutePath)
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    applyDefaultHierarchyTemplate()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // Declaring the block is what switches tracking on.
    }

    // The engine is an expect class with one actual per binding; the flag only silences the
    // beta note the compiler still prints for that shape.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    // Every target the standard entry point has, plus the desktop Kotlin/Native triples the FFmpeg
    // backend has. A dependency edge that resolves for only some of a consumer's targets fails at
    // whichever target nobody compiled, so the list here is not optional per machine: a target whose
    // chain cannot be found fails its native tasks with the three ways to supply it.
    macosArm64()
    iosArm64()
    iosSimulatorArm64()
    linuxX64()
    linuxArm64()
    mingwX64()
    jvm()
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js {
        browser()
        nodejs()
        binaries.library()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }
    android {
        namespace = "io.github.yuroyami.kiteplayer.libass"
        compileSdk = 36
        minSdk = 26
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.file("consumer-rules.pro")
        }
    }

    targets.withType(KotlinNativeTarget::class.java).configureEach {
        val konanName = konanTarget.name
        val dirName = nativeChainDirs[konanName] ?: error("no ass chain directory is known for $konanName")
        val chain = chainFor(dirName)
        val merge = chain?.let { mergeFor(konanName, it) }
        val mergedDir = layout.buildDirectory.dir("ass-chain-merged/$konanName").get().asFile
        compilations.getByName("main").cinterops.create("libass") {
            defFile(project.file("src/nativeInterop/cinterop/libass.def"))
            val driverDir = project.file("native/src")
            if (chain != null) {
                includeDirs(chain.includeDir, driverDir)
                compilerOpts("-I${chain.includeDir.absolutePath}", "-I${driverDir.absolutePath}")
            } else {
                includeDirs(driverDir)
                compilerOpts("-I${driverDir.absolutePath}")
            }
            extraOpts("-libraryPath", mergedDir.absolutePath)
        }
        /*
         * cinterop embeds the merged archive, so it has to exist first AND be a declared input of the
         * cinterop task: its own up-to-date check covers the def and the headers, not a library the
         * def merely names, and a rebuilt chain would otherwise stay stale inside the klib.
         */
        val cinteropTaskName = "cinteropLibass${name.replaceFirstChar { it.uppercaseChar() }}"
        tasks.matching { it.name == cinteropTaskName }.configureEach {
            if (merge != null) {
                dependsOn(merge)
                inputs.files(merge.map { m -> m.outputDir.file(MergeAssChainTask.ARCHIVE_NAME) })
                    .withPropertyName("kiteAssArchive")
                    .withPathSensitivity(PathSensitivity.NAME_ONLY)
            } else {
                doFirst {
                    throw GradleException(
                        "No ass chain for $dirName. Point -Pkiteplayer.libass.root at a KiteFFmpeg " +
                            "native-libs/deps tree, keep the sibling checkout beside this one, or pin the " +
                            "release asset in kiteplayer-libass/ass-chain.sha256.",
                    )
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kiteplayer-core"))
        }
        // Android and the desktop JVM share the JNI half; only the loader differs.
        val jvmAndAndroidMain = maybeCreate("jvmAndAndroidMain").apply { dependsOn(getByName("commonMain")) }
        getByName("jvmMain").dependsOn(jvmAndAndroidMain)
        getByName("androidMain").dependsOn(jvmAndAndroidMain)
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // The end-to-end proof on the two hosts that can run it: real FFmpeg demuxes a real ASS
        // track, the engine routes it here, and the streamed path is compared against whole
        // documents. Test-only, so the shipped module stays backend-free. Shared between the
        // macOS native and JVM runs because both have runBlocking and a filesystem.
        val hostRenderTest = maybeCreate("hostRenderTest").apply {
            dependsOn(getByName("commonTest"))
            dependencies {
                implementation(project(":kiteplayer-ffmpeg"))
                implementation(project(":kiteplayer-output"))
                implementation(libs.kotlinx.atomicfu)
            }
        }
        getByName("macosArm64Test").dependsOn(hostRenderTest)
        getByName("jvmTest").dependsOn(hostRenderTest)
        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
        }
    }
}

// The e2e test binary consumes :kiteplayer-ffmpeg, whose klib names the libav* libraries; the
// host test names the Homebrew location itself, the same way kiteplayer-network does.
kotlin.macosArm64 {
    binaries.all { linkerOpts("-L/opt/homebrew/lib") }
}

/*
 * ── Android: one adapter .so per ABI whose chain exists ──────────────────
 */
run {
    val abiChains = BuildLibassJniTask.ABIS
        .mapNotNull { abi -> chainFor(abi.depsDirName)?.let { abi to it } }
    val ndk = resolveNdk()
    if (abiChains.isEmpty() || ndk == null) {
        val why = if (ndk == null) "no Android NDK found (set ANDROID_NDK_HOME or sdk.dir in local.properties)"
        else "no ass chain for any Android ABI"
        logger.lifecycle("[kiteplayer-libass] Android target ships NO native library: $why.")
        tasks.matching { it.name.startsWith("compileAndroidMain") || it.name == "bundleAndroidMainAar" }.configureEach {
            doFirst { logger.warn("[kiteplayer-libass] WARNING: this Android artifact carries no libass adapter ($why).") }
        }
    } else {
        val buildJni = tasks.register<BuildLibassJniTask>("buildLibassJni") {
            sourceFile.set(project.file("native/src/libass_jni.c"))
            driverHeaders.from(fileTree("native/src") { include("*.h") })
            abis.set(abiChains.map { (abi, _) -> abi.abiDirName })
            chainDirs.set(abiChains.associate { (abi, chain) -> abi.abiDirName to chain.root.absolutePath })
            abiChains.forEach { (_, chain) ->
                chain.producer?.let { dependsOn(it) }
                chainArchives.from(MergeAssChainTask.MEMBERS.map { chain.libDir.resolve("$it.a") })
            }
            ndkDirectory.set(ndk.absolutePath)
            outputDir.set(layout.buildDirectory.dir("libass-jni"))
        }
        logger.lifecycle("[kiteplayer-libass] Android adapter for ${abiChains.joinToString { it.first.abiDirName }}")
        extensions.configure<KotlinMultiplatformAndroidComponentsExtension> {
            onVariants { variant ->
                val jniLibs = checkNotNull(variant.sources.jniLibs) {
                    "AGP exposed no jniLibs sources for Android variant ${variant.name}."
                }
                jniLibs.addGeneratedSourceDirectory(buildJni, BuildLibassJniTask::outputDir)
            }
        }
    }
}

/*
 * ── The web module: kiteass.mjs and kiteass.wasm, linked with emscripten ──
 *
 * The page hosts the two files, the way it hosts the codec module; a browser distribution does
 * NOT inherit a library's resources, which is why they also travel as the `web` zip attached to
 * the wasmJs publication (kiteplayer-libass-wasm-js-<version>-web.zip) for a consumer to unpack
 * beside its own files. They are kept as wasmJs resources too, so this module's own web tests
 * find them beside the compiled test. Needs emcc on PATH and the wasm32 chain; without either,
 * the wasmJs variant still resolves and the provider's background load simply finds no module,
 * which the engine reports as TypesetterUnavailable. -Pkiteplayer.libass.requireAllHostJni=true
 * turns that skip into a failure, as for the desktops.
 */
run {
    // Through a value source, not ProcessBuilder: the configuration cache refuses an external
    // process at configuration time, and a value source is re-evaluated when its answer changes.
    val emcc: String? = providers.exec {
        commandLine("sh", "-c", "command -v emcc || true")
    }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
    val chain = if (emcc != null) chainFor("wasm32") else null
    val requireAll = providers.gradleProperty("kiteplayer.libass.requireAllHostJni").orNull == "true"
    if (emcc == null || chain == null) {
        val why = if (emcc == null) "no emcc on PATH (brew install emscripten)" else "no ass chain for wasm32"
        if (requireAll) throw GradleException("kiteplayer.libass.requireAllHostJni: cannot link the web module, $why.")
        logger.lifecycle("[kiteplayer-libass] wasmJs ships NO libass module: $why.")
    } else {
        val buildWasm = tasks.register<io.github.yuroyami.kiteplayer.buildtools.BuildLibassWasmModuleTask>("buildLibassWasmModule") {
            sourceFile.set(project.file("native/src/kite_ass_wasm.c"))
            driverDir.set(project.file("native/src"))
            chainDir.set(chain.root)
            chain.producer?.let { dependsOn(it) }
            this.emcc.set(emcc)
            outputDir.set(layout.buildDirectory.dir("kiteass"))
        }
        kotlin.sourceSets.getByName("wasmJsMain").resources.srcDir(buildWasm.map { it.outputDir })
        val webZip = tasks.register<Zip>("kiteassWebZip") {
            group = "kiteplayer"
            description = "The libass web module as one zip, attached to the wasmJs publication."
            from(buildWasm.map { it.outputDir })
            archiveBaseName.set("kiteplayer-libass-wasm-js")
            archiveClassifier.set("web")
            destinationDirectory.set(layout.buildDirectory.dir("kiteass-zip"))
        }
        // Attached once the publications exist; the wasmJs one is named after the target.
        afterEvaluate {
            extensions.findByType<PublishingExtension>()?.publications
                ?.matching { it.name == "wasmJs" }
                ?.configureEach { (this as MavenPublication).artifact(webZip) }
        }
    }
}

/*
 * ── Desktop JVM: one adapter per host whose toolchain this machine has ───
 *
 * macOS links with the host clang; Linux and Windows are cross-linked with konan's clang, lld and
 * sysroots, which arrive with the Kotlin/Native distribution once those targets have compiled once.
 * That recipe reads nothing from the build host but the konan packages, so a Linux or Windows
 * build host links the same adapters the same way. -Pkiteplayer.libass.requireAllHostJni=true
 * turns a skipped host into a failure; the publish path passes it, so a jar cannot ship missing a
 * desktop it promises.
 */
run {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val hostIsMac = "mac" in osName || "darwin" in osName
    val requireAll = providers.gradleProperty("kiteplayer.libass.requireAllHostJni").orNull == "true"
    val konanDependencies = konanDataDirProvider.get().resolve("dependencies")
    fun sysrootPresent(konanTargetName: String): Boolean {
        val relative = io.github.yuroyami.kiteplayer.buildtools.CompileKiteRtTask.specFor(konanTargetName).konanSysroot ?: return false
        return konanDependencies.resolve(relative).isDirectory
    }
    val javaHomeProvider = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        .map { it.metadata.installationPath.asFile.absolutePath }

    val staged = mutableListOf<TaskProvider<BuildLibassHostJniTask>>()
    BuildLibassHostJniTask.CHAIN_DIR_FOR_HOST.forEach { (hostTriple, chainDirName) ->
        val konanTargetName = BuildLibassHostJniTask.KONAN_TARGET_FOR_HOST.getValue(hostTriple)
        val toolchainReady = when (hostTriple) {
            "macos-arm64" -> hostIsMac && File("/usr/bin/clang").canExecute()
            "macos-x64" -> false // The sibling builds no macos-x64 chain; Intel Macs fall back to the Kotlin tier.
            "linux-x64", "linux-arm64", "windows-x64" -> konanTargetName != null && sysrootPresent(konanTargetName)
            else -> false
        }
        val chain = if (toolchainReady) chainFor(chainDirName) else null
        if (!toolchainReady || chain == null) {
            if (hostTriple == "macos-x64") return@forEach
            val why = when {
                chain == null && toolchainReady -> "no ass chain for $chainDirName"
                hostTriple == "macos-arm64" -> "this host is not a Mac with /usr/bin/clang"
                else -> "no konan sysroot for $konanTargetName under $konanDependencies yet; compiling any " +
                    "Kotlin/Native code for that target provisions it"
            }
            if (requireAll) throw GradleException("kiteplayer.libass.requireAllHostJni: cannot build the $hostTriple adapter, $why.")
            logger.lifecycle("[kiteplayer-libass] jvm jar skips the $hostTriple adapter: $why.")
            return@forEach
        }
        val mergeKonan = konanTargetName ?: "macos_arm64"
        val merge = mergeFor(mergeKonan, chain)
        staged += tasks.register<BuildLibassHostJniTask>("buildLibassHostJni${gradleSuffix(hostTriple)}") {
            this.hostTriple.set(hostTriple)
            sourceFile.set(project.file("native/src/libass_jni.c"))
            driverDir.set(project.file("native/src"))
            chainIncludeDir.set(chain.includeDir)
            chain.producer?.let { dependsOn(it) }
            mergedArchive.set(merge.flatMap { m -> m.outputDir.file(MergeAssChainTask.ARCHIVE_NAME) })
            javaHome.set(javaHomeProvider)
            konanDataDir.fileProvider(konanDataDirProvider)
            outputDir.set(layout.buildDirectory.dir("libass-host-jni/$hostTriple"))
        }
    }
    tasks.named<ProcessResources>("jvmProcessResources") {
        staged.forEach { stage -> from(stage.flatMap { it.outputDir }) }
    }
}
