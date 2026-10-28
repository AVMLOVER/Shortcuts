package com.germanium.shortcuts

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** One transition drives the indicator and all widths; the outer capsule never resizes. */
@Composable internal fun FloatingNavigation(selectedTab: Int, select: (Int) -> Unit) {
    val transition = updateTransition(selectedTab, label = "navigation")
    val position = transition.animateFloat({ tween(180, easing = FastOutSlowInEasing) }, label = "indicator") { it * 0.26f }
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(34.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp, shadowElevation = 8.dp,
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(0.9f).testTag("floating_navigation")) {
            BoxWithConstraints(Modifier.padding(6.dp).height(52.dp)) {
                val available = maxWidth
                Surface(Modifier.offset(x = available * position.value).width(available * 0.48f).fillMaxHeight(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {}
                Row(Modifier.fillMaxSize()) {
                listOf("shortcuts", "automation", "settings").forEachIndexed { index, key ->
                    val chosen = selectedTab == index; val title = t(key)
                    val weight = transition.animateFloat({ tween(180, easing = FastOutSlowInEasing) }, label = "item_$index") { if (it == index) 0.48f else 0.26f }
                    Surface(onClick = { select(index) }, shape = RoundedCornerShape(28.dp), color = Color.Transparent,
                        modifier = Modifier.width(available * weight.value).fillMaxHeight().testTag("nav_$key").semantics { selected = chosen; role = Role.Tab; contentDescription = title }) {
                        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(listOf(R.drawable.ic_grid, R.drawable.ic_automation, R.drawable.ic_settings)[index])
                            if (chosen) { Spacer(Modifier.width(8.dp)); Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
            }
        }
    }
}
