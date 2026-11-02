package io.github.yuroyami.kiteplayer

import android.view.accessibility.CaptioningManager.CaptionStyle
import kotlin.test.Test
import kotlin.test.assertEquals

/** Android's caption edge types, as the shared mapping names them (#679). */
class SystemCaptionEdgeTest {
    @Test
    fun eachEdgeTypeHasItsName() {
        assertEquals(CaptionEdge.None, captionEdgeOf(CaptionStyle.EDGE_TYPE_NONE))
        assertEquals(CaptionEdge.Outline, captionEdgeOf(CaptionStyle.EDGE_TYPE_OUTLINE))
        assertEquals(CaptionEdge.DropShadow, captionEdgeOf(CaptionStyle.EDGE_TYPE_DROP_SHADOW))
        assertEquals(CaptionEdge.Raised, captionEdgeOf(CaptionStyle.EDGE_TYPE_RAISED))
        assertEquals(CaptionEdge.Depressed, captionEdgeOf(CaptionStyle.EDGE_TYPE_DEPRESSED))
        assertEquals(CaptionEdge.Unspecified, captionEdgeOf(CaptionStyle.EDGE_TYPE_UNSPECIFIED))
    }
}
