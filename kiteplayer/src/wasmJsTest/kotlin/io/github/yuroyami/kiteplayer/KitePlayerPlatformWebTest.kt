@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer

import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The web answer to whether picture in picture exists at all.
 *
 * Both browser features are globals, so each test sets them for its own duration and then puts
 * back what was there. That makes the same test mean the same thing in node, which has neither
 * feature, and in a browser, which may have both.
 */
class KitePlayerPlatformWebTest {

    private fun answerWith(documentWindow: Boolean, videoElement: Boolean): Boolean {
        val saved = setFeatures(documentWindow, videoElement)
        try {
            return KitePlayerPlatform.supportsPictureInPicture
        } finally {
            restoreFeatures(saved)
        }
    }

    @Test
    fun theDocumentWindowAloneIsEnough() {
        assertTrue(answerWith(documentWindow = true, videoElement = false))
    }

    @Test
    fun theVideoElementAloneIsEnough() {
        assertTrue(answerWith(documentWindow = false, videoElement = true))
    }

    @Test
    fun withNeitherFeatureTheAnswerIsNo() {
        assertFalse(answerWith(documentWindow = false, videoElement = false))
    }
}

/**
 * Shadows the two globals with plain values and answers what it replaced. Where there is no
 * `document`, as in node, a stand-in exists only until [restoreFeatures].
 */
@JsFun(
    """(documentWindow, videoElement) => {
      const saved = { dpip: Object.getOwnPropertyDescriptor(globalThis, 'documentPictureInPicture'), fakeDocument: false };
      Object.defineProperty(globalThis, 'documentPictureInPicture',
        { value: documentWindow ? {} : undefined, configurable: true, writable: true });
      if (typeof document === 'undefined') {
        globalThis.document = { pictureInPictureEnabled: videoElement };
        saved.fakeDocument = true;
      } else {
        saved.enabled = Object.getOwnPropertyDescriptor(document, 'pictureInPictureEnabled');
        Object.defineProperty(document, 'pictureInPictureEnabled', { value: videoElement, configurable: true, writable: true });
      }
      return saved;
    }""",
)
private external fun setFeatures(documentWindow: Boolean, videoElement: Boolean): JsAny

@JsFun(
    """(saved) => {
      if (saved.dpip) Object.defineProperty(globalThis, 'documentPictureInPicture', saved.dpip);
      else delete globalThis.documentPictureInPicture;
      if (saved.fakeDocument) delete globalThis.document;
      else if (saved.enabled) Object.defineProperty(document, 'pictureInPictureEnabled', saved.enabled);
      else delete document.pictureInPictureEnabled;
    }""",
)
private external fun restoreFeatures(saved: JsAny)
