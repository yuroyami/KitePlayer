package io.github.yuroyami.kiteplayer.compose

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** The UI coordinate must present caller-owned players without selecting their runtime or transport. */
class UiOnlyDependenciesTest {
    @Test
    fun `UI consumers do not inherit the default player factory or HTTP client`() {
        assertFailsWith<ClassNotFoundException>("the UI coordinate pulled in the standard player runtime") {
            Class.forName("io.github.yuroyami.kiteplayer.KitePlayerPlatform")
        }
        assertFailsWith<ClassNotFoundException>("the UI coordinate pulled in the network transport") {
            Class.forName("io.ktor.client.HttpClient")
        }
        assertNotNull(
            Class.forName("io.github.yuroyami.kiteplayer.mobile.DesktopAwtPlayerViewRendererFactory"),
            "separating the runtime must retain the native-view frame adapter",
        )
    }
}
