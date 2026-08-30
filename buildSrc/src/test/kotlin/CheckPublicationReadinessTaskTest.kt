package io.github.yuroyami.kiteplayer.buildtools

import org.gradle.api.GradleException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CheckPublicationReadinessTaskTest {

    @Test
    fun `a fully configured publication graph passes`() {
        val report = CheckPublicationReadinessTask.evaluate(
            publishingModules = setOf(":core", ":rt"),
            publications = listOf(completePom(":core"), completePom(":rt")),
            edges = setOf(CheckPublicationReadinessTask.ProjectEdge(":core", ":rt")),
        )

        CheckPublicationReadinessTask.requireReady(report)
        assertEquals(
            "Publication readiness inputs: 2 publishing modules, 2 generated POMs, 1 project dependency edge.\n" +
                "Project dependency edges: :core -> :rt\n" +
                "POM findings (0): none\nSibling-publishability findings (0): none\n" +
                "Signing findings (0): none",
            CheckPublicationReadinessTask.render(report),
        )
    }

    @Test
    fun `the split presentation graph and compatibility umbrellas remain publishable`() {
        val modules = setOf(
            ":core",
            ":ffmpeg",
            ":output",
            ":view",
            ":mobile",
            ":compose-interop",
            ":compose-video",
            ":phone",
            ":compose",
        )
        val edges = setOf(
            CheckPublicationReadinessTask.ProjectEdge(":ffmpeg", ":core"),
            CheckPublicationReadinessTask.ProjectEdge(":output", ":core"),
            CheckPublicationReadinessTask.ProjectEdge(":view", ":core"),
            CheckPublicationReadinessTask.ProjectEdge(":mobile", ":core"),
            CheckPublicationReadinessTask.ProjectEdge(":mobile", ":ffmpeg"),
            CheckPublicationReadinessTask.ProjectEdge(":mobile", ":output"),
            CheckPublicationReadinessTask.ProjectEdge(":mobile", ":view"),
            CheckPublicationReadinessTask.ProjectEdge(":compose-interop", ":core"),
            CheckPublicationReadinessTask.ProjectEdge(":compose-interop", ":mobile"),
            CheckPublicationReadinessTask.ProjectEdge(":compose-video", ":core"),
            CheckPublicationReadinessTask.ProjectEdge(":compose-video", ":ffmpeg"),
            CheckPublicationReadinessTask.ProjectEdge(":compose-video", ":mobile"),
            CheckPublicationReadinessTask.ProjectEdge(":compose-video", ":output"),
            CheckPublicationReadinessTask.ProjectEdge(":phone", ":mobile"),
            CheckPublicationReadinessTask.ProjectEdge(":phone", ":view"),
            CheckPublicationReadinessTask.ProjectEdge(":compose", ":compose-interop"),
            CheckPublicationReadinessTask.ProjectEdge(":compose", ":compose-video"),
            CheckPublicationReadinessTask.ProjectEdge(":compose", ":phone"),
        )
        val report = CheckPublicationReadinessTask.evaluate(
            publishingModules = modules,
            publications = modules.map(::completePom),
            edges = edges,
        )

        CheckPublicationReadinessTask.requireReady(report)
        assertEquals(modules.size, report.publishingModuleCount)
        assertEquals(edges.size, report.dependencyEdgeCount)
        assertTrue(report.siblingFindings.isEmpty())
    }

    @Test
    fun `a missing POM field fails naming the field and fix`() {
        val report = CheckPublicationReadinessTask.evaluate(
            publishingModules = setOf(":core"),
            publications = listOf(completePom(":core").copy(description = "  ")),
            edges = emptySet(),
        )

        val failure = assertFailsWith<GradleException> {
            CheckPublicationReadinessTask.requireReady(report)
        }
        assertContains(failure.message.orEmpty(), ":core")
        assertContains(failure.message.orEmpty(), "description")
        assertContains(failure.message.orEmpty(), "Fix:")
        assertContains(failure.message.orEmpty(), "POM_DESCRIPTION")
    }

    @Test
    fun `a publishing module depending on a nonpublishing sibling fails naming both and fix`() {
        val report = CheckPublicationReadinessTask.evaluate(
            publishingModules = setOf(":core"),
            publications = listOf(completePom(":core")),
            edges = setOf(CheckPublicationReadinessTask.ProjectEdge(":core", ":rt")),
        )

        val failure = assertFailsWith<GradleException> {
            CheckPublicationReadinessTask.requireReady(report)
        }
        assertContains(failure.message.orEmpty(), ":core")
        assertContains(failure.message.orEmpty(), ":rt")
        assertContains(failure.message.orEmpty(), "Fix:")
        assertContains(failure.message.orEmpty(), "com.vanniktech.maven.publish")
    }

    @Test
    fun `all findings accumulate once per module and field in stable order`() {
        val first = completePom(":alpha").copy(
            publicationName = "jvm",
            name = null,
            description = null,
            licences = emptyList(),
            scm = null,
        )
        val second = first.copy(publicationName = "kotlinMultiplatform")
        val report = CheckPublicationReadinessTask.evaluate(
            publishingModules = setOf(":zulu", ":alpha"),
            publications = listOf(second, first, completePom(":zulu")),
            edges = setOf(
                CheckPublicationReadinessTask.ProjectEdge(":zulu", ":missing-b"),
                CheckPublicationReadinessTask.ProjectEdge(":alpha", ":missing-a"),
                CheckPublicationReadinessTask.ProjectEdge(":sample", ":also-missing"),
            ),
        )

        assertEquals(listOf("name", "description", "licence", "scm"), report.pomFindings.map { it.field })
        assertTrue(report.pomFindings.all { it.modulePath == ":alpha" })
        assertTrue(report.pomFindings.all { it.publications == listOf("jvm", "kotlinMultiplatform") })
        assertEquals(listOf(":alpha", ":zulu"), report.siblingFindings.map { it.modulePath })
    }

    @Test
    fun `a publishing module with no generated POM is a finding`() {
        val report = CheckPublicationReadinessTask.evaluate(
            publishingModules = setOf(":core"),
            publications = emptyList(),
            edges = emptySet(),
        )

        val failure = assertFailsWith<GradleException> { CheckPublicationReadinessTask.requireReady(report) }
        assertContains(failure.message.orEmpty(), ":core")
        assertContains(failure.message.orEmpty(), "no generated Maven POM")
        assertContains(failure.message.orEmpty(), "GenerateMavenPom")
    }

    @Test
    fun `the namespace aware parser reads direct POM children only`() {
        val pom = kotlin.io.path.createTempFile(suffix = ".pom").toFile()
        try {
            pom.writeText(
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <groupId>io.github.yuroyami</groupId>
                  <artifactId>kite</artifactId>
                  <version>0.0.1</version>
                  <description>A direct description.</description>
                  <licenses><license><name>Apache-2.0</name><url>https://example.invalid/license</url></license></licenses>
                  <scm>
                    <connection>scm:git:https://example.invalid/kite.git</connection>
                    <developerConnection>scm:git:ssh://git@example.invalid/kite.git</developerConnection>
                    <url>https://example.invalid/kite</url>
                  </scm>
                  <dependencies><dependency><name>Nested name must not count.</name></dependency></dependencies>
                </project>
                """.trimIndent(),
            )

            val parsed = CheckPublicationReadinessTask.parsePom(pom, ":core", "jvm")

            assertEquals("io.github.yuroyami", parsed.groupId)
            assertEquals("0.0.1", parsed.version)
            assertEquals("A direct description.", parsed.description)
            assertEquals(null, parsed.name)
            assertEquals("Apache-2.0", parsed.licences.single().name)
            assertEquals("scm:git:ssh://git@example.invalid/kite.git", parsed.scm?.developerConnection)
        } finally {
            pom.delete()
        }
    }

    @Test
    fun `external entities and doctypes are refused`() {
        val pom = kotlin.io.path.createTempFile(suffix = ".pom").toFile()
        try {
            pom.writeText(
                """
                <!DOCTYPE project [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <project><groupId>&secret;</groupId></project>
                """.trimIndent(),
            )

            val failure = assertFailsWith<GradleException> {
                CheckPublicationReadinessTask.parsePom(pom, ":core", "jvm")
            }
            assertContains(failure.message.orEmpty(), "Cannot parse generated POM")
        } finally {
            pom.delete()
        }
    }

    @Test
    fun `edge encoding is reversible and malformed input is refused`() {
        val encoded = CheckPublicationReadinessTask.encodeEdge(":ffmpeg", ":output")
        assertEquals(
            CheckPublicationReadinessTask.ProjectEdge(":ffmpeg", ":output"),
            CheckPublicationReadinessTask.decodeEdge(encoded),
        )
        assertFailsWith<IllegalArgumentException> { CheckPublicationReadinessTask.decodeEdge(":ffmpeg") }
    }

    @Test
    fun `a self dependency is never an unpublishable sibling`() {
        val report = CheckPublicationReadinessTask.evaluate(
            publishingModules = setOf(":core"),
            publications = listOf(completePom(":core")),
            edges = setOf(CheckPublicationReadinessTask.ProjectEdge(":core", ":core")),
        )

        CheckPublicationReadinessTask.requireReady(report)
        assertTrue(report.siblingFindings.isEmpty())
    }

    // ── the three checks Central actually enforces ─────────────────────────────────────────

    @Test
    fun `a publication with no developer is refused`() {
        val report = CheckPublicationReadinessTask.evaluate(
            publishingModules = setOf(":core"),
            publications = listOf(completePom(":core").copy(developers = emptyList())),
            edges = emptySet(),
        )

        val failure = assertFailsWith<GradleException> { CheckPublicationReadinessTask.requireReady(report) }
        assertContains(failure.message.orEmpty(), "developers")
        assertContains(failure.message.orEmpty(), "MavenPom.developers")
    }

    @Test
    fun `a developer entry with only a name is enough`() {
        val report = CheckPublicationReadinessTask.evaluate(
            publishingModules = setOf(":core"),
            publications = listOf(
                completePom(":core").copy(
                    developers = listOf(CheckPublicationReadinessTask.Developer(id = null, name = "yuroyami", url = null)),
                ),
            ),
            edges = emptySet(),
        )

        CheckPublicationReadinessTask.requireReady(report)
    }

    @Test
    fun `an empty developer entry does not count`() {
        val report = CheckPublicationReadinessTask.evaluate(
            publishingModules = setOf(":core"),
            publications = listOf(
                completePom(":core").copy(
                    developers = listOf(CheckPublicationReadinessTask.Developer(id = " ", name = "", url = "https://example.invalid")),
                ),
            ),
            edges = emptySet(),
        )

        assertFailsWith<GradleException> { CheckPublicationReadinessTask.requireReady(report) }
    }

    @Test
    fun `an io github namespace has to match the repository owner`() {
        // Central grants io.github.<user> on proof of owning that account, so publishing
        // io.github.yuroyami out of somebody else's repository cannot work.
        val mismatched = CheckPublicationReadinessTask.evaluate(
            publishingModules = setOf(":core"),
            publications = listOf(
                completePom(":core").copy(
                    groupId = "io.github.yuroyami",
                    scm = CheckPublicationReadinessTask.Scm(
                        connection = "scm:git:https://github.com/someoneelse/KitePlayer.git",
                        developerConnection = "scm:git:ssh://git@github.com/someoneelse/KitePlayer.git",
                        url = "https://github.com/someoneelse/KitePlayer",
                    ),
                ),
            ),
            edges = emptySet(),
        )
        val failure = assertFailsWith<GradleException> { CheckPublicationReadinessTask.requireReady(mismatched) }
        assertContains(failure.message.orEmpty(), "does not match the GitHub account")

        val matched = CheckPublicationReadinessTask.evaluate(
            publishingModules = setOf(":core"),
            publications = listOf(
                completePom(":core").copy(
                    groupId = "io.github.yuroyami",
                    scm = CheckPublicationReadinessTask.Scm(
                        connection = "scm:git:https://github.com/yuroyami/KitePlayer.git",
                        developerConnection = "scm:git:ssh://git@github.com/yuroyami/KitePlayer.git",
                        url = "https://github.com/yuroyami/KitePlayer",
                    ),
                ),
            ),
            edges = emptySet(),
        )
        CheckPublicationReadinessTask.requireReady(matched)
    }

    @Test
    fun `a publication with no scm URL is not also reported as a namespace mismatch`() {
        // One root cause, one finding. The missing scm is already its own line.
        val report = CheckPublicationReadinessTask.evaluate(
            publishingModules = setOf(":core"),
            publications = listOf(completePom(":core").copy(scm = null)),
            edges = emptySet(),
        )
        assertEquals(listOf("scm"), report.pomFindings.map { it.field })
    }

    @Test
    fun `a group outside the io github namespace is left alone`() {
        // Somebody else's namespace rule is somebody else's business; this check knows one rule.
        val report = CheckPublicationReadinessTask.evaluate(
            publishingModules = setOf(":core"),
            publications = listOf(completePom(":core").copy(groupId = "com.example.kite")),
            edges = emptySet(),
        )
        CheckPublicationReadinessTask.requireReady(report)
    }

    @Test
    fun `an unsigned build is only a failure on a run that must publish`() {
        fun report(signingConfigured: Boolean, requireSigning: Boolean) =
            CheckPublicationReadinessTask.evaluate(
                publishingModules = setOf(":core"),
                publications = listOf(completePom(":core")),
                edges = emptySet(),
                signingConfigured = signingConfigured,
                requireSigning = requireSigning,
            )

        // The ordinary local run: no key, and that is fine.
        CheckPublicationReadinessTask.requireReady(report(signingConfigured = false, requireSigning = false))
        CheckPublicationReadinessTask.requireReady(report(signingConfigured = true, requireSigning = true))

        val failure = assertFailsWith<GradleException> {
            CheckPublicationReadinessTask.requireReady(report(signingConfigured = false, requireSigning = true))
        }
        assertContains(failure.message.orEmpty(), "unsigned")
        assertContains(failure.message.orEmpty(), "signingInMemoryKey")
    }

    private fun completePom(modulePath: String): CheckPublicationReadinessTask.PomSnapshot =
        CheckPublicationReadinessTask.PomSnapshot(
            modulePath = modulePath,
            publicationName = "kotlinMultiplatform",
            groupId = "io.github.yuroyami",
            version = "0.0.1",
            name = "KitePlayer",
            description = "A player.",
            licences = listOf(CheckPublicationReadinessTask.Licence("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0")),
            scm = CheckPublicationReadinessTask.Scm(
                connection = "scm:git:https://github.com/yuroyami/KitePlayer.git",
                developerConnection = "scm:git:ssh://git@github.com/yuroyami/KitePlayer.git",
                url = "https://github.com/yuroyami/KitePlayer",
            ),
            developers = listOf(
                CheckPublicationReadinessTask.Developer("yuroyami", "yuroyami", "https://github.com/yuroyami"),
            ),
        )
}
