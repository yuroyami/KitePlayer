package io.github.yuroyami.kiteplayer.buildtools

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Unit and task-action tests for [CheckDuplicateClassesTask], over disposable class trees. */
class CheckDuplicateClassesTaskTest {

    @Test
    fun `a class two modules compile fails naming both`() = withFixture { root ->
        val core = classes(root, "kiteplayer-core/build/jvm", "io/github/yuroyami/kiteplayer/MediaItemBuilder.class")
        val player = classes(root, "kiteplayer/build/jvm", "io/github/yuroyami/kiteplayer/MediaItemBuilder.class")

        val failure = assertFailsWith<GradleException> { newTask(root, jvm = listOf(core, player)).check() }

        assertContains(
            failure.message.orEmpty(),
            "JVM: io.github.yuroyami.kiteplayer.MediaItemBuilder is compiled by :kiteplayer and :kiteplayer-core",
        )
    }

    @Test
    fun `classes of different names pass`() = withFixture { root ->
        val core = classes(root, "kiteplayer-core/build/jvm", "io/github/yuroyami/kiteplayer/MediaItemBuilder.class")
        val player = classes(root, "kiteplayer/build/jvm", "io/github/yuroyami/kiteplayer/KitePlayerJava.class")

        newTask(root, jvm = listOf(core, player)).check()
    }

    @Test
    fun `a JVM class and an Android class of one name pass because no app packages both`() = withFixture { root ->
        val core = classes(root, "kiteplayer-core/build/jvm", "io/github/yuroyami/kiteplayer/Platform.class")
        val player = classes(root, "kiteplayer/build/android", "io/github/yuroyami/kiteplayer/Platform.class")

        newTask(root, jvm = listOf(core), android = listOf(player)).check()
    }

    @Test
    fun `one module compiling a class for both platforms passes`() = withFixture { root ->
        val jvm = classes(root, "kiteplayer/build/jvm", "io/github/yuroyami/kiteplayer/KitePlayerJava.class")
        val android = classes(root, "kiteplayer/build/android", "io/github/yuroyami/kiteplayer/KitePlayerJava.class")

        newTask(root, jvm = listOf(jvm), android = listOf(android)).check()
    }

    @Test
    fun `module-info and multi-release copies are not classes the dexer names`() {
        assertTrue(CheckDuplicateClassesTask.isClassFile("io/github/yuroyami/kiteplayer/MediaItem.class"))
        assertTrue(!CheckDuplicateClassesTask.isClassFile("module-info.class"))
        assertTrue(!CheckDuplicateClassesTask.isClassFile("META-INF/versions/9/module-info.class"))
        assertTrue(!CheckDuplicateClassesTask.isClassFile("META-INF/versions/11/io/github/A.class"))
        assertTrue(!CheckDuplicateClassesTask.isClassFile("META-INF/kiteplayer-core.kotlin_module"))
    }

    @Test
    fun `duplicates lists each shared class with its modules in order`() {
        val found = CheckDuplicateClassesTask.duplicates(
            mapOf(
                ":kiteplayer" to setOf("a.B", "a.C"),
                ":kiteplayer-core" to setOf("a.B"),
                ":kiteplayer-io" to setOf("a.C", "a.D"),
            ),
        )
        assertEquals(mapOf("a.B" to listOf(":kiteplayer", ":kiteplayer-core"), "a.C" to listOf(":kiteplayer", ":kiteplayer-io")), found)
    }

    private fun withFixture(block: (File) -> Unit) {
        val root = createTempDirectory("duplicate-classes").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    /** A class directory at [directory] under [root] that holds an empty file for each of [names]. */
    private fun classes(root: File, directory: String, vararg names: String): File {
        val dir = root.resolve(directory)
        names.forEach { name -> dir.resolve(name).apply { parentFile.mkdirs() }.writeBytes(ByteArray(0)) }
        return dir
    }

    private fun newTask(root: File, jvm: List<File> = emptyList(), android: List<File> = emptyList()): CheckDuplicateClassesTask {
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        return project.tasks.register("checkDuplicateClasses", CheckDuplicateClassesTask::class.java) {
            repositoryRoot.set(root)
            publishingProjectDirectories.put(":kiteplayer", "kiteplayer")
            publishingProjectDirectories.put(":kiteplayer-core", "kiteplayer-core")
            jvmClassDirectories.from(jvm)
            androidClassDirectories.from(android)
        }.get()
    }
}
