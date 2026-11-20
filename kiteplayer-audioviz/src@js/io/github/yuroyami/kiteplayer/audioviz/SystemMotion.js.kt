package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.runtime.Composable

@Composable
internal actual fun systemReducesMotion(): Boolean = prefersReducedMotion()

/** The browser's prefers-reduced-motion media query. False where there is no window, as in Node. */
private fun prefersReducedMotion(): Boolean =
    js("typeof window !== 'undefined' && !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches)")
