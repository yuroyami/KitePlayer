/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Tests for [DuplicateClasses], over disposable class trees. */
class DuplicateClassesTest {

    @Test
    fun `a class two modules compile fails naming both`() = withFixture { root ->
        val core = classes(root, "kiteplayer-core/build/jvm", "io/github/yuroyami/kiteplayer/MediaItemBuilder.class")
        val player = classes(root, "kiteplayer/build/jvm", "io/github/yuroyami/kiteplayer/MediaItemBuilder.class")

        val failure = assertFailsWith<BuildFailure> { run(jvm = mapOf("kiteplayer-core" to core, "kiteplayer" to player)) }

        assertContains(
            failure.message.orEmpty(),
            "JVM: io.github.yuroyami.kiteplayer.MediaItemBuilder is compiled by kiteplayer and kiteplayer-core",
        )
    }

    @Test
    fun `classes of different names pass`() = withFixture { root ->
        val core = classes(root, "kiteplayer-core/build/jvm", "io/github/yuroyami/kiteplayer/MediaItemBuilder.class")
        val player = classes(root, "kiteplayer/build/jvm", "io/github/yuroyami/kiteplayer/KitePlayerJava.class")

        run(jvm = mapOf("kiteplayer-core" to core, "kiteplayer" to player))
    }

    @Test
    fun `a JVM class and an Android class of one name pass because no app packages both`() = withFixture { root ->
        val core = classes(root, "kiteplayer-core/build/jvm", "io/github/yuroyami/kiteplayer/Platform.class")
        val player = classes(root, "kiteplayer/build/android", "io/github/yuroyami/kiteplayer/Platform.class")

        run(jvm = mapOf("kiteplayer-core" to core), android = mapOf("kiteplayer" to player))
    }

    @Test
    fun `one module compiling a class for both platforms passes`() = withFixture { root ->
        val jvm = classes(root, "kiteplayer/build/jvm", "io/github/yuroyami/kiteplayer/KitePlayerJava.class")
        val android = classes(root, "kiteplayer/build/android", "io/github/yuroyami/kiteplayer/KitePlayerJava.class")

        run(jvm = mapOf("kiteplayer" to jvm), android = mapOf("kiteplayer" to android))
    }

    @Test
    fun `module-info and multi-release copies are not classes the dexer names`() {
        assertTrue(DuplicateClasses.isClassFile("io/github/yuroyami/kiteplayer/MediaItem.class"))
        assertTrue(!DuplicateClasses.isClassFile("module-info.class"))
        assertTrue(!DuplicateClasses.isClassFile("META-INF/versions/9/module-info.class"))
        assertTrue(!DuplicateClasses.isClassFile("META-INF/versions/11/io/github/A.class"))
        assertTrue(!DuplicateClasses.isClassFile("META-INF/kiteplayer-core.kotlin_module"))
    }

    @Test
    fun `duplicates lists each shared class with its modules in order`() {
        val found = DuplicateClasses.duplicates(
            mapOf(
                "kiteplayer" to setOf("a.B", "a.C"),
                "kiteplayer-core" to setOf("a.B"),
                "kiteplayer-io" to setOf("a.C", "a.D"),
            ),
        )
        assertEquals(mapOf("a.B" to listOf("kiteplayer", "kiteplayer-core"), "a.C" to listOf("kiteplayer", "kiteplayer-io")), found)
    }

    private fun run(jvm: Map<String, File> = emptyMap(), android: Map<String, File> = emptyMap()) =
        DuplicateClasses.check(jvm.mapValues { listOf(it.value) }, android.mapValues { listOf(it.value) }) {}

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
}
