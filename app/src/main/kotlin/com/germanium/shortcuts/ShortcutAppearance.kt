package com.germanium.shortcuts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal object ShortcutStyles {
    val icons = linkedMapOf(
        "layers" to R.drawable.ic_glyph_layers, "bolt" to R.drawable.ic_glyph_bolt, "star" to R.drawable.ic_glyph_star, "heart" to R.drawable.ic_glyph_heart,
        "music" to R.drawable.ic_glyph_music, "folder" to R.drawable.ic_glyph_folder, "calendar" to R.drawable.ic_glyph_calendar, "camera" to R.drawable.ic_glyph_camera,
        "chat" to R.drawable.ic_glyph_chat, "globe" to R.drawable.ic_glyph_globe, "code" to R.drawable.ic_glyph_code, "book" to R.drawable.ic_glyph_book,
        "coffee" to R.drawable.ic_glyph_coffee, "home" to R.drawable.ic_glyph_home, "person" to R.drawable.ic_glyph_person, "check" to R.drawable.ic_glyph_check,
    )
    val colors = linkedMapOf(
        "red" to Color(0xFFB3261E), "orange" to Color(0xFF965A00), "yellow" to Color(0xFF756000), "green" to Color(0xFF216D40),
        "teal" to Color(0xFF006B61), "blue" to Color(0xFF135CAB), "purple" to Color(0xFF6C3AB1), "pink" to Color(0xFF9D3F76),
    )
    fun color(key: String): Color = colors[key] ?: runCatching { Color(android.graphics.Color.parseColor(key)) }.getOrDefault(colors.getValue("purple"))
}
@Composable internal fun themedShortcutColor(key: String): Color {
    val colors = MaterialTheme.colorScheme
    return when (key) {
        "red" -> colors.error; "orange" -> colors.tertiary; "yellow" -> colors.tertiaryContainer
        "green" -> colors.secondary; "teal" -> colors.secondaryContainer; "blue" -> colors.primary
        "purple" -> colors.primaryContainer; "pink" -> colors.inversePrimary
        else -> if (key.startsWith('#')) ShortcutStyles.color(key) else colors.primaryContainer
    }
}

@Composable internal fun ShortcutBadge(icon: String, color: String, size: Dp = 52.dp, description: String? = null) {
    val key = icon.takeIf { it in ShortcutStyles.icons } ?: "layers"
    val background = themedShortcutColor(color)
    val foreground = MaterialTheme.colorScheme.let { if ((background.luminance() > 0.5f) == (it.onSurface.luminance() < 0.5f)) it.onSurface else it.inverseOnSurface }
    Surface(Modifier.size(size), shape = RoundedCornerShape(size * 0.32f), color = background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(painterResource(ShortcutStyles.icons.getValue(key)), description ?: t("icon_$key"), Modifier.size(size * 0.56f), tint = foreground)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ShortcutAppearancePicker(icon: String, color: String, dismiss: () -> Unit, change: (String, String) -> Unit) {
    var custom by rememberSaveable { mutableStateOf(color.startsWith('#')) }
    ModalBottomSheet(contentWindowInsets = { shortcutsContentInsets() }, onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 24.dp).testTag("shortcut_appearance_picker"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(t("shortcut_appearance"), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = dismiss, modifier = Modifier.testTag("close_appearance")) { Text(t("done")) }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ShortcutBadge(icon, color, 80.dp) }
            LazyColumn(Modifier.weight(1f).testTag("appearance_options"), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(t("icon_colors"), style = MaterialTheme.typography.titleMedium) }
                ShortcutStyles.colors.keys.chunked(4).forEach { row -> item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { key ->
                        val description = t("preset_color", ShortcutStyles.colors.keys.indexOf(key) + 1)
                        StyleChoice(key == color, null, Modifier.weight(1f).testTag("choose_color_$key").semantics { contentDescription = description }, { custom = false; change(icon, key) }) {
                            Surface(Modifier.size(36.dp), shape = RoundedCornerShape(12.dp), color = themedShortcutColor(key)) {
                                if (key == color) Icon(painterResource(R.drawable.ic_glyph_check), null, Modifier.padding(8.dp))
                            }
                        }
                    } }
                } }
                item {
                    FilterChip(custom, { custom = !custom }, label = { Text(t("custom_color")) }, modifier = Modifier.testTag("custom_color"))
                    if (custom) M3EColorPicker(color) { change(icon, it) }
                }
                item { Text(t("icon_symbols"), style = MaterialTheme.typography.titleMedium) }
                ShortcutStyles.icons.keys.chunked(4).forEach { row -> item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { key ->
                        StyleChoice(key == icon, "icon_$key", Modifier.weight(1f).testTag("choose_icon_$key"), { change(key, color) }) {
                            Icon(painterResource(ShortcutStyles.icons.getValue(key)), null, Modifier.size(32.dp))
                        }
                    } }
                } }
            }
        }
    }
}

/** Continuous HSV controls built with the app's Material 3 Expressive sliders. */
@Composable internal fun M3EColorPicker(initial: String, change: (String) -> Unit) {
    val seed = themedShortcutColor(initial)
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(seed.toArgb(), it) } }
    var hue by rememberSaveable { mutableFloatStateOf(hsv[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(hsv[1]) }
    var brightness by rememberSaveable { mutableFloatStateOf(hsv[2]) }
    fun commit() { change("#%06X".format(java.util.Locale.ROOT, android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)) and 0xFFFFFF)) }
    Column(Modifier.fillMaxWidth().testTag("color_sliders"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("#%06X".format(java.util.Locale.ROOT, seed.toArgb() and 0xFFFFFF), style = MaterialTheme.typography.titleMedium)
        ColorChannel("color_hue", hue / 360f, (0..6).map { Color.hsv(it * 60f, 1f, 1f) }) { hue = it * 360f; commit() }
        ColorChannel("color_saturation", saturation, listOf(Color.hsv(hue, 0f, brightness), Color.hsv(hue, 1f, brightness))) { saturation = it; commit() }
        ColorChannel("color_brightness", brightness, listOf(Color.Black, Color.hsv(hue, saturation, 1f))) { brightness = it; commit() }
    }
}

@Composable private fun ColorChannel(label: String, value: Float, colors: List<Color>, changed: (Float) -> Unit) {
    Text(t(label), style = MaterialTheme.typography.labelLarge)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp)).background(Brush.horizontalGradient(colors)))
        Slider(value, changed, modifier = Modifier.testTag(label), colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent))
    }
}

@Composable private fun StyleChoice(chosen: Boolean, label: String?, modifier: Modifier, click: () -> Unit, graphic: @Composable () -> Unit) {
    Surface(onClick = click, modifier = modifier.semantics { selected = chosen; role = Role.RadioButton }, shape = RoundedCornerShape(16.dp),
        color = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (chosen) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            graphic(); if (label != null) Text(t(label), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}
