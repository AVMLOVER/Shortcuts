package com.germanium.shortcuts

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.flow.first

@RunWith(AndroidJUnit4::class)
class Version102DeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShortcutsApplication
    private fun step(type: String, vararg parameters: Pair<String, String>) = ActionStep(type = type, parameters = parameters.toMap())
    private fun flow(vararg steps: ActionStep) = Shortcut(id = "verify102-${UUID.randomUUID()}", name = "Verify102-${UUID.randomUUID()}", steps = steps.toList())
    private suspend fun withEditor(source: Shortcut, save: Boolean = true, block: (ActivityScenario<EditorActivity>, ShortcutsViewModel) -> Unit) {
        if (save) app.shortcuts.save(source)
        try { ActivityScenario.launch<EditorActivity>(Intent(app, EditorActivity::class.java).putExtra("shortcutId", source.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            var model: ShortcutsViewModel? = null
            scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); model = ViewModelProvider(it)[ShortcutsViewModel::class.java] }
            compose.waitUntil(15000) { model?.state?.value?.storageReady == true && model?.editor?.value != null }
            block(scenario, model!!)
        } } finally { app.runs.forget(source.id); app.shortcuts.delete(source.id); java.io.File(app.filesDir, "drafts/${source.id}.xml").delete(); app.updateSharingTargets() }
    }
    private suspend fun image(files: MutableList<String>, width: Int = 20, height: Int = 10): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try { for (y in 0 until height) for (x in 0 until width) bitmap.setPixel(x, y, Color.rgb(x * 47 % 256, y * 59 % 256, x * y * 31 % 256))
            return app.workspace.writeBinary("MediaTests", "${UUID.randomUUID()}.png", ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it); it.toByteArray() }).also { files += it }
        } finally { bitmap.recycle() }
    }
    private suspend fun bitmap(path: String) = AndroidImages(app).bytes(path).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    private fun cleanup(files: List<String>) = files.forEach { ref -> runCatching { if (ref.startsWith("content://") && android.net.Uri.parse(ref).authority == "${app.packageName}.files") app.contentResolver.delete(android.net.Uri.parse(ref),null,null) else app.workspace.readableFile(ref).delete() } }
    private suspend fun pdf(source: Value): String = coroutineScope {
        val oldPending = app.interactions.onPending
        app.interactions.onPending = null
        try {
            val task = async { app.actions.transformImage("make_pdf", source, emptyMap()).text() }
            val request = withTimeout(15000) { app.interactions.current.first { it is Interaction.SavePdf } } as Interaction.SavePdf
            val directory = java.io.File(app.cacheDir,"shared").also { check(it.isDirectory || it.mkdirs()) }
            val file = java.io.File(directory,"verify102-${UUID.randomUUID()}.pdf")
            val uri = androidx.core.content.FileProvider.getUriForFile(app,"${app.packageName}.files",file)
            app.interactions.reply(request,uri.toString()); task.await()
        } finally { app.interactions.onPending = oldPending }
    }
    @Test fun nativeFactorsAndStatisticsRunOnTheDevice() = runBlocking {
        assertEquals(listOf("2^3", "3^2"), (IntegerFactors.calculate(Value.Text("72"), "prime_factors", true) as Value.ListValue).items.map { it.text() })
        assertEquals(Value.BooleanValue(true), IntegerFactors.calculate(Value.Text("18446744073709551557"), "is_prime", false))
        assertEquals("2", ContentNumbers.stats(Value.ListValue("2 4 4 4 5 5 7 9".split(' ').map { Value.Text(it) }), "stddev").text())
    }
    @Test fun resizeAndCompressionProduceReadableImages() = runBlocking {
        val files = mutableListOf<String>()
        try {
            val original = image(files)
            val resized = app.actions.transformImage("resize_image", Value.Text(original), mapOf("width" to "10", "height" to "0", "folder" to "MediaTests")).text().also { files += it }
            bitmap(resized).let { try { assertEquals(10,it.width); assertEquals(5,it.height) } finally { it.recycle() } }
            val compressed = app.actions.transformImage("compress_image", Value.Text(original), mapOf("quality" to "40", "imageFormat" to "jpeg", "folder" to "MediaTests")).text().also { files += it }
            val bytes = AndroidImages(app).bytes(compressed); val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            assertEquals("image/jpeg",bounds.outMimeType); assertEquals(20,bounds.outWidth); assertEquals(10,bounds.outHeight)
        } finally { cleanup(files) }
    }
    @Test fun textImageAndPaginatedPdfHaveTheExpectedFormat() = runBlocking {
        val files = mutableListOf<String>()
        try {
            val png = app.actions.transformImage("text_image", Value.Text("Hello\nShortcuts"), mapOf("width" to "200", "fontSize" to "20", "padding" to "10", "transparentBackground" to "true", "folder" to "MediaTests")).text().also { files += it }
            bitmap(png).let { try { assertEquals(200,it.width); assertTrue(it.height > 40); assertEquals(0, Color.alpha(it.getPixel(0,0))) } finally { it.recycle() } }
            val pdf = pdf(Value.Text("Text page\n".repeat(200))).also { files += it }
            PdfRenderer(app.contentResolver.openFileDescriptor(android.net.Uri.parse(pdf),"r")!!).use { assertTrue(it.pageCount > 1) }
        } finally { cleanup(files) }
    }
    @Test fun aNewEmptyEditorIsNotSaved() = runBlocking {
        val source = flow()
        withEditor(source, save = false) { scenario, _ ->
            compose.onNodeWithTag("save_shortcut").performClick()
            compose.waitUntil(15000) { scenario.state == Lifecycle.State.DESTROYED }
            assertFalse(runBlocking { app.shortcuts.list().any { it.id == source.id } })
        }
    }
    @Test fun deletingEveryActionThenDoneRemovesOnlyTheTestShortcut() = runBlocking {
        val source = flow(step("text", "value" to "temporary"))
        withEditor(source) { scenario, model ->
            scenario.onActivity { model.editSteps(emptyList()) }
            compose.onNodeWithTag("save_shortcut").performClick()
            compose.waitUntil(15000) { scenario.state == Lifecycle.State.DESTROYED }
            assertFalse(runBlocking { app.shortcuts.list().any { it.id == source.id } })
        }
    }
    @Test fun onlyEnabledOutputSuggestionsAppearInTheVariableBar() = runBlocking {
        val first = step("text", "value" to "suggestion"); val second = step("text", "value" to "")
        withEditor(flow(first,second)) { _, _ ->
            compose.onNodeWithTag("field_${second.id}_value").performScrollTo().performClick()
            compose.onNodeWithTag("variable_bar_@${first.id}").assertDoesNotExist()
            compose.onNodeWithTag("variable_bar_toggle_${first.id}").performScrollTo().assertIsOff().performClick()
            compose.onNodeWithTag("field_${second.id}_value").performScrollTo().performClick()
            compose.onNodeWithTag("editor_variable_bar").performScrollToNode(hasTestTag("variable_bar_@${first.id}"))
            compose.onNodeWithTag("variable_bar_@${first.id}").assertExists().performClick()
        }
    }
    @Test fun conditionalRepeatHasFooterComparisonsAndSeparateCount() = runBlocking {
        val body = step("calculate", "operation" to "+", "right" to "1")
        val repeat = ActionStep(type="repeat_until", parameters=mapOf("left" to "{{input}}", "operator" to "greater_equal", "right" to "3"), branches=listOf(ActionBranch("body",steps=listOf(body))))
        withEditor(flow(step("number","value" to "0"),repeat)) { _, model ->
            compose.onNodeWithTag("editor_actions").performScrollToNode(hasTestTag("repeat_condition_output_${repeat.id}"))
            compose.onNodeWithTag("repeat_condition_output_${repeat.id}").performScrollTo().assertExists()
            compose.onNodeWithTag("repeat_condition_compare_${repeat.id}").performScrollTo().assertExists()
            compose.onNodeWithTag("run_shortcut").performClick()
            compose.waitUntil(15000) { model.state.value.runningId == null && model.state.value.result != null }
            val result = model.state.value.result!!; assertEquals("3",result.output); assertEquals("3",result.steps.last().properties["repeatCount"]?.text())
        }
    }
    @Test fun interactiveImageEditingContinuesAfterConfirmation() = runBlocking {
        val files = mutableListOf<String>()
        try {
            val input = image(files)
            withEditor(flow(step("flip_image","source" to input,"interactive" to "true","folder" to "MediaTests"))) { _, model ->
                compose.onNodeWithTag("run_shortcut").performClick()
                compose.waitUntil(15000) { compose.onAllNodesWithTag("image_edit_dialog").fetchSemanticsNodes().isNotEmpty() }
                compose.waitUntil(15000) { runCatching { compose.onNodeWithTag("confirm_image_edit").assertIsEnabled(); true }.getOrDefault(false) }
                compose.onNodeWithTag("confirm_image_edit").performClick()
                compose.waitUntil(15000) { model.state.value.runningId == null && model.state.value.result != null }
                assertEquals(RunStatus.SUCCEEDED,model.state.value.result!!.status)
                files += model.state.value.result!!.output
            }
        } finally { cleanup(files) }
    }
    @Test fun sharedTextIsPassedOnlyToAnEligibleSelectedShortcut() = runBlocking {
        val source = flow(step("share_input"), step("get_numbers_from_input"),step("statistics","statistic" to "average"),step("format_number","digits" to "2","locale" to "en-US"))
        app.shortcuts.save(source)
        try {
            SharingTargets.refresh(app)
            ActivityScenario.launch<ShareActivity>(Intent(app,ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"1 2 3").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
                compose.waitUntil(15000) { compose.onAllNodesWithText(source.name).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(source.name).performClick()
                compose.waitUntil(15000) { compose.onAllNodesWithText("2.00").fetchSemanticsNodes().isNotEmpty() }
            }
        } finally { app.runs.forget(source.id); app.shortcuts.delete(source.id); SharingTargets.refresh(app) }
    }
}
