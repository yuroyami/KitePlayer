package io.github.yuroyami.kiteplayer.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Fails when two published modules compile a class of the same name (#420).
 *
 * An app that depends on both gets both. Android's dexer refuses the pair with "Type ... is defined
 * multiple times", and the JVM loads whichever comes first on the class path and hides the other.
 * The ABI check reads one module at a time, so it cannot see this. The check reads the compiled
 * classes of every published module, internal ones included, because the dexer refuses those too.
 * The JVM and Android classes are read apart, because an app packages one or the other.
 */
@DisableCachingByDefault(because = "This verification task has no outputs.")
abstract class CheckDuplicateClassesTask : DefaultTask() {

    /** The class directories of the main JVM compilation of every published module. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jvmClassDirectories: ConfigurableFileCollection

    /** The class directories of the main Android compilation of every published module. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val androidClassDirectories: ConfigurableFileCollection

    /** Each published module's path, mapped to its directory relative to [repositoryRoot]. */
    @get:Input
    abstract val publishingProjectDirectories: MapProperty<String, String>

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    init {
        group = "verification"
        description = "Check that no two published modules compile a class of the same name."
        publishingProjectDirectories.convention(emptyMap())
    }

    @TaskAction
    fun check() {
        val root = repositoryRoot.get().asFile
        val directories = publishingProjectDirectories.get()
        val findings = listOf("JVM" to jvmClassDirectories, "Android" to androidClassDirectories).map { (platform, classes) ->
            val byModule = classesByModule(root, directories, classes)
            logger.lifecycle(
                "[checkDuplicateClasses] $platform: ${byModule.values.sumOf { it.size }} classes in " +
                    "${byModule.size} published modules",
            )
            platform to duplicates(byModule)
        }
        val report = render(findings)
        if (report != null) throw GradleException(report)
    }

    private fun classesByModule(
        root: File,
        directories: Map<String, String>,
        classes: ConfigurableFileCollection,
    ): Map<String, Set<String>> {
        val byModule = sortedMapOf<String, MutableSet<String>>()
        classes.asFileTree.visit {
            if (isDirectory) return@visit
            val name = relativePath.pathString
            if (!isClassFile(name)) return@visit
            byModule.getOrPut(ownerOf(file.relativeTo(root).invariantSeparatorsPath, directories)) { sortedSetOf() } +=
                name.removeSuffix(".class").replace('/', '.')
        }
        return byModule
    }

    companion object {
        /** A class the dexer and the class loader name: not a multi-release copy, not `module-info`. */
        fun isClassFile(relativePath: String): Boolean =
            relativePath.endsWith(".class") &&
                !relativePath.startsWith("META-INF/") &&
                relativePath.substringAfterLast('/') != "module-info.class"

        /** Each class that more than one module compiles, mapped to those modules in order. */
        fun duplicates(classesByModule: Map<String, Set<String>>): Map<String, List<String>> {
            val owners = sortedMapOf<String, MutableList<String>>()
            classesByModule.toSortedMap().forEach { (module, classes) ->
                classes.forEach { owners.getOrPut(it) { mutableListOf() } += module }
            }
            return owners.filterValues { it.size > 1 }
        }

        /** The failure message for [findings], one line per class, or null when there is none. */
        fun render(findings: List<Pair<String, Map<String, List<String>>>>): String? {
            val lines = findings.flatMap { (platform, duplicates) ->
                duplicates.map { (name, modules) -> "  $platform: $name is compiled by ${modules.joinToString(" and ")}" }
            }
            if (lines.isEmpty()) return null
            return (
                listOf(
                    "Two published modules compile a class of the same name. An app that depends on both cannot be " +
                        "built for Android, and on the JVM one of the two classes hides the other:",
                ) +
                    lines +
                    "Move the class into one module, or give one of them a name of its own."
                ).joinToString("\n")
        }

        private fun ownerOf(relativePath: String, directories: Map<String, String>): String =
            directories.entries
                .filter { (_, directory) -> relativePath.startsWith(directory.trim('/') + "/") }
                .maxByOrNull { (_, directory) -> directory.length }
                ?.key
                ?: throw GradleException(
                    "Compiled class '$relativePath' belongs to no publishing project directory. " +
                        "Known directories: ${directories.toSortedMap()}.",
                )
    }
}
