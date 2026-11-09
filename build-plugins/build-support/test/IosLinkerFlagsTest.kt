/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The README's Other Linker Flags for a static iOS framework hold every iOS linker option that a
 * published klib's cinterop declares (#771). Kotlin embeds those only in a dynamic framework, so a
 * flag missing from the README is an undefined symbol in an app that links a static one.
 */
class IosLinkerFlagsTest {

    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "README.md").isFile && File(it, "library.module-template.yaml").isFile }
        ?: error("no repository root above ${System.getProperty("user.dir")}")

    /** The flags of every `linkerOpts.ios` line in [def], each `-framework X` as one flag. */
    private fun flagsOf(def: File): Set<String> = def.readLines()
        .filter { it.startsWith("linkerOpts.ios") && it.substringBefore('=').trim() == "linkerOpts.ios" }
        .flatMap { flags(it.substringAfter('=')) }
        .toSet()

    private fun flags(line: String): List<String> {
        val words = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var at = 0
        while (at < words.size) {
            out += if (words[at] == "-framework" && at + 1 < words.size) "-framework ${words[++at]}" else words[at]
            at++
        }
        return out
    }

    @Test
    fun theReadmeLinksEveryLibraryAStaticFrameworkNeeds() {
        val readme = File(root, "README.md").readText()
        val block = readme.substringAfter("which needs these Other Linker Flags", "")
            .substringAfter("```text\n", "").substringBefore("\n```", "")
        assertTrue(block.isNotBlank(), "the README has no linker flags block")
        val listed = flags(block).toSet()
        val defs = root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "def" }
            .toList()
        // KiteFFmpeg's own options, when the sibling checkout is beside this one.
        val sibling = File(root.parentFile, "KiteFFmpeg/kiteffmpeg/interop/ffmpeg.def").takeIf { it.isFile }
        val needed = (defs + listOfNotNull(sibling)).associateWith(::flagsOf).filterValues { it.isNotEmpty() }
        assertTrue(needed.isNotEmpty(), "no def file declares iOS linker options, so this test reads nothing")
        val missing = needed.flatMap { (def, flags) -> (flags - listed).map { "$it (${def.name})" } }
        assertTrue(missing.isEmpty(), "the README's static framework flags lack $missing")
    }
}
