/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

import java.io.File

/**
 * Prevents a new engine, backend, subtitle or sample source file from naming the `kitert`
 * cinterop directly.
 *
 * The check reads every present `.kt` file in the `src`, `src@<platform>`, `test` and
 * `test@<platform>` folders of every module except `kiteplayer-output`, which owns the C sink
 * pointer, and `kiteplayer-rt`, which is the binding. It includes untracked sources: a new
 * coupling site must fail before it can be committed. Comments are removed before matching,
 * while string and character literals remain source text.
 */
object KitertCoupling {
    /** One source-tree measurement. Each matching file appears exactly once. */
    data class Measurement(
        val scannedFileCount: Int,
        val matchingFiles: Set<String>,
    )

    /** The exact source paths permitted to name the binding. */
    data class Baseline(val allowedFiles: Set<String>)


    const val BASELINE_FILE_NAME: String = "kitert-coupling-baseline.txt"
    const val ALLOWED_KITERT_FILE: String = "allowed_kitert_file"
    const val CNAMES_PATTERN: String = "cnames.structs.kprt_"
    const val CINTEROP_PACKAGE_PATTERN: String = "kiteplayer.rt.cinterop"

    /** The only two modules that own the binding boundary by design. */
    val EXCLUDED_MODULE_PATHS: Set<String> = linkedSetOf(
        "kiteplayer-output",
        "kiteplayer-rt",
    )

    /**
     * Pure module selector used by root registration. A future project is included without a
     * code change; only the two decided exclusions are removed.
     */
    fun selectIncludedModulePaths(allModulePaths: Iterable<String>): List<String> =
        allModulePaths
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .filterNot { it in EXCLUDED_MODULE_PATHS }
            .sorted()

    /**
     * Removes Kotlin line comments, KDoc and nested block comments. Quoted content is kept:
     * `//`, `/*` and `*/` inside ordinary strings, raw strings or character literals are not
     * comments. Newlines survive so diagnostics remain readable.
     */
    fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        val stack = mutableListOf(LexerFrame(LexerMode.CODE))
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            val frame = stack.last()
            when (frame.mode) {
                LexerMode.RAW_STRING -> {
                    out.append(c)
                    if (c == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"') {
                        out.append("\"\"")
                        i += 3
                        stack.removeAt(stack.lastIndex)
                    } else if (c == '$' && i + 1 < n && text[i + 1] == '{') {
                        out.append('{')
                        i += 2
                        stack += LexerFrame(LexerMode.CODE, templateBraceDepth = 1)
                    } else {
                        i++
                    }
                }
                LexerMode.STRING -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < n) {
                        out.append(text[i + 1])
                        i += 2
                    } else if (c == '$' && i + 1 < n && text[i + 1] == '{') {
                        out.append('{')
                        i += 2
                        stack += LexerFrame(LexerMode.CODE, templateBraceDepth = 1)
                    } else {
                        if (c == '"') stack.removeAt(stack.lastIndex)
                        i++
                    }
                }
                LexerMode.CHAR -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < n) {
                        out.append(text[i + 1])
                        i += 2
                    } else {
                        if (c == '\'') stack.removeAt(stack.lastIndex)
                        i++
                    }
                }
                LexerMode.BACKTICK_ID -> {
                    out.append(c)
                    i++
                    if (c == '`') stack.removeAt(stack.lastIndex)
                }
                LexerMode.CODE -> when {
                    c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                        out.append(' ')
                        i += 2
                        while (i < n && text[i] != '\n') i++
                    }
                    c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                        out.append(' ')
                        i += 2
                        var blockDepth = 1
                        while (i < n && blockDepth > 0) {
                            when {
                                text[i] == '/' && i + 1 < n && text[i + 1] == '*' -> {
                                    blockDepth++
                                    i += 2
                                }
                                text[i] == '*' && i + 1 < n && text[i + 1] == '/' -> {
                                    blockDepth--
                                    i += 2
                                }
                                else -> {
                                    if (text[i] == '\n') out.append('\n')
                                    i++
                                }
                            }
                        }
                    }
                    c == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"' -> {
                        out.append("\"\"\"")
                        i += 3
                        stack += LexerFrame(LexerMode.RAW_STRING)
                    }
                    c == '"' -> {
                        out.append(c)
                        i++
                        stack += LexerFrame(LexerMode.STRING)
                    }
                    c == '\'' -> {
                        out.append(c)
                        i++
                        stack += LexerFrame(LexerMode.CHAR)
                    }
                    c == '`' -> {
                        out.append(c)
                        i++
                        stack += LexerFrame(LexerMode.BACKTICK_ID)
                    }
                    frame.templateBraceDepth > 0 && c == '{' -> {
                        frame.templateBraceDepth++
                        out.append(c)
                        i++
                    }
                    frame.templateBraceDepth > 0 && c == '}' -> {
                        frame.templateBraceDepth--
                        out.append(c)
                        i++
                        if (frame.templateBraceDepth == 0) stack.removeAt(stack.lastIndex)
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
        }
        return out.toString()
    }

    private enum class LexerMode { CODE, STRING, RAW_STRING, CHAR, BACKTICK_ID }

    private data class LexerFrame(
        val mode: LexerMode,
        var templateBraceDepth: Int = 0,
    )

    /** Measures the configured source inputs and returns sorted repository-relative paths. */
    fun measure(repositoryRoot: File, sourceFiles: Collection<File>): Measurement {
        if (!repositoryRoot.isDirectory) {
            throw BuildFailure(
                "Cannot measure kitert coupling: ${repositoryRoot.path} is not a repository directory.",
            )
        }
        val rootPath = repositoryRoot.toPath().toAbsolutePath().normalize()
        val filesByPath = linkedMapOf<String, File>()
        for (file in sourceFiles) {
            if (!file.isFile) {
                throw BuildFailure("Cannot measure kitert coupling: ${file.path} is not a source file.")
            }
            if (file.extension != "kt") {
                throw BuildFailure("Cannot measure kitert coupling: ${file.path} is not a Kotlin file.")
            }
            val filePath = file.toPath().toAbsolutePath().normalize()
            if (!filePath.startsWith(rootPath)) {
                throw BuildFailure(
                    "Cannot measure kitert coupling: ${file.path} is outside ${repositoryRoot.path}.",
                )
            }
            val relative = rootPath.relativize(filePath).toString().replace('\\', '/')
            val segments = relative.split('/')
            if (segments.none(::isSourceFolder) || segments.lastOrNull().orEmpty().isEmpty()) {
                throw BuildFailure(
                    "Cannot measure kitert coupling: $relative is not under a module src directory.",
                )
            }
            filesByPath.putIfAbsent(relative, file)
        }

        val matching = sortedSetOf<String>()
        for ((path, file) in filesByPath.toSortedMap()) {
            val source = stripComments(file.readText())
            if (CNAMES_PATTERN in source || CINTEROP_PACKAGE_PATTERN in source) matching += path
        }
        return Measurement(filesByPath.size, matching)
    }

    /** Reads strict `allowed_kitert_file path` lines; blank and `#` text is ignored. */
    fun parseBaseline(baselineFile: File): Baseline {
        if (!baselineFile.isFile) {
            throw BuildFailure("Cannot read the kitert coupling baseline: ${baselineFile.path} is not a file.")
        }
        val allowed = sortedSetOf<String>()
        baselineFile.readLines().forEachIndexed { index, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed
            val split = line.indexOfFirst { it.isWhitespace() }
            if (split < 0) {
                baselineError(baselineFile, index, "expected `$ALLOWED_KITERT_FILE path`, found `$raw`")
            }
            val key = line.substring(0, split)
            val value = line.substring(split).trim()
            if (key != ALLOWED_KITERT_FILE || value.isEmpty()) {
                baselineError(
                    baselineFile,
                    index,
                    "expected `$ALLOWED_KITERT_FILE path`, found `$raw`",
                )
            }
            val path = validateAllowedPath(baselineFile, index, value)
            if (!allowed.add(path)) {
                baselineError(baselineFile, index, "duplicate $ALLOWED_KITERT_FILE `$path`")
            }
        }
        return Baseline(allowed)
    }

    /** Produces the complete deterministic baseline used by the missing-baseline failure. */
    fun renderBaseline(measured: Measurement, includedModulePaths: Iterable<String>): String {
        val modules = includedModulePaths.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        return buildString {
            appendLine("# KitePlayer kitert cinterop coupling baseline.")
            appendLine("#")
            appendLine("# Command: ./kotlin check kitertCoupling")
            appendLine("# Measured matching Kotlin file count: ${measured.matchingFiles.size}")
            appendLine("#")
            appendLine("# Scope: every present .kt file, including untracked files, in the src, src@*, test")
            appendLine("# and test@* folders of each module except the two exclusions below. Comments are stripped;")
            appendLine("# string and character literals remain, and each matching file counts once.")
            appendLine("# Included when this baseline was measured:")
            for (module in modules) appendLine("#   $module")
            appendLine("# Excluded by design:")
            appendLine("#   kiteplayer-output - owns the C sink pointer")
            appendLine("#   kiteplayer-rt - is the binding")
            appendLine("# Patterns: $CNAMES_PATTERN and $CINTEROP_PACKAGE_PATTERN")
            appendLine()
            for (path in measured.matchingFiles.sorted()) {
                appendLine("$ALLOWED_KITERT_FILE $path")
            }
        }
    }

    private fun validateIncludedModules(rawModules: List<String>): List<String> {
        val modules = rawModules.map { it.trim() }
        if (modules.any { it.isEmpty() || it.startsWith(':') || '/' in it }) {
            throw BuildFailure("checkKitertCoupling received an invalid module name.")
        }
        if (modules.distinct().size != modules.size) {
            throw BuildFailure("checkKitertCoupling received a duplicate module name.")
        }
        val forbidden = modules.filter { it in EXCLUDED_MODULE_PATHS }
        if (forbidden.isNotEmpty()) {
            throw BuildFailure(
                "checkKitertCoupling received excluded project(s) as inputs: ${forbidden.joinToString()}.",
            )
        }
        return modules.sorted()
    }

    private fun validateAllowedPath(baselineFile: File, index: Int, value: String): String {
        if ('\\' in value) {
            baselineError(baselineFile, index, "path must use `/`, found `$value`")
        }
        if (value.startsWith('/') || WINDOWS_ABSOLUTE.containsMatchIn(value)) {
            baselineError(baselineFile, index, "path must be repository-relative, found `$value`")
        }
        val segments = value.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            baselineError(baselineFile, index, "path is not normalized: `$value`")
        }
        if (segments.none(::isSourceFolder) || !value.endsWith(".kt")) {
            baselineError(baselineFile, index, "path must name a Kotlin file under `src`: `$value`")
        }
        return value
    }

    private fun baselineError(file: File, zeroBasedIndex: Int, message: String): Nothing {
        throw BuildFailure("${file.path}:${zeroBasedIndex + 1}: $message.")
    }

    private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:[/\\\\]")

    fun check(repositoryRoot: File, sourceFiles: Collection<File>, includedModules: List<String>, baseline: File, log: (String) -> Unit) {
        val root = repositoryRoot
        val modules = validateIncludedModules(includedModules)

        // This ordering is part of the contract. The first invocation has no baseline, but must
        // still report the measured count and the exact file that will make the next run green.
        val measured = measure(root, sourceFiles)
        if (!baseline.exists()) {
            throw BuildFailure(
                buildString {
                    appendLine("baseline missing: ${baseline.path}")
                    appendLine("Measured ${measured.scannedFileCount} Kotlin source file(s).")
                    appendLine("Measured matching Kotlin file count: ${measured.matchingFiles.size}")
                    appendLine("Create $BASELINE_FILE_NAME with exactly this content:")
                    appendLine()
                    append(renderBaseline(measured, modules))
                },
            )
        }
        if (!baseline.isFile) {
            throw BuildFailure("Cannot read the kitert coupling baseline: ${baseline.path} is not a file.")
        }

        val recorded = parseBaseline(baseline)
        val newFiles = measured.matchingFiles - recorded.allowedFiles
        if (newFiles.isNotEmpty()) {
            throw BuildFailure(
                buildString {
                    appendLine("The kitert coupling ratchet failed against ${baseline.path}.")
                    appendLine("New Kotlin source file(s) name the kitert cinterop:")
                    for (path in newFiles.sorted()) appendLine("  $path")
                    appendLine()
                    appendLine("Remove the direct cinterop name, or, if it is deliberate, add:")
                    for (path in newFiles.sorted()) appendLine("  $ALLOWED_KITERT_FILE $path")
                    appendLine(
                        "The allowlist must move in the same commit, with the old and new file " +
                            "counts and the reason recorded in the commit message.",
                    )
                },
            )
        }

        log(
            "kitert source files scanned: ${measured.scannedFileCount} " +
                "across ${modules.joinToString()}",
        )
        log(
            "kitert matching files: ${measured.matchingFiles.size} " +
                "(${recorded.allowedFiles.size} allowlisted)",
        )
        for (path in measured.matchingFiles) log("  $path")

        val stale = recorded.allowedFiles - measured.matchingFiles
        if (stale.isNotEmpty()) {
            log("allowed but no longer matching (stale lines, removable in a normal commit):")
            for (path in stale.sorted()) log("  $path")
        }
    }

    /** A source folder of the module layout: `src`, `test`, or one of them with a `@` qualifier. */
    fun isSourceFolder(segment: String): Boolean =
        segment == "src" || segment == "test" || segment.startsWith("src@") || segment.startsWith("test@")

    /** The source files to scan: every `.kt` file in the source folders of each included module. */
    fun sourceFilesOf(repositoryRoot: File, modules: Collection<String>): List<File> = modules.sorted().flatMap { module ->
        repositoryRoot.resolve(module).listFiles().orEmpty()
            .filter { it.isDirectory && isSourceFolder(it.name) }
            .sortedBy { it.name }
            .flatMap { folder -> folder.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList() }
    }
}
