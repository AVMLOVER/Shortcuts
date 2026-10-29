package com.germanium.shortcuts

import android.graphics.Bitmap
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable internal fun ImageEditDialog(app: ShortcutsApplication, request: Interaction.ImageEdit) {
    var options by remember(request.id) { mutableStateOf(request.options) }
    var preview by remember(request.id) { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    fun change(key: String, value: String) { options = options + (key to value) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; change("secondary", uri.toString()) }
    }
    LaunchedEffect(options) {
        loading = true; delay(150)
        try { preview = AndroidImageTransforms(app).render(request.operation, request.source, options, preview = true); error = null }
        catch (e: Exception) { if (e is CancellationException) throw e; error = app.describe(e) }
        finally { loading = false }
    }
    val owned = preview
    DisposableEffect(owned) { onDispose { owned?.recycle() } }
    AlertDialog(modifier = Modifier.testTag("image_edit_dialog"), onDismissRequest = { app.interactions.reply(request, null) }, title = { Text(t("action_${request.operation}")) }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.large) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    preview?.let { Image(it.asImageBitmap(), t("image_preview"), Modifier.fillMaxSize().padding(8.dp)) }
                    if (loading) CircularProgressIndicator(Modifier.size(32.dp))
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (request.operation == "overlay_image" || request.operation == "mask_image" && options["maskShape"] == "custom") {
                OutlinedTextField(options["secondary"].orEmpty(), { change("secondary", it) }, label = { Text(t("secondary_image")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FilledTonalButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text(t("choose_image")) }
            }
            when (request.operation) {
                "rotate_image" -> ImageSlider("image_rotation", options["rotation"], 90, -360f..360f) { change("rotation", it) }
                "convert_image" -> {
                    ImageChoices("image_format", options["imageFormat"] ?: "png", listOf("png" to "format_png", "jpeg" to "format_jpeg", "webp" to "format_webp")) { change("imageFormat", it) }
                    if (options["imageFormat"] != "png") ImageSlider("image_quality", options["quality"], 90, 0f..100f) { change("quality", it) }
                }
                "resize_image" -> {
                    ImageSlider("image_width", options["width"], 1024, 0f..16384f) { change("width", it) }
                    ImageSlider("image_height", options["height"], 0, 0f..16384f) { change("height", it) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(t("keep_aspect"), Modifier.weight(1f)); ExpressiveSwitch(options["keepAspect"] != "false", { change("keepAspect", it.toString()) }) }
                }
                "compress_image" -> {
                    ImageSlider("image_quality", options["quality"], 80, 0f..100f) { change("quality", it) }
                    ImageChoices("image_format", options["imageFormat"] ?: "jpeg", listOf("jpeg" to "format_jpeg", "webp" to "format_webp")) { change("imageFormat", it) }
                }
                "mask_image" -> {
                    ImageChoices("mask_shape", options["maskShape"] ?: "ellipse", listOf("ellipse", "circle", "rounded", "rectangle", "custom").map { it to "mask_$it" }) { change("maskShape", it) }
                    if (options["maskShape"] == "rounded") ImageSlider("mask_radius", options["radius"], 25, 0f..50f) { change("radius", it) }
                    if (options["maskShape"] == "custom") ImageChoices("mask_mode", options["maskMode"] ?: "alpha", listOf("alpha" to "mask_alpha", "luminance" to "mask_luminance")) { change("maskMode", it) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(t("invert_mask"), Modifier.weight(1f)); ExpressiveSwitch(options["invert"] == "true", { change("invert", it.toString()) }) }
                }
                "overlay_image" -> {
                    ImageSlider("image_scale", options["scale"], 50, 1f..400f) { change("scale", it) }
                    ImageSlider("image_opacity", options["opacity"], 100, 0f..100f) { change("opacity", it) }
                    ImageSlider("image_rotation", options["rotation"], 0, -180f..180f) { change("rotation", it) }
                    ImageSlider("image_offset_x", options["offsetX"], 0, -2048f..2048f) { change("offsetX", it) }
                    ImageSlider("image_offset_y", options["offsetY"], 0, -2048f..2048f) { change("offsetY", it) }
                }
                "flip_image" -> ImageChoices("flip_direction", options["flipDirection"] ?: "horizontal", listOf("horizontal" to "direction_horizontal", "vertical" to "direction_vertical", "both" to "direction_both")) { change("flipDirection", it) }
                "combine_images" -> {
                    ImageChoices("combine_direction", options["direction"] ?: "horizontal", listOf("horizontal" to "direction_horizontal", "vertical" to "direction_vertical", "grid" to "direction_grid")) { change("direction", it) }
                    ImageSlider("image_spacing", options["spacing"], 0, 0f..256f) { change("spacing", it) }
                    if (options["direction"] == "grid") ImageSlider("grid_columns", options["columns"], 2, 1f..16f) { change("columns", it) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(t("match_dimensions"), Modifier.weight(1f)); ExpressiveSwitch(options["matchDimensions"] != "false", { change("matchDimensions", it.toString()) }) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { app.interactions.reply(request, JSONObject(options).toString()) }, enabled = !loading && error == null && preview != null, modifier = Modifier.testTag("confirm_image_edit")) { Text(t("done")) } }, dismissButton = { TextButton(onClick = { app.interactions.reply(request, null) }) { Text(t("cancel")) } })
}

@Composable private fun ImageChoices(label: String, selected: String, choices: List<Pair<String, String>>, change: (String) -> Unit) {
    Text(t(label), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { choices.forEach { (key, text) -> FilterChip(selected == key, { change(key) }, label = { Text(if (text in Texts.ids) t(text) else text) }) } }
}
@Composable private fun ImageSlider(label: String, source: String?, default: Int, range: ClosedFloatingPointRange<Float>, change: (String) -> Unit) {
    val value = source?.toIntOrNull() ?: default
    Text("${t(label)}: $value", style = MaterialTheme.typography.labelLarge)
    Slider(value.toFloat().coerceIn(range), { change(it.roundToInt().toString()) }, valueRange = range)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { change((value - 1).coerceIn(range.start.toInt(), range.endInclusive.toInt()).toString()) }) { Text("−") }
        OutlinedTextField(source ?: default.toString(), { if (it.length <= 7 && it.all { c -> c.isDigit() || c == '-' }) change(it) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        IconButton(onClick = { change((value + 1).coerceIn(range.start.toInt(), range.endInclusive.toInt()).toString()) }) { AppIcon(R.drawable.ic_add, description = "+") }
    }
}
