package io.github.yuroyami.kiteplayer.session

import android.media.session.PlaybackState
import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Requests for specific media and the application's own media keys (#431). The router and the key
 * order are generic in their request and event, so these tests route plain strings: a host test
 * cannot build the platform's bundles, addresses or key events.
 */
class MediaRequestTest {

    private val state = MediaSessionState(
        phase = MediaSessionPhase.Playing,
        position = 5.seconds,
        duration = 60.seconds,
        speed = 1.0,
        canSeek = true,
        hasVideo = false,
        title = "A Holiday",
        artist = null,
        album = null,
        hasNext = false,
        hasPrevious = false,
    )

    private val searchActions = PlaybackState.ACTION_PLAY_FROM_SEARCH or PlaybackState.ACTION_PREPARE_FROM_SEARCH
    private val idActions = PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID
    private val addressActions = PlaybackState.ACTION_PLAY_FROM_URI or PlaybackState.ACTION_PREPARE_FROM_URI

    @Test
    fun `each kind offers exactly its own actions`() {
        assertEquals(0L, requestActionsFor(emptySet()))
        assertEquals(searchActions, requestActionsFor(setOf(MediaRequestKind.Search)))
        assertEquals(idActions, requestActionsFor(setOf(MediaRequestKind.MediaId)))
        assertEquals(addressActions, requestActionsFor(setOf(MediaRequestKind.Address)))
        assertEquals(PlaybackState.ACTION_PREPARE, requestActionsFor(setOf(MediaRequestKind.Prepare)))
        assertEquals(
            searchActions or idActions or addressActions or PlaybackState.ACTION_PREPARE,
            requestActionsFor(MediaRequestKind.entries.toSet()),
        )
    }

    @Test
    fun `without a handler the session offers what it offered before`() {
        assertEquals(actionsFor(state), sessionPlaybackFor(state, emptyList()).actions)
        val router = MediaRequestRouter<String>()
        assertEquals(actionsFor(state), sessionPlaybackFor(state, emptyList(), router.offered).actions)
    }

    @Test
    fun `a search handler adds the search actions to the transport`() {
        val router = MediaRequestRouter<String>()
        router.set(setOf(MediaRequestKind.Search)) {}
        val actions = sessionPlaybackFor(state, emptyList(), router.offered).actions
        assertEquals(actionsFor(state) or searchActions, actions)
        assertFalse((actions and idActions) != 0L, "an id the application does not answer must not be offered")
    }

    @Test
    fun `a request of an offered kind reaches the handler`() {
        val router = MediaRequestRouter<String>()
        val heard = ArrayList<String>()
        router.set(setOf(MediaRequestKind.Search, MediaRequestKind.MediaId)) { heard += it }
        assertTrue(router.deliver(MediaRequestKind.Search) { "jazz" })
        assertTrue(router.deliver(MediaRequestKind.MediaId) { "album-7" })
        assertEquals(listOf("jazz", "album-7"), heard)
    }

    @Test
    fun `a request of a kind not offered is ignored without being built`() {
        val router = MediaRequestRouter<String>()
        val heard = ArrayList<String>()
        router.set(setOf(MediaRequestKind.Search)) { heard += it }
        assertFalse(router.deliver(MediaRequestKind.Address) { error("built a request nobody answers") })
        assertTrue(heard.isEmpty())
    }

    @Test
    fun `a request the platform sent without its target is ignored`() {
        val router = MediaRequestRouter<String>()
        val heard = ArrayList<String>()
        router.set(setOf(MediaRequestKind.MediaId)) { heard += it }
        assertFalse(router.deliver(MediaRequestKind.MediaId) { null })
        assertTrue(heard.isEmpty())
    }

    @Test
    fun `a null handler or no kinds offers nothing and a later handler replaces an earlier one`() {
        val router = MediaRequestRouter<String>()
        router.set(setOf(MediaRequestKind.Search)) {}
        router.set(setOf(MediaRequestKind.Search), null)
        assertEquals(emptySet(), router.offered)
        router.set(emptySet()) {}
        assertEquals(emptySet(), router.offered)

        val first = ArrayList<String>()
        val second = ArrayList<String>()
        router.set(setOf(MediaRequestKind.Search)) { first += it }
        router.set(setOf(MediaRequestKind.Prepare)) { second += it }
        assertEquals(setOf(MediaRequestKind.Prepare), router.offered)
        assertFalse(router.deliver(MediaRequestKind.Search) { "jazz" })
        assertTrue(router.deliver(MediaRequestKind.Prepare) { "ready" })
        assertEquals(emptyList(), first)
        assertEquals(listOf("ready"), second)
    }

    @Test
    fun `an application that handles a key stops the session's own action`() {
        val seen = ArrayList<String>()
        val action = mediaKeyActionFor("next", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN, 0) { event ->
            seen += event
            true
        }
        assertEquals(MediaKeyAction.Application, action)
        assertEquals(listOf("next"), seen)
    }

    @Test
    fun `a key the application declines or has no handler for goes to the session's rule`() {
        val declined = mediaKeyActionFor("pp", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN, 0) { false }
        assertEquals(MediaKeyAction.Toggle, declined)
        val none = mediaKeyActionFor<String>("pp", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN, 0, null)
        assertEquals(MediaKeyAction.Toggle, none)
        val hook = mediaKeyActionFor("hook", KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_DOWN, 0) { false }
        assertEquals(MediaKeyAction.Platform, hook, "the headset keeps its double press when the application declines")
    }
}
