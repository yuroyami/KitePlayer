import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Builds the libass JNI adapter into `<abi>/libkiteplayer_libass_jni.so`, which is exactly the
 * shape Android's `jniLibs` source API packages into an AAR.
 *
 * The Kotlin/Native targets of `:kiteplayer-libass` need nothing here: they reach libass through
 * cinterop. A JVM target cannot, so Android gets a real shared library wrapping the same calls.
 *
 * The ANDROID NDK's clang is used rather than konan's, unlike [CompileKiteRtTask] next door, and
 * the reason is the output: this produces a `.so` that Android's own loader dlopens beside the
 * platform libc++, so it should be built by the toolchain that targets that platform directly.
 * konan's android sysroot is an NDK sysroot too, but it exists to make Kotlin/Native binaries.
 */
abstract class BuildLibassJniTask : DefaultTask() {

    /** `native/src/libass_jni.c`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFile: org.gradle.api.file.RegularFileProperty

    /** A KiteFFmpeg `native-libs/deps` tree holding `<target>/ass-chain` installs. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assChainRoot: DirectoryProperty

    /** The Android NDK, which supplies both the compiler and `jni.h`. */
    @get:Input
    abstract val ndkDirectory: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "kiteplayer"
        description = "Compile the libass JNI adapter for every Android ABI."
    }

    @TaskAction
    fun build() {
        val ndkBin = File(ndkDirectory.get())
            .resolve("toolchains/llvm/prebuilt")
            .listFiles()?.firstOrNull { it.isDirectory }?.resolve("bin")
            ?: throw GradleException("No prebuilt NDK toolchain under ${ndkDirectory.get()}")
        val source = sourceFile.get().asFile
        val depsRoot = assChainRoot.get().asFile
        val output = outputDir.get().asFile
        output.deleteRecursively()

        ABIS.forEach { abi ->
            val chain = depsRoot.resolve("${abi.depsDirName}/ass-chain")
            val chainLib = chain.resolve("lib")
            if (!chainLib.resolve("libass.a").isFile) {
                throw GradleException(
                    "No ass chain for ${abi.depsDirName}: ${chainLib}/libass.a is missing. Run " +
                        "KiteFFmpeg's :kiteffmpeg-core:buildAssChainFor${abi.gradleSuffix} first.",
                )
            }
            // clang++ rather than clang, because harfbuzz is C++ and something has to carry the
            // runtime. -static-libstdc++ keeps it INSIDE this library: the alternative puts a
            // libc++_shared.so next to it that every consumer would then have to package too.
            val clang = ndkBin.resolve("${abi.ndkTarget}-clang++")
            if (!clang.canExecute()) throw GradleException("No NDK compiler at $clang")
            val destination = output.resolve("${abi.abiDirName}/libkiteplayer_libass_jni.so")
            destination.parentFile.mkdirs()

            val command = listOf(
                clang.absolutePath,
                "-shared", "-fPIC", "-O2",
                "-fvisibility=hidden", "-DJNIEXPORT=__attribute__((visibility(\"default\")))",
                "-o", destination.absolutePath,
                // -x c because the DRIVER is clang++ but the SOURCE is C. The driver choice is
                // about the link (harfbuzz is C++ and something must carry its runtime); left to
                // infer the language from the extension, clang++ would compile this file as C++,
                // where `JNIEnv` is a class and every `(*env)->Call(env, ...)` stops compiling.
                "-x", "c", source.absolutePath, "-x", "none",
                "-I", chain.resolve("include").absolutePath,
                // The chain, dependents first, in a group: harfbuzz and freetype reference each
                // other and one left-to-right pass cannot close that cycle.
                "-Wl,--start-group",
                chainLib.resolve("libass.a").absolutePath,
                chainLib.resolve("libharfbuzz.a").absolutePath,
                chainLib.resolve("libfreetype.a").absolutePath,
                chainLib.resolve("libfribidi.a").absolutePath,
                "-Wl,--end-group",
                "-static-libstdc++",
                "-lz", "-lm",
                // Nothing may be left dangling: a missing symbol in a .so surfaces as a dlopen
                // failure on a user's device rather than as a build error here.
                "-Wl,--no-undefined",
            )
            run(command)
            logger.lifecycle("[kiteplayer-libass] ${abi.abiDirName}/${destination.name} built")
        }
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) {
            throw GradleException("Command exited with $code:\n${command.joinToString(" ")}\n$output")
        }
    }

    /** One Android ABI: what the NDK calls it, what the AAR calls it, and where its chain sits. */
    data class AndroidAbi(
        val abiDirName: String,
        val ndkTarget: String,
        val depsDirName: String,
        val gradleSuffix: String,
    )

    companion object {
        /**
         * The two ABIs this project builds an ass chain for. armeabi-v7a and x86 are absent for the
         * same reason they are absent from the chain: nothing has cross-built them, and an ABI
         * directory with no library in it is worse than no ABI directory at all.
         */
        val ABIS: List<AndroidAbi> = listOf(
            AndroidAbi("arm64-v8a", "aarch64-linux-android24", "android-arm64", "AndroidArm64"),
            AndroidAbi("x86_64", "x86_64-linux-android24", "android-x64", "AndroidX64"),
        )

    }
}
