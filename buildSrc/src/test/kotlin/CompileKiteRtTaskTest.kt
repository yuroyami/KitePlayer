package io.github.yuroyami.kiteplayer.buildtools

import org.gradle.api.GradleException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests over the pure parts of [CompileKiteRtTask].
 *
 * The architecture assertion is the one that matters. Register item B1-11 says a
 * wrong-architecture archive is embedded by cinterop without complaint and fails only at the
 * consumer's final link, so the producer has to catch it, and a guard nobody tests is a guard
 * nobody can rely on. These cases hand [CompileKiteRtTask.verifyObjectArchitecture] real `file -b`
 * strings, including the exact string a linuxX64 object produces, which is the mix that was
 * measured to pass silently through cinterop.
 *
 * buildSrc's tests are not part of the main build's task graph, so the verification gate runs
 * `:buildSrc:test` explicitly.
 */
class CompileKiteRtTaskTest {

    private val someObject = File("/tmp/kiteplayer-rt-test/kite_rt_ring.o")

    /** Every native target `kiteplayer-core` declares, spelled as Kotlin/Native spells it. */
    private val declaredTargets = listOf(
        "macos_arm64",
        "ios_arm64", "ios_simulator_arm64", "ios_x64",
        "tvos_arm64", "tvos_simulator_arm64",
        "watchos_arm32", "watchos_arm64", "watchos_device_arm64", "watchos_simulator_arm64",
        "android_arm32", "android_arm64", "android_x64", "android_x86",
        "linux_x64", "linux_arm64",
        "mingw_x64",
    )

    @Test
    fun `every target kiteplayer-core declares has a triple and an expected architecture`() {
        for (target in declaredTargets) {
            val spec = CompileKiteRtTask.specFor(target)
            assertTrue(spec.triple.isNotBlank(), "$target has no triple")
            assertTrue(
                (spec.appleSdk == null) != (spec.konanSysroot == null),
                "$target must name exactly one sysroot, has appleSdk=${spec.appleSdk} konanSysroot=${spec.konanSysroot}",
            )
            assertTrue(
                CompileKiteRtTask.expectedObjectDescription(target).isNotBlank(),
                "$target has no expected object description",
            )
        }
    }

    @Test
    fun `an unknown target is refused with the name of the function to extend`() {
        val failure = assertFailsWith<GradleException> { CompileKiteRtTask.specFor("mars_riscv128") }
        assertContains(failure.message.orEmpty(), "mars_riscv128")
        assertContains(failure.message.orEmpty(), "CompileKiteRtTask.specFor")
    }

    @Test
    fun `the measured architecture strings are the ones the host produced`() {
        // Measured on this machine by compiling native/src/kite_rt_ring.c for each target with the
        // konan clang and reading `file -b` off the object. Written down so a change to specFor
        // that silently altered a triple would fail here rather than at a consumer's link.
        assertEquals("Mach-O 64-bit object arm64", CompileKiteRtTask.expectedObjectDescription("macos_arm64"))
        assertEquals("Mach-O object arm_v7k", CompileKiteRtTask.expectedObjectDescription("watchos_arm32"))
        assertEquals("Mach-O object arm64_32", CompileKiteRtTask.expectedObjectDescription("watchos_arm64"))
        assertEquals(
            "Mach-O 64-bit object arm64",
            CompileKiteRtTask.expectedObjectDescription("watchos_device_arm64"),
        )
        assertEquals("ELF 32-bit LSB relocatable, Intel 80386", CompileKiteRtTask.expectedObjectDescription("android_x86"))
        assertEquals("Intel amd64 COFF object file", CompileKiteRtTask.expectedObjectDescription("mingw_x64"))
    }

    @Test
    fun `a matching architecture passes, trailing detail and all`() {
        // Real `file -b` output carries version and section detail after the architecture, which is
        // why the check is a prefix match rather than an equality.
        CompileKiteRtTask.verifyObjectArchitecture(
            "linux_x64",
            someObject,
            "ELF 64-bit LSB relocatable, x86-64, version 1 (SYSV), not stripped",
        )
        CompileKiteRtTask.verifyObjectArchitecture("macos_arm64", someObject, "Mach-O 64-bit object arm64")
        CompileKiteRtTask.verifyObjectArchitecture("watchos_arm64", someObject, "Mach-O object arm64_32_v8")
    }

    @Test
    fun `a Windows COFF object is accepted under either spelling of the same format`() {
        // MEASURED ON TWO HOSTS, which is the whole point of this case. This Mac's `file` calls a
        // mingw object "Intel amd64 COFF object file"; a GitHub windows-latest runner calls the
        // object its own konan clang just produced "x86-64 COFF object file". Same format, same
        // architecture, two spellings, and pinning only the one this machine says turned the first
        // real Windows CI run red on a correct object.
        CompileKiteRtTask.verifyObjectArchitecture(
            "mingw_x64",
            someObject,
            "x86-64 COFF object file, not stripped, 5 sections, symbol offset=0x178, 14 symbols, " +
                "1st section name \".text\", 2nd section name \".data\"",
        )
        CompileKiteRtTask.verifyObjectArchitecture(
            "mingw_x64",
            someObject,
            "Intel amd64 COFF object file, not stripped, 5 sections",
        )
    }

    @Test
    fun `a Windows slot still refuses an object that is not COFF at all`() {
        // Widening the accepted set must not widen it to everything: an ELF in the mingw slot is the
        // B1-11 mix and stays refused.
        val failure = assertFailsWith<GradleException> {
            CompileKiteRtTask.verifyObjectArchitecture(
                "mingw_x64",
                someObject,
                "ELF 64-bit LSB relocatable, x86-64, version 1 (SYSV), not stripped",
            )
        }
        assertContains(failure.message.orEmpty(), "mingw_x64")
        assertContains(failure.message.orEmpty(), "B1-11")
    }

    @Test
    fun `the exact mix register item B1-11 records is refused`() {
        // A linuxX64 ELF object where the macosArm64 one belongs. Measured during reconnaissance to
        // be embedded by cinterop without complaint and to fail only at the consumer's final link.
        val failure = assertFailsWith<GradleException> {
            CompileKiteRtTask.verifyObjectArchitecture(
                "macos_arm64",
                someObject,
                "ELF 64-bit LSB relocatable, x86-64, version 1 (SYSV), not stripped",
            )
        }
        val message = failure.message.orEmpty()
        assertContains(message, "macos_arm64")
        assertContains(message, "ELF 64-bit LSB relocatable, x86-64")
        assertContains(message, "Mach-O 64-bit object arm64")
        assertContains(message, "B1-11")
    }

    @Test
    fun `a 32 bit watchOS object in a 64 bit watchOS slot is refused`() {
        // The two watchOS arm64 spellings are the likeliest confusion in this table: watchos_arm64
        // is the 32 bit pointer arm64_32 ABI and watchos_device_arm64 is ordinary arm64.
        assertFailsWith<GradleException> {
            CompileKiteRtTask.verifyObjectArchitecture(
                "watchos_device_arm64",
                someObject,
                "Mach-O object arm64_32_v8",
            )
        }
        assertFailsWith<GradleException> {
            CompileKiteRtTask.verifyObjectArchitecture(
                "watchos_arm64",
                someObject,
                "Mach-O 64-bit object arm64",
            )
        }
    }

    @Test
    fun `the flag set carries the four guards the plan requires`() {
        val flags = CompileKiteRtTask.COMPILER_FLAGS
        assertContains(flags, "-fvisibility=hidden")
        assertContains(flags, "-Werror")
        // A variable length array on a real-time path is an allocation no symbol audit would show,
        // and B1.8's render audit relies on this flag being in force for the shipped archive too.
        assertContains(flags, "-Werror=vla")
        assertContains(flags, "-fPIC")
    }

    @Test
    fun `the archive name is the one the def file asks cinterop for`() {
        assertEquals("libkiteplayerrt.a", CompileKiteRtTask.ARCHIVE_NAME)
    }

    @Test
    fun `the LLVM package resolver prefers the named package`() {
        val root = createTempDirectory()
        try {
            val preferred = root.resolve("llvm-21-aarch64-macos-essentials-97/bin")
            writeExecutable(preferred.resolve("clang"))
            val older = root.resolve("llvm-19-aarch64-macos-essentials-79/bin")
            writeExecutable(older.resolve("clang"))

            val resolved = CompileKiteRtTask.resolveLlvmBinDir(root, "llvm-21-aarch64-macos-essentials-97")
            assertEquals(preferred.absolutePath, resolved.absolutePath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the LLVM package resolver falls back to the newest present and says so`() {
        val root = createTempDirectory()
        try {
            // Numbers, not text: llvm-9 must not sort above llvm-21, and essentials-97 must beat
            // essentials-79 within the same LLVM version.
            writeExecutable(root.resolve("llvm-9-aarch64-macos-essentials-99/bin/clang"))
            writeExecutable(root.resolve("llvm-21-aarch64-macos-essentials-79/bin/clang"))
            val newest = root.resolve("llvm-21-aarch64-macos-essentials-97/bin")
            writeExecutable(newest.resolve("clang"))

            val messages = mutableListOf<String>()
            val resolved = CompileKiteRtTask.resolveLlvmBinDir(root, "llvm-22-does-not-exist") { messages += it }

            assertEquals(newest.absolutePath, resolved.absolutePath)
            assertEquals(1, messages.size, "the substitution must be reported exactly once")
            assertContains(messages.single(), "llvm-21-aarch64-macos-essentials-97")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `no LLVM package at all is a failure that names what is missing`() {
        val root = createTempDirectory()
        try {
            val failure = assertFailsWith<GradleException> {
                CompileKiteRtTask.resolveLlvmBinDir(root, "llvm-21-aarch64-macos-essentials-97")
            }
            assertContains(failure.message.orEmpty(), "llvm-21-aarch64-macos-essentials-97")
        } finally {
            root.deleteRecursively()
        }
    }

    // ---- The three guard cases the interlude added (I-10), mirroring CompileKiteCodecCTaskTest
    // so the two near-twin tasks are covered identically. These run the REAL compile() with the
    // real konan clang over a trivial fixture source, because the review measured that a suite
    // which only exercises the predicates stays green when a guard's call site is deleted.

    @Test
    fun `compile itself refuses an object whose description is wrong`() {
        val fixture = fixture()
        val project = org.gradle.testfixtures.ProjectBuilder.builder().build()
        val task = project.tasks
            .register("compileWithLyingDescribe", LyingDescribeTask::class.java)
            .get()
        task.konanTargetName.set("macos_arm64")
        task.sourceDir.set(fixture.sourceDir)
        task.includeDir.set(fixture.includeDir)
        task.konanDataDir.set(konanDataDir)
        task.outputDir.set(fixture.outputRoot.resolve("macos_arm64"))

        val message = kotlin.test.assertFails { task.compile() }.message ?: ""
        assertContains(message, "macos_arm64")
        assertContains(message, LyingDescribeTask.WRONG_DESCRIPTION)
        assertContains(message, CompileKiteRtTask.expectedObjectDescription("macos_arm64"))
    }

    /** A task whose `file -b` answer is always wrong, for the call-site case above. */
    abstract class LyingDescribeTask @javax.inject.Inject constructor(
        execOperations: org.gradle.process.ExecOperations,
    ) : CompileKiteRtTask(execOperations) {
        override fun describeFile(file: File): String = WRONG_DESCRIPTION
        companion object {
            const val WRONG_DESCRIPTION: String = "Mach-O 64-bit object x86_64 (a lie, planted by the call-site test)"
        }
    }

    @Test
    fun `a stale object from a previous run is cleared`() {
        val fixture = fixture()
        val task = newRealTask("macos_arm64", fixture)
        val stale = fixture.outputRoot.resolve("macos_arm64/obj/stale.o")
        stale.parentFile.mkdirs()
        stale.writeText("not an object at all")

        task.compile()

        assertTrue(!stale.exists(), "the stale object survived the compile")
        val objects = fixture.outputRoot.resolve("macos_arm64/obj").listFiles().orEmpty().map { it.name }
        assertEquals(listOf("probe.o"), objects, "obj/ must hold exactly the objects of this run")
    }

    @Test
    fun `an output directory not named after its target is refused`() {
        val fixture = fixture()
        val task = newRealTask("macos_arm64", fixture)
        task.outputDir.set(fixture.outputRoot.resolve("shared"))

        val message = kotlin.test.assertFails { task.compile() }.message ?: ""
        assertContains(message, "B1-11")
        assertContains(message, "macos_arm64")
        assertContains(message, "shared")
    }

    private class Fixture(val sourceDir: File, val includeDir: File, val outputRoot: File)

    private fun fixture(): Fixture {
        val root = createTempDirectory()
        val sourceDir = root.resolve("src").also { it.mkdirs() }
        val includeDir = root.resolve("include").also { it.mkdirs() }
        sourceDir.resolve("probe.c").writeText("int kprt_probe(void) { return 0; }\n")
        return Fixture(sourceDir, includeDir, root.resolve("out"))
    }

    private fun newRealTask(konanTarget: String, fixture: Fixture): CompileKiteRtTask {
        val project = org.gradle.testfixtures.ProjectBuilder.builder().build()
        val task = project.tasks
            .register("compileKiteRtCFor$konanTarget", CompileKiteRtTask::class.java)
            .get()
        task.konanTargetName.set(konanTarget)
        task.sourceDir.set(fixture.sourceDir)
        task.includeDir.set(fixture.includeDir)
        task.konanDataDir.set(konanDataDir)
        task.outputDir.set(fixture.outputRoot.resolve(konanTarget))
        return task
    }

    /** `~/.konan`, or whatever `KONAN_DATA_DIR` points at, the same resolution the build uses. */
    private val konanDataDir: File
        get() {
            val fromEnv = System.getenv("KONAN_DATA_DIR")
            val dir = if (fromEnv.isNullOrBlank()) {
                File(System.getProperty("user.home"), ".konan")
            } else {
                File(fromEnv)
            }
            assertTrue(
                dir.resolve("dependencies").isDirectory,
                "No konan dependencies under ${dir.absolutePath}; they arrive with the " +
                    "Kotlin/Native distribution.",
            )
            return dir
        }


    @Test
    fun `a Windows shaped dependencies tree resolves clang and llvm-ar by their exe names`() {
        // Interlude item I-20. A Windows konan package ships clang.exe and llvm-ar.exe, and the
        // review measured File("bin/clang").canExecute() false against a Windows shaped tree, so
        // every candidate was rejected and the windows-x64 CI job could not pass. Resolution now
        // tries the bare name and then the .exe name.
        val root = createTempDirectory()
        val bin = root.resolve("llvm-21-x86_64-windows-essentials-97/bin")
        writeExecutable(bin.resolve("clang.exe"))
        writeExecutable(bin.resolve("llvm-ar.exe"))

        val resolved = CompileKiteRtTask.resolveLlvmBinDir(root, "llvm-21-x86_64-windows-essentials-97")
        assertEquals(bin.absolutePath, resolved.absolutePath)
        assertEquals("clang.exe", CompileKiteRtTask.resolveTool(resolved, "clang")?.name)
        assertEquals("llvm-ar.exe", CompileKiteRtTask.resolveTool(resolved, "llvm-ar")?.name)
    }

    @Test
    fun `the Android toolchain package is named after the build host`() {
        // Interlude item I-20. The sysroot path hardcoded the osx infix, and on an Ubuntu runner
        // the osx package never exists, so the C compile threw before cinterop and the three
        // android CI jobs could not pass. The infix now follows the host, the way konan's own
        // konan.properties names the packages.
        val root = createTempDirectory()
        val linuxSysroot = root.resolve("target-toolchain-2-linux-android_ndk/sysroot")
        assertTrue(linuxSysroot.mkdirs())

        assertEquals("osx", CompileKiteRtTask.konanHostInfix("Mac OS X"))
        assertEquals("windows", CompileKiteRtTask.konanHostInfix("Windows Server 2022"))
        assertEquals("linux", CompileKiteRtTask.konanHostInfix("Linux"))
        assertTrue(
            root.resolve(CompileKiteRtTask.androidToolchainSysroot("Linux")).isDirectory,
            "the Linux shaped tree must resolve on a Linux host name",
        )
    }

    private fun createTempDirectory(): File {
        val directory = File.createTempFile("kiteplayer-rt-buildsrc", "")
        assertTrue(directory.delete())
        assertTrue(directory.mkdirs())
        return directory
    }

    private fun writeExecutable(file: File) {
        file.parentFile.mkdirs()
        file.writeText("#!/bin/sh\nexit 0\n")
        assertTrue(file.setExecutable(true))
    }
}
