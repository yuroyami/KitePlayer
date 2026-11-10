@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@JsFun("(id, member) => ({ t: 'call', id: id, command: { t: member } })")
private external fun futureCall(id: Int, member: String): JsAny

@JsFun("(member) => ({ t: 'send', control: { t: member } })")
private external fun futureControl(member: String): JsAny

/**
 * The page and a worker file of another release find out at the start, and a call the worker
 * cannot read is answered (#905). The greeting carried no version, and the worker dropped what it
 * could not decode with no reply, so a call from a newer page waited for ever with nothing said.
 */
class WorkerHandshakeTest {

    @Test
    fun aWorkerOfThisProtocolIsAccepted() {
        assertNull(helloRefusal(WorkerMessage.Hello(WORKER_PROTOCOL)))
    }

    @Test
    fun aWorkerOfAnotherOrNoProtocolIsRefusedNamingBoth() {
        for (theirs in listOf(WORKER_PROTOCOL + 1, null)) {
            val refusal = helloRefusal(WorkerMessage.Hello(theirs))
            val error = assertIs<PlaybackError.ConfigurationInvalid>(refusal?.error, "a worker of protocol $theirs")
            assertTrue("protocol $WORKER_PROTOCOL" in error.detail, error.detail)
            if (theirs != null) assertTrue("protocol $theirs" in error.detail, error.detail)
        }
    }

    @Test
    fun aCallOfACommandThisWorkerDoesNotKnowIsStillAnsweredByItsId() {
        val data = futureCall(4, "teleport")
        assertNull(decodePageMessage(data), "the worker cannot read the command")
        assertEquals(4 to "teleport", unreadableCall(data))
        assertNull(unreadableControl(data))
    }

    @Test
    fun aControlThisWorkerDoesNotKnowIsNamedForItsRefusal() {
        val data = futureControl("setWarpFactor")
        assertNull(decodePageMessage(data))
        assertEquals("setWarpFactor", unreadableControl(data))
        assertNull(unreadableCall(data))
    }
}
