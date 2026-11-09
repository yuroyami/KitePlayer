/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every folder that NOTICE sends a reader to exists in the tree (#912). */
class NoticePathsTest {

    @Test
    fun everyFolderNoticeNamesExists() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "NOTICE").isFile && File(it, "library.module-template.yaml").isFile }
            ?: error("no repository root above ${System.getProperty("user.dir")}")
        val named = Regex("""(?m)^(kiteplayer-[\w-]+/\S+/)\.?$""").findAll(File(root, "NOTICE").readText())
            .map { it.groupValues[1] }.toList()
        assertTrue(named.isNotEmpty(), "NOTICE names no folder, so this test reads nothing")
        val missing = named.filterNot { File(root, it).isDirectory }
        assertTrue(missing.isEmpty(), "NOTICE names folders that do not exist: $missing")
    }
}
