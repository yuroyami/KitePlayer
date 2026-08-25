package io.github.yuroyami.kiteplayer.phone

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The whole point of this module is that it is on its way OUT.
 *
 * KP-UNTESTED-MODULES: an umbrella kept for 0.0.2 source consumers is only harmless while it stays
 * deprecated, because that is what tells a consumer to migrate and what lets this module eventually
 * be deleted. Silently un-deprecating it turns a compatibility shim back into supported API, which
 * is a decision nobody would make on purpose in a commit that only touched an annotation.
 *
 * JVM only, because it needs reflection; the contract it guards is the same on every target.
 */
class DeprecationIntactTest {

    @Test
    fun phoneBackendsIsStillDeprecated() {
        val method = Class.forName("io.github.yuroyami.kiteplayer.phone.PhoneBackendsKt")
            .declaredMethods
            .single { it.name == "phoneBackends" }
        assertTrue(
            method.annotations.any { it.annotationClass.simpleName == "Deprecated" },
            "phoneBackends lost its @Deprecated, was: ${method.annotations.map { it.annotationClass.simpleName }}",
        )
    }
}
