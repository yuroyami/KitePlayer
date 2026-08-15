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

/** Unit and task-action tests for [CheckKitertCouplingTask], all over disposable source trees. */
class CheckKitertCouplingTaskTest {

    @Test
    fun `a matching per-file baseline passes`() = withFixture { root ->
        val source = kotlinSource(
            root,
            "kiteplayer-core/src/nativeMain/kotlin/Existing.kt",
            "import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_create\n",
        )
        val baseline = root.resolve(CheckKitertCouplingTask.BASELINE_FILE_NAME)
        baseline.writeText(
            "${CheckKitertCouplingTask.ALLOWED_KITERT_FILE} " +
                "kiteplayer-core/src/nativeMain/kotlin/Existing.kt\n",
        )

        newTask(root, baseline, listOf(source)).check()
    }

    @Test
    fun `a planted file fails by name and reverting the plant restores green`() = withFixture { root ->
        val existing = kotlinSource(
            root,
            "kiteplayer-core/src/nativeMain/kotlin/Existing.kt",
            "import cnames.structs.kprt_ring\n",
        )
        val baseline = baselineAllowing(root, existing)
        newTask(root, baseline, kotlinFiles(root)).check()

        val planted = kotlinSource(
            root,
            "kiteplayer-ffmpeg/src/nativeMain/kotlin/Planted.kt",
            "import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_create\n",
        )
        val failure = assertFailsWith<GradleException> {
            newTask(root, baseline, kotlinFiles(root)).check()
        }
        assertContains(failure.message.orEmpty(), "New Kotlin source file(s)")
        assertContains(failure.message.orEmpty(), planted.relativeTo(root).invariantSeparatorsPath)
        assertContains(failure.message.orEmpty(), CheckKitertCouplingTask.ALLOWED_KITERT_FILE)

        assertTrue(planted.delete(), "the planted source was not reverted")
        newTask(root, baseline, kotlinFiles(root)).check()
    }

    @Test
    fun `a same-count path swap still fails`() = withFixture { root ->
        val old = kotlinSource(
            root,
            "kiteplayer-core/src/nativeMain/kotlin/Old.kt",
            "val old = \"${CheckKitertCouplingTask.CINTEROP_PACKAGE_PATTERN}\"\n",
        )
        val baseline = baselineAllowing(root, old)
        assertTrue(old.delete())
        val replacement = kotlinSource(
            root,
            "kiteplayer-core/src/nativeMain/kotlin/Replacement.kt",
            "val replacement = \"${CheckKitertCouplingTask.CINTEROP_PACKAGE_PATTERN}\"\n",
        )

        val measured = CheckKitertCouplingTask.measure(root, kotlinFiles(root))
        assertEquals(1, measured.matchingFiles.size, "the aggregate count deliberately did not rise")
        val failure = assertFailsWith<GradleException> {
            newTask(root, baseline, listOf(replacement)).check()
        }
        assertContains(failure.message.orEmpty(), "Replacement.kt")
    }

    @Test
    fun `missing baseline reports the completed measurement and exact creation file`() =
        withFixture { root ->
            val source = kotlinSource(
                root,
                "kiteplayer-core/src/nativeTest/kotlin/Probe.kt",
                "val probe = \"${CheckKitertCouplingTask.CNAMES_PATTERN}ring\"\n",
            )
            val missing = root.resolve(CheckKitertCouplingTask.BASELINE_FILE_NAME)
            val modules = listOf(":kiteplayer-subtitles", ":kiteplayer-core")
            val measured = CheckKitertCouplingTask.measure(root, listOf(source))
            val expectedFile = CheckKitertCouplingTask.renderBaseline(measured, modules)

            val failure = assertFailsWith<GradleException> {
                newTask(root, missing, listOf(source), modules).check()
            }
            val message = failure.message.orEmpty()
            assertContains(message, "baseline missing")
            assertContains(message, "Measured 1 Kotlin source file(s)")
            assertContains(message, "Measured matching Kotlin file count: 1")
            assertContains(message, expectedFile)
            assertContains(message, "allowed_kitert_file kiteplayer-core/src/nativeTest/kotlin/Probe.kt")
        }

    @Test
    fun `comment stripping handles nesting and preserves quoted content`() {
        val stripped = CheckKitertCouplingTask.stripComments(
            """
            |val kept = "kiteplayer.rt.cinterop // not a comment"
            |val raw = ${'"'}${'"'}${'"'}cnames.structs.kprt_ring /* not a comment */${'"'}${'"'}${'"'}
            |val char = '/' // kiteplayer.rt.cinterop
            |/* cnames.structs.kprt_hidden /* kiteplayer.rt.cinterop */ still hidden */
            |val after = "ok"
            """.trimMargin(),
        )

        assertContains(stripped, "kiteplayer.rt.cinterop // not a comment")
        assertContains(stripped, "cnames.structs.kprt_ring /* not a comment */")
        assertContains(stripped, "val char = '/'")
        assertContains(stripped, "val after = \"ok\"")
        assertTrue("kprt_hidden" !in stripped)
    }

    @Test
    fun `comments inside string templates are stripped while template code remains`() {
        val stripped = CheckKitertCouplingTask.stripComments(
            """
            |val ordinary = "value ${'$'}{1 /* kiteplayer.rt.cinterop */ + run { // cnames.structs.kprt_hidden
            |    cnames.structs.kprt_visible
            |}}"
            """.trimMargin(),
        )

        assertTrue("kiteplayer.rt.cinterop" !in stripped)
        assertTrue("kprt_hidden" !in stripped)
        assertContains(stripped, "cnames.structs.kprt_visible")
    }

    @Test
    fun `nested raw templates and backtick braces do not end template code early`() {
        val stripped = CheckKitertCouplingTask.stripComments(
            """
            |val raw = ${'"'}${'"'}${'"'}value ${'$'}{
            |    if (true) "nested ${'$'}{1 /* kiteplayer.rt.cinterop */}" else `name}kept`
            |}${'"'}${'"'}${'"'}
            """.trimMargin(),
        )

        assertTrue("kiteplayer.rt.cinterop" !in stripped)
        assertContains(stripped, "`name}kept`")
    }

    @Test
    fun `an escaped string template stays literal text`() {
        val stripped = CheckKitertCouplingTask.stripComments(
            "val escaped = \"\\\${1 /* kiteplayer.rt.cinterop */}\"",
        )

        assertContains(stripped, "kiteplayer.rt.cinterop")
    }

    @Test
    fun `template comments do not match but direct template code does`() = withFixture { root ->
        val commentOnly = kotlinSource(
            root,
            "kiteplayer-core/src/commonMain/kotlin/CommentTemplate.kt",
            "val value = \"\${1 /* kiteplayer.rt.cinterop */}\"\n",
        )
        val directCode = kotlinSource(
            root,
            "kiteplayer-core/src/commonMain/kotlin/CodeTemplate.kt",
            "val value = \"\${cnames.structs.kprt_ring}\"\n",
        )

        val measured = CheckKitertCouplingTask.measure(root, listOf(commentOnly, directCode))
        assertEquals(
            setOf("kiteplayer-core/src/commonMain/kotlin/CodeTemplate.kt"),
            measured.matchingFiles,
        )
    }

    @Test
    fun `comment-only names do not count but quoted names do`() = withFixture { root ->
        val comments = kotlinSource(
            root,
            "kiteplayer-core/src/commonMain/kotlin/Comments.kt",
            """
            // kiteplayer.rt.cinterop
            /* cnames.structs.kprt_ring */
            val clean = 1
            """.trimIndent(),
        )
        val quoted = kotlinSource(
            root,
            "kiteplayer-core/src/commonMain/kotlin/Quoted.kt",
            "val diagnostic = \"kiteplayer.rt.cinterop\"\n",
        )

        val measured = CheckKitertCouplingTask.measure(root, listOf(comments, quoted))
        assertEquals(setOf("kiteplayer-core/src/commonMain/kotlin/Quoted.kt"), measured.matchingFiles)
    }

    @Test
    fun `both patterns and repeated mentions count one file once`() = withFixture { root ->
        val source = kotlinSource(
            root,
            "kiteplayer-core/src/nativeMain/kotlin/Many.kt",
            """
            import cnames.structs.kprt_ring
            import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_create
            val again = "kiteplayer.rt.cinterop"
            """.trimIndent(),
        )

        val measured = CheckKitertCouplingTask.measure(root, listOf(source, source))
        assertEquals(1, measured.scannedFileCount, "duplicate file-collection entries must be harmless")
        assertEquals(setOf("kiteplayer-core/src/nativeMain/kotlin/Many.kt"), measured.matchingFiles)
    }

    @Test
    fun `module selector covers the split artifacts and excludes only the two decided owners`() {
        val selected = CheckKitertCouplingTask.selectIncludedModulePaths(
            listOf(
                ":kiteplayer-sample",
                ":kiteplayer-sample-android",
                ":kiteplayer-output",
                ":kiteplayer-core",
                ":kiteplayer-rt",
                ":kiteplayer-subtitles",
                ":kiteplayer-ffmpeg",
                ":kiteplayer-view",
                ":kiteplayer-mobile",
                ":kiteplayer-compose-interop",
                ":kiteplayer-compose-video",
                ":kiteplayer-phone",
                ":kiteplayer-compose",
                ":kiteplayer-core",
            ),
        )

        assertEquals(
            listOf(
                ":kiteplayer-compose",
                ":kiteplayer-compose-interop",
                ":kiteplayer-compose-video",
                ":kiteplayer-core",
                ":kiteplayer-ffmpeg",
                ":kiteplayer-mobile",
                ":kiteplayer-phone",
                ":kiteplayer-sample",
                ":kiteplayer-sample-android",
                ":kiteplayer-subtitles",
                ":kiteplayer-view",
            ),
            selected,
        )
    }

    @Test
    fun `baseline parser is strict about keys duplicates and portable source paths`() =
        withFixture { root ->
            val invalidLines = listOf(
                "other_key kiteplayer-core/src/main/kotlin/A.kt" to "expected `allowed_kitert_file path`",
                "allowed_kitert_file /absolute/src/main/kotlin/A.kt" to "repository-relative",
                "allowed_kitert_file C:/absolute/src/main/kotlin/A.kt" to "repository-relative",
                "allowed_kitert_file module/src/../src/main/kotlin/A.kt" to "not normalized",
                "allowed_kitert_file module\\src\\main\\kotlin\\A.kt" to "must use `/`",
                "allowed_kitert_file module/api/A.kt" to "under `src`",
                "allowed_kitert_file module/src/main/kotlin/A.java" to "Kotlin file",
            )
            for ((line, expected) in invalidLines) {
                val file = root.resolve("invalid-${invalidLines.indexOf(line to expected)}.txt")
                file.writeText("$line\n")
                val failure = assertFailsWith<GradleException> {
                    CheckKitertCouplingTask.parseBaseline(file)
                }
                assertContains(failure.message.orEmpty(), expected)
                assertContains(failure.message.orEmpty(), ":1:")
            }

            val duplicate = root.resolve("duplicate.txt")
            duplicate.writeText(
                """
                allowed_kitert_file module/src/main/kotlin/A.kt
                allowed_kitert_file module/src/main/kotlin/A.kt
                """.trimIndent(),
            )
            val duplicateFailure = assertFailsWith<GradleException> {
                CheckKitertCouplingTask.parseBaseline(duplicate)
            }
            assertContains(duplicateFailure.message.orEmpty(), "duplicate")
            assertContains(duplicateFailure.message.orEmpty(), ":2:")
        }

    @Test
    fun `baseline renderer sorts modules and paths deterministically`() {
        val rendered = CheckKitertCouplingTask.renderBaseline(
            CheckKitertCouplingTask.Measurement(
                scannedFileCount = 4,
                matchingFiles = linkedSetOf(
                    "z-module/src/main/kotlin/Z.kt",
                    "a-module/src/main/kotlin/A.kt",
                ),
            ),
            listOf(":z-module", ":a-module", ":z-module"),
        )

        assertTrue(rendered.indexOf("#   :a-module") < rendered.indexOf("#   :z-module"))
        assertTrue(
            rendered.indexOf("allowed_kitert_file a-module") <
                rendered.indexOf("allowed_kitert_file z-module"),
        )
        assertEquals(rendered, CheckKitertCouplingTask.renderBaseline(
            CheckKitertCouplingTask.Measurement(4, linkedSetOf(
                "a-module/src/main/kotlin/A.kt",
                "z-module/src/main/kotlin/Z.kt",
            )),
            listOf(":a-module", ":z-module"),
        ))
    }

    @Test
    fun `measurement rejects inputs outside the repository and outside module src`() =
        withFixture { root ->
            val outsideRoot = createTempDirectory("kitert-outside").toFile()
            try {
                val outside = kotlinSource(outsideRoot, "module/src/main/kotlin/Outside.kt", "val x = 1\n")
                assertContains(
                    assertFailsWith<GradleException> {
                        CheckKitertCouplingTask.measure(root, listOf(outside))
                    }.message.orEmpty(),
                    "outside",
                )
                val notSource = kotlinSource(root, "module/generated/Generated.kt", "val x = 1\n")
                assertContains(
                    assertFailsWith<GradleException> {
                        CheckKitertCouplingTask.measure(root, listOf(notSource))
                    }.message.orEmpty(),
                    "not under a module src directory",
                )
            } finally {
                outsideRoot.deleteRecursively()
            }
        }

    private fun newTask(
        root: File,
        baseline: File,
        sources: Collection<File>,
        modules: List<String> = listOf(":kiteplayer-core", ":kiteplayer-ffmpeg"),
    ): CheckKitertCouplingTask {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.register(
            "checkKitertCoupling",
            CheckKitertCouplingTask::class.java,
        ).get()
        task.repositoryRoot.set(root)
        task.sourceFiles.from(sources)
        task.includedModulePaths.set(modules)
        task.baselineFile.set(baseline)
        task.baselineInputs.from(if (baseline.isFile) listOf(baseline) else emptyList<File>())
        return task
    }

    private fun baselineAllowing(root: File, vararg sources: File): File {
        val measured = CheckKitertCouplingTask.measure(root, sources.toList())
        return root.resolve(CheckKitertCouplingTask.BASELINE_FILE_NAME).also {
            it.writeText(CheckKitertCouplingTask.renderBaseline(measured, listOf(":kiteplayer-core")))
        }
    }

    private fun kotlinSource(root: File, relativePath: String, text: String): File =
        root.resolve(relativePath).also {
            it.parentFile.mkdirs()
            it.writeText(text)
        }

    private fun kotlinFiles(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private inline fun withFixture(block: (File) -> Unit) {
        val root = createTempDirectory("kitert-coupling").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
