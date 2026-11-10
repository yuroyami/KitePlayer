package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.runtime.Composable
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

@Composable
internal actual fun systemReducesMotion(): Boolean = UIAccessibilityIsReduceMotionEnabled()
