/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/** A local libass chain is compared with the pinned asset archive by archive (#931). */
class AssChainPinTest {

    private fun chain(vararg archives: Pair<String, String>): AssChain.Chain {
        val root = createTempDirectory("ass-chain").toFile()
        val lib = root.resolve("lib").also { it.mkdirs() }
        archives.forEach { (name, bytes) -> lib.resolve(name).writeText(bytes) }
        lib.resolve("libass.la").writeText("a libtool file the zip leaves out")
        return AssChain.Chain("wasm32", root)
    }

    @Test
    fun `the same archives match`() {
        val a = chain("libass.a" to "ass", "libharfbuzz.a" to "hb 14.5.0")
        val b = chain("libass.a" to "ass", "libharfbuzz.a" to "hb 14.5.0")
        assertEquals(emptyList(), AssChain.differingArchives(a, b))
    }

    @Test
    fun `a rebuilt archive of the same size differs`() {
        val a = chain("libass.a" to "ass", "libharfbuzz.a" to "hb 14.6.0")
        val b = chain("libass.a" to "ass", "libharfbuzz.a" to "hb 14.5.0")
        assertEquals(listOf("libharfbuzz.a"), AssChain.differingArchives(a, b))
    }

    @Test
    fun `an archive only one side has differs`() {
        val a = chain("libass.a" to "ass", "libharfbuzz-gpu.a" to "gpu")
        val b = chain("libass.a" to "ass")
        assertEquals(listOf("libharfbuzz-gpu.a"), AssChain.differingArchives(a, b))
        assertEquals(listOf("libharfbuzz-gpu.a"), AssChain.differingArchives(b, a))
    }

}
