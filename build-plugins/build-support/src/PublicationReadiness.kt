/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks the POM metadata that Maven Central needs, and that no published module depends on a
 * module that is not published. It reads the POMs of a local publish, so it runs after
 * `./kotlin publish mavenLocal`.
 */
object PublicationReadiness {
    data class PomSnapshot(
        val modulePath: String,
        val publicationName: String,
        val groupId: String?,
        val version: String?,
        val name: String?,
        val description: String?,
        val licences: List<Licence>,
        val scm: Scm?,
        val developers: List<Developer> = emptyList(),
    )

    data class Developer(val id: String?, val name: String?, val url: String?)

    data class Licence(val name: String?, val url: String?)

    data class Scm(
        val connection: String?,
        val developerConnection: String?,
        val url: String?,
    )

    data class ProjectEdge(val from: String, val to: String)

    data class Finding(
        val modulePath: String,
        val field: String,
        val publications: List<String>,
        val detail: String,
        val fix: String,
    )

    data class ReadinessReport(
        val publishingModuleCount: Int,
        val generatedPomCount: Int,
        val dependencyEdges: List<ProjectEdge>,
        val pomFindings: List<Finding>,
        val siblingFindings: List<Finding>,
        val signingFindings: List<Finding> = emptyList(),
    ) {
        val dependencyEdgeCount: Int get() = dependencyEdges.size
    }

    const val EDGE_SEPARATOR: String = " -> "

    fun encodeEdge(from: String, to: String): String = "$from$EDGE_SEPARATOR$to"

    fun decodeEdge(encoded: String): ProjectEdge {
        val parts = encoded.split(EDGE_SEPARATOR)
        require(parts.size == 2 && parts.all(String::isNotBlank)) {
            "Malformed project dependency edge '$encoded'. Expected '<from>$EDGE_SEPARATOR<to>'."
        }
        return ProjectEdge(parts[0], parts[1])
    }

    fun evaluate(
        publishingModules: Set<String>,
        publications: List<PomSnapshot>,
        edges: Set<ProjectEdge>,
        signingConfigured: Boolean = true,
        requireSigning: Boolean = false,
    ): ReadinessReport {
        val pomFindings = buildList {
            for (modulePath in publishingModules.sorted()) {
                val modulePoms = publications
                    .filter { publication -> publication.modulePath == modulePath }
                    .sortedBy(PomSnapshot::publicationName)
                if (modulePoms.isEmpty()) {
                    add(
                        Finding(
                            modulePath = modulePath,
                            field = "publication",
                            publications = emptyList(),
                            detail = "no generated Maven POM exists.",
                            fix = "Enable publishing in its module.yaml, then run ./kotlin publish mavenLocal before this check.",
                        ),
                    )
                    continue
                }

                addMissingField(
                    modulePath,
                    "group",
                    modulePoms.filter { it.groupId.isBlankValue() },
                    "POM group is missing or blank.",
                    "Set settings.publishing.group.",
                )
                addMissingField(
                    modulePath,
                    "version",
                    modulePoms.filter { it.version.isBlankValue() },
                    "POM version is missing or blank.",
                    "Set settings.publishing.version.",
                )
                addMissingField(
                    modulePath,
                    "name",
                    modulePoms.filter { it.name.isBlankValue() },
                    "POM name is missing or blank.",
                    "Set settings.publishing.pom.name.",
                )
                addMissingField(
                    modulePath,
                    "description",
                    modulePoms.filter { it.description.isBlankValue() },
                    "POM description is missing or blank.",
                    "Set settings.publishing.pom.description.",
                )
                addMissingField(
                    modulePath,
                    "licence",
                    modulePoms.filter { publication ->
                        publication.licences.none { licence ->
                            !licence.name.isBlankValue() && !licence.url.isBlankValue()
                        }
                    },
                    "POM licence needs at least one nonblank name and URL.",
                    "Set settings.publishing.pom.licenses with a name and a url.",
                )
                addMissingField(
                    modulePath,
                    "scm",
                    modulePoms.filter { publication ->
                        publication.scm == null ||
                            publication.scm.connection.isBlankValue() ||
                            publication.scm.developerConnection.isBlankValue() ||
                            publication.scm.url.isBlankValue()
                    },
                    "POM scm needs nonblank connection, developerConnection and URL.",
                    "Set settings.publishing.pom.scm with connection, developerConnection and url.",
                )
                // Maven Central REQUIRES this and rejects the bundle without it. The check is
                // here rather than at upload because a rejected bundle is found at the end of
                // a release, and this is found in seconds.
                addMissingField(
                    modulePath,
                    "developers",
                    modulePoms.filter { publication ->
                        publication.developers.none { developer ->
                            !developer.id.isBlankValue() || !developer.name.isBlankValue()
                        }
                    },
                    "POM developers needs at least one entry with an id or a name.",
                    "Set settings.publishing.pom.developers with id, name and url.",
                )
                addMissingField(
                    modulePath,
                    "namespace",
                    modulePoms.filter { publication -> !namespaceMatchesScm(publication) },
                    "the io.github.<user> group does not match the GitHub account in the scm URL.",
                    "Central grants io.github.<user> on proof of owning that account, so the " +
                        "group and the repository owner have to be the same user.",
                )
            }
        }

        val siblingFindings = edges
            .filter { edge -> edge.from in publishingModules && edge.to !in publishingModules }
            .sortedWith(compareBy(ProjectEdge::from, ProjectEdge::to))
            .map { edge ->
                Finding(
                    modulePath = edge.from,
                    field = "sibling-publishability",
                    publications = emptyList(),
                    detail = "publishing module ${edge.from} depends on non-publishing sibling ${edge.to}.",
                    fix = "Enable settings.publishing in ${edge.to}/module.yaml, or remove or replace the dependency of ${edge.from}.",
                )
            }

        val signingFindings = if (requireSigning && !signingConfigured) {
            listOf(
                Finding(
                    modulePath = "<build>",
                    field = "signing",
                    publications = emptyList(),
                    detail = "this run must be publishable and has no signing key, so every artifact would be unsigned.",
                    fix = "Export KOTLIN_TOOLCHAIN_SIGNING_KEY (and its passphrase) before publishing.",
                ),
            )
        } else {
            emptyList()
        }

        return ReadinessReport(
            publishingModuleCount = publishingModules.size,
            generatedPomCount = publications.size,
            dependencyEdges = edges.sortedWith(compareBy(ProjectEdge::from, ProjectEdge::to)),
            pomFindings = pomFindings,
            siblingFindings = siblingFindings,
            signingFindings = signingFindings,
        )
    }

    fun parsePom(file: File, modulePath: String, publicationName: String): PomSnapshot {
        val project = try {
            newDocumentBuilder().parse(file).documentElement
        } catch (failure: Exception) {
            throw BuildFailure("Cannot parse generated POM ${file.absolutePath}: ${failure.message}", failure)
        }
        val licences = project.directElement("licenses")
            ?.directElements("license")
            .orEmpty()
            .map { licence -> Licence(licence.directText("name"), licence.directText("url")) }
        val scmElement = project.directElement("scm")
        val developers = project.directElement("developers")
            ?.directElements("developer")
            .orEmpty()
            .map { developer ->
                Developer(
                    id = developer.directText("id"),
                    name = developer.directText("name"),
                    url = developer.directText("url"),
                )
            }
        return PomSnapshot(
            modulePath = modulePath,
            publicationName = publicationName,
            groupId = project.directText("groupId"),
            version = project.directText("version"),
            name = project.directText("name"),
            description = project.directText("description"),
            licences = licences,
            scm = scmElement?.let { scm ->
                Scm(
                    connection = scm.directText("connection"),
                    developerConnection = scm.directText("developerConnection"),
                    url = scm.directText("url"),
                )
            },
            developers = developers,
        )
    }

    fun render(report: ReadinessReport): String = buildString {
        append("Publication readiness inputs: ")
            .append(report.publishingModuleCount).append(" publishing modules, ")
            .append(report.generatedPomCount).append(" generated POMs, ")
            .append(report.dependencyEdgeCount).append(" project dependency edge")
        if (report.dependencyEdgeCount != 1) append('s')
        append(".\nProject dependency edges:")
        if (report.dependencyEdges.isEmpty()) {
            append(" none")
        } else {
            append(' ')
            append(report.dependencyEdges.joinToString(", ") { edge -> encodeEdge(edge.from, edge.to) })
        }
        append('\n')
        appendFindings("POM findings", report.pomFindings)
        append('\n')
        appendFindings("Sibling-publishability findings", report.siblingFindings)
        append('\n')
        appendFindings("Signing findings", report.signingFindings)
    }

    fun requireReady(report: ReadinessReport) {
        if (report.pomFindings.isNotEmpty() ||
            report.siblingFindings.isNotEmpty() ||
            report.signingFindings.isNotEmpty()
        ) {
            throw BuildFailure("Publication readiness failed.\n${render(report)}")
        }
    }

    /**
     * Whether an `io.github.<user>` group agrees with the GitHub account owning the repository.
     *
     * Central grants that namespace on proof of owning the account, so the two disagreeing
     * means the coordinates cannot be published at all. Any other group shape is somebody
     * else's namespace rule and is left alone.
     */
    private fun namespaceMatchesScm(publication: PomSnapshot): Boolean {
        val group = publication.groupId ?: return true
        val user = group.removePrefix("io.github.").takeIf { group.startsWith("io.github.") } ?: return true
        val owner = user.substringBefore('.')
        // No scm URL at all is already a finding of its own; saying it twice helps nobody.
        val url = publication.scm?.url ?: return true
        val path = url.substringAfter("github.com", missingDelimiterValue = "").trim('/', ':')
        return path.substringBefore('/').equals(owner, ignoreCase = true)
    }

    private fun StringBuilder.appendFindings(label: String, findings: List<Finding>) {
        append(label).append(" (").append(findings.size).append("):")
        if (findings.isEmpty()) {
            append(" none")
            return
        }
        for (finding in findings) {
            append("\n- ").append(finding.modulePath).append(": ").append(finding.detail)
            if (finding.publications.isNotEmpty()) {
                append(" [publications: ").append(finding.publications.joinToString(", ")).append(']')
            }
            append(" Fix: ").append(finding.fix)
        }
    }

    private fun MutableList<Finding>.addMissingField(
        modulePath: String,
        field: String,
        deficient: List<PomSnapshot>,
        detail: String,
        fix: String,
    ) {
        if (deficient.isEmpty()) return
        add(
            Finding(
                modulePath = modulePath,
                field = field,
                publications = deficient.map(PomSnapshot::publicationName).distinct().sorted(),
                detail = detail,
                fix = fix,
            ),
        )
    }

    private fun String?.isBlankValue(): Boolean = this == null || isBlank()

    private fun Element.directText(name: String): String? =
        directElement(name)?.textContent?.trim()?.takeIf(String::isNotEmpty)

    private fun Element.directElement(name: String): Element? = directElements(name).firstOrNull()

    private fun Element.directElements(name: String): List<Element> = buildList {
        val children = childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element && (child.localName ?: child.nodeName) == name) add(child)
        }
    }

    /**
     * Checks the POMs that a local publish of [artifactId] at [version] wrote into [repository]:
     * the root POM and one per platform (`<artifactId>-<platform>`). A dependency on another module
     * of [projectModules] counts as an edge, and that module must have a POM at the same version.
     */
    fun checkLocalPublication(
        repository: File,
        group: String,
        artifactId: String,
        version: String,
        projectModules: Collection<String>,
        signingConfigured: Boolean,
        requireSigning: Boolean,
        log: (String) -> Unit,
    ) {
        val groupDir = repository.resolve(group.replace('.', '/'))
        val siblings = projectModules.map { it.substringAfterLast('/') }.toSet()
        val own = artifactsOf(groupDir, artifactId, siblings)
        val snapshots = own.mapNotNull { artifact ->
            groupDir.resolve("$artifact/$version/$artifact-$version.pom").takeIf { it.isFile }?.let { pom ->
                parsePom(pom, artifactId, artifact.removePrefix(artifactId).removePrefix("-").ifEmpty { "root" })
            }
        }
        val edges = own.flatMap { artifact ->
            val pom = groupDir.resolve("$artifact/$version/$artifact-$version.pom")
            if (!pom.isFile) emptyList() else dependencyArtifacts(pom, group).mapNotNull { ownerOf(it, siblings) }
        }.filter { it != artifactId }.toSortedSet()
        val published = edges.filter { sibling -> groupDir.resolve("$sibling/$version/$sibling-$version.pom").isFile }.toSet()
        val report = evaluate(
            publishingModules = setOf(artifactId) + published,
            publications = snapshots,
            edges = edges.map { ProjectEdge(artifactId, it) }.toSet(),
            signingConfigured = signingConfigured,
            requireSigning = requireSigning,
        ).let { full -> full.copy(pomFindings = full.pomFindings.filter { it.modulePath == artifactId }) }
        log(render(report))
        requireReady(report)
    }

    /** The artifact folders of [artifactId]: itself and `<artifactId>-<platform>`, but not a longer sibling name. */
    private fun artifactsOf(groupDir: File, artifactId: String, siblings: Set<String>): List<String> =
        groupDir.listFiles().orEmpty().map { it.name }
            .filter { name -> ownerOf(name, siblings) == artifactId }
            .sorted()

    /** The module that publishes [artifact]: the longest module name that is the artifact or its prefix. */
    private fun ownerOf(artifact: String, modules: Set<String>): String? =
        modules.filter { artifact == it || artifact.startsWith("$it-") }.maxByOrNull { it.length }

    private fun dependencyArtifacts(pom: File, group: String): List<String> {
        val project = newDocumentBuilder().parse(pom).documentElement
        return project.directElement("dependencies")?.directElements("dependency").orEmpty()
            .filter { it.directText("groupId") == group }
            .mapNotNull { it.directText("artifactId") }
    }

    /** A parser that refuses doctypes and external entities. */
    private fun newDocumentBuilder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        setExpandEntityReferences(false)
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }.newDocumentBuilder()
}
