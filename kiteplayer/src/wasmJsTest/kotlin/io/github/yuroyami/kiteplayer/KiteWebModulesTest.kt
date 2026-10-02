package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The codec module choice (#58). The page under test is not cross-origin isolated, in Node or in
 * the headless browser, so the live answer is the single-threaded module; the choice itself is
 * checked both ways.
 */
class KiteWebModulesTest {

    @Test
    fun anIsolatedPageGetsTheThreadedModuleWhenThereIsOne() {
        assertEquals("./kite-mt.mjs", chooseCodecModule(isolated = true, "./kite.mjs", "./kite-mt.mjs"))
        assertEquals("./kite.mjs", chooseCodecModule(isolated = true, "./kite.mjs", null))
    }

    @Test
    fun aPageThatIsNotIsolatedNeverGetsTheThreadedModule() {
        assertEquals("./kite.mjs", chooseCodecModule(isolated = false, "./kite.mjs", "./kite-mt.mjs"))
        assertEquals("./kite.mjs", chooseCodecModule(isolated = false, "./kite.mjs", null))
    }

    @Test
    fun thisPageIsNotIsolatedSoItGetsTheSingleThreadedModule() {
        assertFalse(KiteWebModules.isCrossOriginIsolated)
        assertEquals("./kite.mjs", KiteWebModules.codecModuleUrl(threaded = "./kite-mt.mjs"))
    }
}
