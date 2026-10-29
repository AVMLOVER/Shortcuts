package com.germanium.shortcuts

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable

internal fun ComponentActivity.enableShortcutsEdgeToEdge() {
    enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
    if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
}

/** Gesture navigation draws over the app; only actual system buttons reserve bottom space. */
@Composable internal fun shortcutsBottomInsets(): WindowInsets =
    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
        .union(WindowInsets.tappableElement.only(WindowInsetsSides.Bottom))

@Composable internal fun shortcutsContentInsets(): WindowInsets =
    WindowInsets.safeDrawing.only(WindowInsetsSides.Top).union(shortcutsBottomInsets())
