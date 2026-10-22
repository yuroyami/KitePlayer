@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi

/**
 * Keeps a page's screen awake while it shows a playing picture (#238), through the W3C Screen Wake
 * Lock API. A browser keeps the screen on by itself only for a playing video element, and the
 * player draws onto a canvas, so without this the display dims and locks in the middle of a film.
 *
 * Two things ask for it, and the screen stays awake while either does. A canvas renderer says
 * [framePresented] for each picture it draws, and the screen stays awake until [GRACE_MILLIS] pass
 * with none: a pause, the end, a stall or a closed renderer lets it sleep, as a player with no
 * picture to watch should. A page whose player draws in a worker, where there is no screen to keep,
 * says [setHeld] while its player plays a picture.
 *
 * The browser drops the lock whenever the page is hidden, so it is asked for again when the page
 * shows while something still wants it. A page outside a secure context, a browser without the API
 * and a worker simply get no lock, with no error.
 */
@KitePlayerLowLevelApi
public object WebDisplayAwake {
    /** How long the screen stays awake after the last picture a renderer drew. */
    public const val GRACE_MILLIS: Int = 2000

    /** A picture was just drawn on this page. */
    public fun framePresented(): Unit = webWakeFrame(GRACE_MILLIS)

    /** One more holder wants the screen awake when [on], and one fewer when not, whatever is drawn. */
    public fun setHeld(on: Boolean): Unit = webWakeHold(on, GRACE_MILLIS)
}

/*
 * The state is one object on the page's global scope, so every renderer and every player of the
 * page shares one lock. `wanted` is the rule, `check` applies it, and `request` asks the browser,
 * at most once at a time.
 */
private const val WAKE_STATE = """(grace) => {
  const g = globalThis;
  if (g.__kiteWake) return g.__kiteWake;
  const w = { sentinel: null, pending: false, last: -1e15, holds: 0, timer: 0, grace: grace };
  w.wanted = () => w.holds > 0 || Date.now() - w.last < w.grace;
  w.request = () => {
    if (w.sentinel || w.pending) return;
    if (typeof document === 'undefined' || document.visibilityState !== 'visible') return;
    if (!g.isSecureContext || !g.navigator || !g.navigator.wakeLock) return;
    w.pending = true;
    g.navigator.wakeLock.request('screen').then((s) => {
      w.pending = false;
      if (!w.wanted()) { s.release().catch(() => {}); return; }
      w.sentinel = s;
      s.addEventListener('release', () => { if (w.sentinel === s) w.sentinel = null; });
    }, () => { w.pending = false; });
  };
  w.check = () => {
    if (w.timer) { clearTimeout(w.timer); w.timer = 0; }
    if (w.wanted()) {
      w.request();
      if (w.holds === 0) w.timer = setTimeout(w.check, Math.max(50, w.last + w.grace - Date.now()));
    } else if (w.sentinel) {
      const s = w.sentinel;
      w.sentinel = null;
      s.release().catch(() => {});
    }
  };
  if (typeof document !== 'undefined' && document.addEventListener) {
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') w.check(); });
  }
  g.__kiteWake = w;
  return w;
}"""

@JsFun(
    """(grace) => {
  const w = ($WAKE_STATE)(grace);
  w.last = Date.now();
  if (!w.sentinel) w.request();
  if (!w.timer && w.holds === 0) w.timer = setTimeout(w.check, w.grace);
}""",
)
private external fun webWakeFrame(grace: Int)

@JsFun(
    """(on, grace) => {
  const w = ($WAKE_STATE)(grace);
  w.holds = Math.max(0, w.holds + (on ? 1 : -1));
  w.check();
}""",
)
private external fun webWakeHold(on: Boolean, grace: Int)
