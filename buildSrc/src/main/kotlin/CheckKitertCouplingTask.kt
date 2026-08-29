package io.github.yuroyami.kiteplayer.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Prevents a new engine, backend, subtitle or sample source file from naming the `kitert`
 * cinterop directly.
 *
 * The root build supplies every present `src/**/*.kt` file from every included Gradle
 * subproject except `kiteplayer-output`, which owns the C sink pointer, and `kiteplayer-rt`,
 * which is the binding. The file collection deliberately includes untracked sources: a new
 * coupling site must fail before it can be committed. Comments are removed before matching,
 * while string and character literals remain source text.
 *
 * [baselineFile] is deliberately [Internal]. An `InputFile` that does not exist is rejected by
 * Gradle before [check] can run, which would make the baseline bootstrap unable to print its
 * measured number. [baselineInputs] is the tracked view of the same path; root registration
 * supplies it as a one-file file tree, which is empty while the baseline is absent and tracks its
 * contents once it exists.
 */
@DisableCachingByDefault(because = "This verification task has no outputs.")
abstract class CheckKitertCouplingTask : DefaultTask() {

    /** The dynamically selected `src/**/*.kt` files to inspect. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    /** Active, non-excluded Gradle project paths, for a deterministic scope report. */
    @get:Input
    abstract val includedModulePaths: ListProperty<String>

    /** Repository root, used only to produce portable allowlist paths. */
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    /** The intended baseline path, read only after the source measurement has completed. */
    @get:Internal
    abstract val baselineFile: RegularFileProperty

    /** Tracked file-tree view of [baselineFile], allowed to be empty during bootstrap. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineInputs: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val root = repositoryRoot.get().asFile
        val modules = validateIncludedModules(includedModulePaths.get())

        // This ordering is part of the contract. The first invocation has no baseline, but must
        // still report the measured count and the exact file that will make the next run green.
        val measured = measure(root, sourceFiles.files)
        val baseline = baselineFile.get().asFile
        if (!baseline.exists()) {
            throw GradleException(
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
            throw GradleException("Cannot read the kitert coupling baseline: ${baseline.path} is not a file.")
        }

        val recorded = parseBaseline(baseline)
        val newFiles = measured.matchingFiles - recorded.allowedFiles
        if (newFiles.isNotEmpty()) {
            throw GradleException(
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

        logger.lifecycle(
            "kitert source files scanned: ${measured.scannedFileCount} " +
                "across ${modules.joinToString()}",
        )
        logger.lifecycle(
            "kitert matching files: ${measured.matchingFiles.size} " +
                "(${recorded.allowedFiles.size} allowlisted)",
        )
        for (path in measured.matchingFiles) logger.lifecycle("  $path")

        val stale = recorded.allowedFiles - measured.matchingFiles
        if (stale.isNotEmpty()) {
            logger.lifecycle("allowed but no longer matching (stale lines, removable in a normal commit):")
            for (path in stale.sorted()) logger.lifecycle("  $path")
        }
    }

    /** One source-tree measurement. Each matching file appears exactly once. */
    data class Measurement(
        val scannedFileCount: Int,
        val matchingFiles: Set<String>,
    )

    /** The exact source paths permitted to name the binding. */
    data class Baseline(val allowedFiles: Set<String>)

    companion object {

        const val BASELINE_FILE_NAME: String = "kitert-coupling-baseline.txt"
        const val ALLOWED_KITERT_FILE: String = "allowed_kitert_file"
        const val CNAMES_PATTERN: String = "cnames.structs.kprt_"
        const val CINTEROP_PACKAGE_PATTERN: String = "kiteplayer.rt.cinterop"

        /** The only two active projects that own the binding boundary by design. */
        val EXCLUDED_MODULE_PATHS: Set<String> = linkedSetOf(
            ":kiteplayer-output",
            ":kiteplayer-rt",
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
                throw GradleException(
                    "Cannot measure kitert coupling: ${repositoryRoot.path} is not a repository directory.",
                )
            }
            val rootPath = repositoryRoot.toPath().toAbsolutePath().normalize()
            val filesByPath = linkedMapOf<String, File>()
            for (file in sourceFiles) {
                if (!file.isFile) {
                    throw GradleException("Cannot measure kitert coupling: ${file.path} is not a source file.")
                }
                if (file.extension != "kt") {
                    throw GradleException("Cannot measure kitert coupling: ${file.path} is not a Kotlin file.")
                }
                val filePath = file.toPath().toAbsolutePath().normalize()
                if (!filePath.startsWith(rootPath)) {
                    throw GradleException(
                        "Cannot measure kitert coupling: ${file.path} is outside ${repositoryRoot.path}.",
                    )
                }
                val relative = rootPath.relativize(filePath).toString().replace('\\', '/')
                val segments = relative.split('/')
                if ("src" !in segments || segments.lastOrNull().orEmpty().isEmpty()) {
                    throw GradleException(
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
                throw GradleException("Cannot read the kitert coupling baseline: ${baselineFile.path} is not a file.")
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
                appendLine("# Command: ./gradlew checkKitertCoupling")
                appendLine("# Measured matching Kotlin file count: ${measured.matchingFiles.size}")
                appendLine("#")
                appendLine("# Scope: every present src/**/*.kt file, including untracked files, in each")
                appendLine("# active Gradle subproject except the two exclusions below. Comments are stripped;")
                appendLine("# string and character literals remain, and each matching file counts once.")
                appendLine("# Included when this baseline was measured:")
                for (module in modules) appendLine("#   $module")
                appendLine("# Excluded by design:")
                appendLine("#   :kiteplayer-output - owns the C sink pointer")
                appendLine("#   :kiteplayer-rt - is the binding")
                appendLine("# Patterns: $CNAMES_PATTERN and $CINTEROP_PACKAGE_PATTERN")
                appendLine()
                for (path in measured.matchingFiles.sorted()) {
                    appendLine("$ALLOWED_KITERT_FILE $path")
                }
            }
        }

        private fun validateIncludedModules(rawModules: List<String>): List<String> {
            val modules = rawModules.map { it.trim() }
            if (modules.any { it.isEmpty() || !it.startsWith(':') }) {
                throw GradleException("checkKitertCoupling received an invalid included Gradle project path.")
            }
            if (modules.distinct().size != modules.size) {
                throw GradleException("checkKitertCoupling received a duplicate included Gradle project path.")
            }
            val forbidden = modules.filter { it in EXCLUDED_MODULE_PATHS }
            if (forbidden.isNotEmpty()) {
                throw GradleException(
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
            if ("src" !in segments || !value.endsWith(".kt")) {
                baselineError(baselineFile, index, "path must name a Kotlin file under `src`: `$value`")
            }
            return value
        }

        private fun baselineError(file: File, zeroBasedIndex: Int, message: String): Nothing {
            throw GradleException("${file.path}:${zeroBasedIndex + 1}: $message.")
        }

        private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:[/\\\\]")
    }
}
