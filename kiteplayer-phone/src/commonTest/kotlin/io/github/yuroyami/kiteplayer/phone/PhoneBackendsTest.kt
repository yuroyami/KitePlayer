package io.github.yuroyami.kiteplayer.phone

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.mobile.mobileBackends
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * KP-UNTESTED-MODULES. This module is PUBLISHED and had no test source set at all, so every one of
 * its test tasks answered NO-SOURCE and it read as covered until CI named it.
 *
 * It owns no implementation. It owns exactly one contract: it is a deprecated umbrella for 0.0.2
 * source consumers and it must keep DELEGATING to what replaced it. A shim that quietly grows its
 * own behaviour is worse than no shim, because the consumer who never migrated gets a second
 * implementation without asking for one.
 */
class PhoneBackendsTest {

    /**
     * Compared by SHAPE, not by equality, and the reason is worth stating.
     *
     * `Backends` is a data class, but on an available JVM `backendsOrNull` builds a FRESH
     * `KiteCodecMediaBackend()` on every call, so two correct calls are already unequal. Comparing
     * the types answers the question actually being asked, which is whether the same stack was
     * assembled, and it is non-vacuous on both sides of the availability check: when no backend is
     * available both shapes are `null to null`, and when one is both name the same classes.
     */
    private fun Backends.shape(): Pair<String?, String?> =
        backend?.let { it::class.simpleName } to output?.let { it::class.simpleName }

    @Test
    fun theDeprecatedUmbrellaAssemblesTheSameStackAsTheModuleThatReplacedIt() {
        @Suppress("DEPRECATION")
        val umbrella = phoneBackends()
        assertEquals(
            mobileBackends().shape(),
            umbrella.shape(),
            "phoneBackends must delegate to mobileBackends, not assemble a stack of its own",
        )
    }

    @Test
    fun theUmbrellaIsStableAcrossCalls() {
        @Suppress("DEPRECATION")
        val first = phoneBackends().shape()
        @Suppress("DEPRECATION")
        val second = phoneBackends().shape()
        assertEquals(first, second, "a resolver that answers differently twice has hidden state")
    }
}
