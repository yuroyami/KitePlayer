/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PublicationReadinessTest {

    @Test
    fun `a fully configured publication graph passes`() {
        val report = PublicationReadiness.evaluate(
            publishingModules = setOf(":core", ":rt"),
            publications = listOf(completePom(":core"), completePom(":rt")),
            edges = setOf(PublicationReadiness.ProjectEdge(":core", ":rt")),
        )

        PublicationReadiness.requireReady(report)
        assertEquals(
            "Publication readiness inputs: 2 publishing modules, 2 generated POMs, 1 project dependency edge.\n" +
                "Project dependency edges: :core -> :rt\n" +
                "POM findings (0): none\nSibling-publishability findings (0): none\n" +
                "Signing findings (0): none",
            PublicationReadiness.render(report),
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
            PublicationReadiness.ProjectEdge(":ffmpeg", ":core"),
            PublicationReadiness.ProjectEdge(":output", ":core"),
            PublicationReadiness.ProjectEdge(":view", ":core"),
            PublicationReadiness.ProjectEdge(":mobile", ":core"),
            PublicationReadiness.ProjectEdge(":mobile", ":ffmpeg"),
            PublicationReadiness.ProjectEdge(":mobile", ":output"),
            PublicationReadiness.ProjectEdge(":mobile", ":view"),
            PublicationReadiness.ProjectEdge(":compose-interop", ":core"),
            PublicationReadiness.ProjectEdge(":compose-interop", ":mobile"),
            PublicationReadiness.ProjectEdge(":compose-video", ":core"),
            PublicationReadiness.ProjectEdge(":compose-video", ":ffmpeg"),
            PublicationReadiness.ProjectEdge(":compose-video", ":mobile"),
            PublicationReadiness.ProjectEdge(":compose-video", ":output"),
            PublicationReadiness.ProjectEdge(":phone", ":mobile"),
            PublicationReadiness.ProjectEdge(":phone", ":view"),
            PublicationReadiness.ProjectEdge(":compose", ":compose-interop"),
            PublicationReadiness.ProjectEdge(":compose", ":compose-video"),
            PublicationReadiness.ProjectEdge(":compose", ":phone"),
        )
        val report = PublicationReadiness.evaluate(
            publishingModules = modules,
            publications = modules.map(::completePom),
            edges = edges,
        )

        PublicationReadiness.requireReady(report)
        assertEquals(modules.size, report.publishingModuleCount)
        assertEquals(edges.size, report.dependencyEdgeCount)
        assertTrue(report.siblingFindings.isEmpty())
    }

    @Test
    fun `a missing POM field fails naming the field and fix`() {
        val report = PublicationReadiness.evaluate(
            publishingModules = setOf(":core"),
            publications = listOf(completePom(":core").copy(description = "  ")),
            edges = emptySet(),
        )

        val failure = assertFailsWith<BuildFailure> {
            PublicationReadiness.requireReady(report)
        }
        assertContains(failure.message.orEmpty(), ":core")
        assertContains(failure.message.orEmpty(), "description")
        assertContains(failure.message.orEmpty(), "Fix:")
        assertContains(failure.message.orEmpty(), "settings.publishing.pom.description")
    }

    @Test
    fun `a publishing module depending on a nonpublishing sibling fails naming both and fix`() {
        val report = PublicationReadiness.evaluate(
            publishingModules = setOf(":core"),
            publications = listOf(completePom(":core")),
            edges = setOf(PublicationReadiness.ProjectEdge(":core", ":rt")),
        )

        val failure = assertFailsWith<BuildFailure> {
            PublicationReadiness.requireReady(report)
        }
        assertContains(failure.message.orEmpty(), ":core")
        assertContains(failure.message.orEmpty(), ":rt")
        assertContains(failure.message.orEmpty(), "Fix:")
        assertContains(failure.message.orEmpty(), "settings.publishing")
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
        val report = PublicationReadiness.evaluate(
            publishingModules = setOf(":zulu", ":alpha"),
            publications = listOf(second, first, completePom(":zulu")),
            edges = setOf(
                PublicationReadiness.ProjectEdge(":zulu", ":missing-b"),
                PublicationReadiness.ProjectEdge(":alpha", ":missing-a"),
                PublicationReadiness.ProjectEdge(":sample", ":also-missing"),
            ),
        )

        assertEquals(listOf("name", "description", "licence", "scm"), report.pomFindings.map { it.field })
        assertTrue(report.pomFindings.all { it.modulePath == ":alpha" })
        assertTrue(report.pomFindings.all { it.publications == listOf("jvm", "kotlinMultiplatform") })
        assertEquals(listOf(":alpha", ":zulu"), report.siblingFindings.map { it.modulePath })
    }

    @Test
    fun `a publishing module with no generated POM is a finding`() {
        val report = PublicationReadiness.evaluate(
            publishingModules = setOf(":core"),
            publications = emptyList(),
            edges = emptySet(),
        )

        val failure = assertFailsWith<BuildFailure> { PublicationReadiness.requireReady(report) }
        assertContains(failure.message.orEmpty(), ":core")
        assertContains(failure.message.orEmpty(), "no generated Maven POM")
        assertContains(failure.message.orEmpty(), "./kotlin publish mavenLocal")
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

            val parsed = PublicationReadiness.parsePom(pom, ":core", "jvm")

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

            val failure = assertFailsWith<BuildFailure> {
                PublicationReadiness.parsePom(pom, ":core", "jvm")
            }
            assertContains(failure.message.orEmpty(), "Cannot parse generated POM")
        } finally {
            pom.delete()
        }
    }

    @Test
    fun `edge encoding is reversible and malformed input is refused`() {
        val encoded = PublicationReadiness.encodeEdge(":ffmpeg", ":output")
        assertEquals(
            PublicationReadiness.ProjectEdge(":ffmpeg", ":output"),
            PublicationReadiness.decodeEdge(encoded),
        )
        assertFailsWith<IllegalArgumentException> { PublicationReadiness.decodeEdge(":ffmpeg") }
    }

    @Test
    fun `a self dependency is never an unpublishable sibling`() {
        val report = PublicationReadiness.evaluate(
            publishingModules = setOf(":core"),
            publications = listOf(completePom(":core")),
            edges = setOf(PublicationReadiness.ProjectEdge(":core", ":core")),
        )

        PublicationReadiness.requireReady(report)
        assertTrue(report.siblingFindings.isEmpty())
    }

    // ── the three checks Central actually enforces ─────────────────────────────────────────

    @Test
    fun `a publication with no developer is refused`() {
        val report = PublicationReadiness.evaluate(
            publishingModules = setOf(":core"),
            publications = listOf(completePom(":core").copy(developers = emptyList())),
            edges = emptySet(),
        )

        val failure = assertFailsWith<BuildFailure> { PublicationReadiness.requireReady(report) }
        assertContains(failure.message.orEmpty(), "developers")
        assertContains(failure.message.orEmpty(), "settings.publishing.pom.developers")
    }

    @Test
    fun `a developer entry with only a name is enough`() {
        val report = PublicationReadiness.evaluate(
            publishingModules = setOf(":core"),
            publications = listOf(
                completePom(":core").copy(
                    developers = listOf(PublicationReadiness.Developer(id = null, name = "yuroyami", url = null)),
                ),
            ),
            edges = emptySet(),
        )

        PublicationReadiness.requireReady(report)
    }

    @Test
    fun `an empty developer entry does not count`() {
        val report = PublicationReadiness.evaluate(
            publishingModules = setOf(":core"),
            publications = listOf(
                completePom(":core").copy(
                    developers = listOf(PublicationReadiness.Developer(id = " ", name = "", url = "https://example.invalid")),
                ),
            ),
            edges = emptySet(),
        )

        assertFailsWith<BuildFailure> { PublicationReadiness.requireReady(report) }
    }

    @Test
    fun `an io github namespace has to match the repository owner`() {
        // Central grants io.github.<user> on proof of owning that account, so publishing
        // io.github.yuroyami out of somebody else's repository cannot work.
        val mismatched = PublicationReadiness.evaluate(
            publishingModules = setOf(":core"),
            publications = listOf(
                completePom(":core").copy(
                    groupId = "io.github.yuroyami",
                    scm = PublicationReadiness.Scm(
                        connection = "scm:git:https://github.com/someoneelse/KitePlayer.git",
                        developerConnection = "scm:git:ssh://git@github.com/someoneelse/KitePlayer.git",
                        url = "https://github.com/someoneelse/KitePlayer",
                    ),
                ),
            ),
            edges = emptySet(),
        )
        val failure = assertFailsWith<BuildFailure> { PublicationReadiness.requireReady(mismatched) }
        assertContains(failure.message.orEmpty(), "does not match the GitHub account")

        val matched = PublicationReadiness.evaluate(
            publishingModules = setOf(":core"),
            publications = listOf(
                completePom(":core").copy(
                    groupId = "io.github.yuroyami",
                    scm = PublicationReadiness.Scm(
                        connection = "scm:git:https://github.com/yuroyami/KitePlayer.git",
                        developerConnection = "scm:git:ssh://git@github.com/yuroyami/KitePlayer.git",
                        url = "https://github.com/yuroyami/KitePlayer",
                    ),
                ),
            ),
            edges = emptySet(),
        )
        PublicationReadiness.requireReady(matched)
    }

    @Test
    fun `a publication with no scm URL is not also reported as a namespace mismatch`() {
        // One root cause, one finding. The missing scm is already its own line.
        val report = PublicationReadiness.evaluate(
            publishingModules = setOf(":core"),
            publications = listOf(completePom(":core").copy(scm = null)),
            edges = emptySet(),
        )
        assertEquals(listOf("scm"), report.pomFindings.map { it.field })
    }

    @Test
    fun `a group outside the io github namespace is left alone`() {
        // Somebody else's namespace rule is somebody else's business; this check knows one rule.
        val report = PublicationReadiness.evaluate(
            publishingModules = setOf(":core"),
            publications = listOf(completePom(":core").copy(groupId = "com.example.kite")),
            edges = emptySet(),
        )
        PublicationReadiness.requireReady(report)
    }

    @Test
    fun `an unsigned build is only a failure on a run that must publish`() {
        fun report(signingConfigured: Boolean, requireSigning: Boolean) =
            PublicationReadiness.evaluate(
                publishingModules = setOf(":core"),
                publications = listOf(completePom(":core")),
                edges = emptySet(),
                signingConfigured = signingConfigured,
                requireSigning = requireSigning,
            )

        // The ordinary local run: no key, and that is fine.
        PublicationReadiness.requireReady(report(signingConfigured = false, requireSigning = false))
        PublicationReadiness.requireReady(report(signingConfigured = true, requireSigning = true))

        val failure = assertFailsWith<BuildFailure> {
            PublicationReadiness.requireReady(report(signingConfigured = false, requireSigning = true))
        }
        assertContains(failure.message.orEmpty(), "unsigned")
        assertContains(failure.message.orEmpty(), "KOTLIN_TOOLCHAIN_SIGNING_KEY")
    }

    private fun completePom(modulePath: String): PublicationReadiness.PomSnapshot =
        PublicationReadiness.PomSnapshot(
            modulePath = modulePath,
            publicationName = "kotlinMultiplatform",
            groupId = "io.github.yuroyami",
            version = "0.0.1",
            name = "KitePlayer",
            description = "A player.",
            licences = listOf(PublicationReadiness.Licence("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0")),
            scm = PublicationReadiness.Scm(
                connection = "scm:git:https://github.com/yuroyami/KitePlayer.git",
                developerConnection = "scm:git:ssh://git@github.com/yuroyami/KitePlayer.git",
                url = "https://github.com/yuroyami/KitePlayer",
            ),
            developers = listOf(
                PublicationReadiness.Developer("yuroyami", "yuroyami", "https://github.com/yuroyami"),
            ),
        )
}
