package com.germanium.shortcuts

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp

/** The single switch component for the entire app, including newly added action options. */
@Composable internal fun ExpressiveSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Switch(checked, onCheckedChange, modifier = modifier, enabled = enabled,
        thumbContent = { Icon(painterResource(if (checked) R.drawable.ic_glyph_check else R.drawable.ic_close), null, Modifier.size(16.dp)) })
}
