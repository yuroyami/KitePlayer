/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

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
object DuplicateClasses {
    /** Checks [jvm] and [android]: each maps a module name to the folders of its compiled classes. */
    fun check(jvm: Map<String, List<File>>, android: Map<String, List<File>>, log: (String) -> Unit) {
        val findings = listOf("JVM" to jvm, "Android" to android).map { (platform, folders) ->
            val byModule = classesByModule(folders)
            log("$platform: ${byModule.values.sumOf { it.size }} classes in ${byModule.size} published modules")
            platform to duplicates(byModule)
        }
        render(findings)?.let { throw BuildFailure(it) }
    }

    /** The class names in each module's folders. */
    fun classesByModule(folders: Map<String, List<File>>): Map<String, Set<String>> = folders.toSortedMap().mapValues { (_, dirs) ->
        dirs.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }
                .filter(::isClassFile).map { it.removeSuffix(".class").replace('/', '.') }.toList()
        }.toSortedSet()
    }

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
            ) + lines + "Move the class into one module, or give one of them a name of its own."
            ).joinToString("\n")
    }
}
