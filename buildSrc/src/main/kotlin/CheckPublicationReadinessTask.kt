package io.github.yuroyami.kiteplayer.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Checks publication metadata and project-dependency publication symmetry without publishing. */
@DisableCachingByDefault(because = "This verification task has no outputs.")
abstract class CheckPublicationReadinessTask : DefaultTask() {

    @get:Input
    abstract val publishingModules: SetProperty<String>

    @get:Input
    abstract val projectDependencyEdges: SetProperty<String>

    @get:Input
    abstract val publishingProjectDirectories: MapProperty<String, String>

    /**
     * True when this build has a signing key, so publications will actually be signed.
     *
     * Not a POM fact, which is why it is passed in. Maven Central rejects unsigned artifacts, and
     * an ordinary local run has no key, so an unsigned build is only a FINDING when
     * [requireSigning] says this run is heading for Central.
     */
    @get:Input
    abstract val signingConfigured: org.gradle.api.provider.Property<Boolean>

    /** True on a run that must be publishable to Central, so unsigned becomes a failure. */
    @get:Input
    abstract val requireSigning: org.gradle.api.provider.Property<Boolean>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatedPoms: ConfigurableFileCollection

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    init {
        group = "verification"
        description = "Check publication POM metadata and publishing-module dependency symmetry."
        publishingModules.convention(emptySet())
        projectDependencyEdges.convention(emptySet())
        publishingProjectDirectories.convention(emptyMap())
        signingConfigured.convention(false)
        requireSigning.convention(false)
    }

    @TaskAction
    fun check() {
        val root = repositoryRoot.get().asFile.toPath().toAbsolutePath().normalize()
        val moduleDirectories = publishingProjectDirectories.get()
        val snapshots = generatedPoms.files
            .sortedBy { file -> repositoryRelativePath(root, file) }
            .map { file ->
                val relativePath = repositoryRelativePath(root, file)
                val modulePath = ownerOf(relativePath, moduleDirectories)
                parsePom(file, modulePath, publicationName(relativePath))
            }
        val report = evaluate(
            publishingModules = publishingModules.get(),
            publications = snapshots,
            edges = projectDependencyEdges.get().map(::decodeEdge).toSet(),
            signingConfigured = signingConfigured.get(),
            requireSigning = requireSigning.get(),
        )
        logger.lifecycle(render(report))
        requireReady(report)
    }

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

    companion object {
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
                                fix = "Create at least one MavenPublication and its GenerateMavenPom task.",
                            ),
                        )
                        continue
                    }

                    addMissingField(
                        modulePath,
                        "group",
                        modulePoms.filter { it.groupId.isBlankValue() },
                        "POM group is missing or blank.",
                        "Set the publication group or the root project group.",
                    )
                    addMissingField(
                        modulePath,
                        "version",
                        modulePoms.filter { it.version.isBlankValue() },
                        "POM version is missing or blank.",
                        "Set the publication version or the root project version.",
                    )
                    addMissingField(
                        modulePath,
                        "name",
                        modulePoms.filter { it.name.isBlankValue() },
                        "POM name is missing or blank.",
                        "Configure MavenPom.name or POM_NAME.",
                    )
                    addMissingField(
                        modulePath,
                        "description",
                        modulePoms.filter { it.description.isBlankValue() },
                        "POM description is missing or blank.",
                        "Configure MavenPom.description or POM_DESCRIPTION.",
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
                        "Configure MavenPom.licenses or POM_LICENSE_NAME and POM_LICENSE_URL.",
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
                        "Configure MavenPom.scm or POM_SCM_CONNECTION, POM_SCM_DEV_CONNECTION and POM_SCM_URL.",
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
                        "Configure MavenPom.developers with id, name and url.",
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
                        fix = "Apply com.vanniktech.maven.publish to ${edge.to}, or remove or replace the project dependency from ${edge.from}.",
                    )
                }

            val signingFindings = if (requireSigning && !signingConfigured) {
                listOf(
                    Finding(
                        modulePath = "<build>",
                        field = "signing",
                        publications = emptyList(),
                        detail = "this run must be publishable and has no signing key, so every artifact would be unsigned.",
                        fix = "Export ORG_GRADLE_PROJECT_signingInMemoryKey (and its password) before publishing.",
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
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isXIncludeAware = false
                setExpandEntityReferences(false)
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            }
            val project = try {
                factory.newDocumentBuilder().parse(file).documentElement
            } catch (failure: Exception) {
                throw GradleException("Cannot parse generated POM ${file.absolutePath}: ${failure.message}", failure)
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
                throw GradleException("Publication readiness failed.\n${render(report)}")
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

        private fun repositoryRelativePath(root: Path, file: File): String {
            val candidate = file.toPath().toAbsolutePath().normalize()
            if (!candidate.startsWith(root)) {
                throw GradleException("Generated POM ${file.absolutePath} is outside repository root $root.")
            }
            return root.relativize(candidate).toString().replace(File.separatorChar, '/')
        }

        private fun ownerOf(relativePath: String, moduleDirectories: Map<String, String>): String {
            val candidates = moduleDirectories.entries.filter { (_, directory) ->
                val normalized = directory.trim('/').replace(File.separatorChar, '/')
                relativePath == normalized || relativePath.startsWith("$normalized/")
            }
            return candidates.maxByOrNull { (_, directory) -> directory.length }?.key
                ?: throw GradleException(
                    "Generated POM '$relativePath' belongs to no publishing project directory. " +
                        "Known directories: ${moduleDirectories.toSortedMap()}.",
                )
        }

        private fun publicationName(relativePath: String): String {
            val parts = relativePath.split('/')
            val marker = parts.indexOfLast { part -> part == "publications" }
            return if (marker >= 0 && marker + 1 < parts.size) parts[marker + 1] else File(relativePath).parentFile.name
        }
    }
}
