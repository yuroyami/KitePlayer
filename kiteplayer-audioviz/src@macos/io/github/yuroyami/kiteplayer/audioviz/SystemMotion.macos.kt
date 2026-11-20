package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.runtime.Composable
import platform.AppKit.NSWorkspace
import platform.AppKit.accessibilityDisplayShouldReduceMotion

@Composable
internal actual fun systemReducesMotion(): Boolean = NSWorkspace.sharedWorkspace.accessibilityDisplayShouldReduceMotion
