package com.germanium.shortcuts

import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class DeviceRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShortcutsApplication
    private fun step(type: String, vararg pairs: Pair<String, String>, branches: List<ActionBranch> = emptyList()) = ActionStep(type = type, parameters = pairs.toMap(), branches = branches)
    @Test fun androidIcuTemplatesAndNestedXml() = runBlocking {
        assertEquals("hello", RunContext(ExecutionMode.STANDARD, Value.Text("hello"), VariableScope()).render("{{input}}"))
        val source = Shortcut(name = "Device XML", steps = listOf(step("if", "operator" to "is_not_empty", branches = listOf(ActionBranch("then", steps = listOf(step("output")))))))
        assertEquals(source, XmlCodec().decode(XmlCodec().encode(source)))
        assertEquals("Hello, Shortcuts!\nThe workflow engine is ready.", ShortcutEngine().run(DemoShortcut.create(), ExecutionMode.STANDARD).output)
    }
    @Test fun resourceFilesMatchRequestedLanguages() {
        val expected = mapOf("en" to "Settings", "zh-Hans" to "设置", "de" to "Einstellungen", "ja" to "設定", "zh-Hant" to "設定")
        expected.forEach { (language, word) ->
            val config = Configuration(app.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(language))) }
            assertEquals(language, word, app.createConfigurationContext(config).tr("settings"))
        }
        ActionCatalog.all.forEach { definition ->
            assertTrue(definition.title, Texts.ids.containsKey(definition.title))
            assertTrue(definition.group, Texts.ids.containsKey("group_${definition.group}"))
            definition.fields.forEach { field -> assertTrue(field.label, Texts.ids.containsKey(field.label)); field.choices.forEach { assertTrue(it.second, Texts.ids.containsKey(it.second) || it.first == it.second) } }
        }
    }
    @Test fun standardShellAndStdinWorkOnAndroid() = runBlocking {
        assertEquals("Shortcuts-device-test", app.actions.shell("printf Shortcuts-device-test", ExecutionMode.STANDARD, 5000).stdout)
        val result = ShellProcessTask().run(listOf("/system/bin/sh", "-c", "cat"), 5000, ByteArrayInputStream("stream-test".toByteArray()))
        assertEquals("stream-test", result.stdout)
    }
    @Test fun crashWriterRecordsFullStackWithoutTerminatingTheTest() {
        val file = app.diagnostics.writeCrash(Thread.currentThread(), IllegalStateException("Synthetic regression test"))
        assertNotNull(file)
        try { val data = file!!.readText(); assertTrue(data.contains("Synthetic regression test")); assertTrue(data.contains("DeviceRegressionTest")); assertTrue(data.contains("Step=")) } finally { file?.delete() }
    }
    @Test fun editorRunOpensQuickLookInsteadOfCrashing() = runBlocking {
        val source = Shortcut(name = "Device regression", steps = listOf(step("text", "value" to "Hello"), step("replace_text", "find" to "Hello", "replacement" to "android-icu-ok"), step("quick_look")))
        withEditor(source) {
            compose.onNodeWithTag("run_shortcut").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("quick_look").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("android-icu-ok") and hasAnyAncestor(hasTestTag("quick_look"))).assertExists()
            compose.onNodeWithTag("close_quick_look").performClick()
        }
    }
    @Test fun menuAndIfOnlyRunChosenChildren() = runBlocking {
        val menu = step("menu", "title" to "Device menu", branches = listOf(ActionBranch("wrong", "Wrong", listOf(step("text", "value" to "wrong"))), ActionBranch("selected", "Use", listOf(step("append_text", "value" to "-selected")))))
        val conditional = step("if", "left" to "{{input}}", "operator" to "greater", "right" to "1", branches = listOf(ActionBranch("then", steps = listOf(menu))))
        val source = Shortcut(name = "Device menu regression", steps = listOf(step("text", "value" to "2"), conditional, step("append_text", "value" to "-done"), step("quick_look")))
        withEditor(source) {
            compose.onNodeWithTag("run_shortcut").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("menu_choice_selected").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("menu_choice_selected").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("quick_look").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("2-selected-done") and hasAnyAncestor(hasTestTag("quick_look"))).assertExists()
            compose.onNodeWithTag("close_quick_look").performClick()
        }
    }
    @Test fun dictionaryCanAddThreeRowsRunAndPersistThem() = runBlocking {
        val dictionary = step("dictionary")
        val source = Shortcut(name = "Dictionary editor regression", steps = listOf(dictionary, step("quick_look")))
        withEditor(source) {
            fun fill(index: Int, key: String, value: String) {
                compose.onNodeWithTag("entry_key_${dictionary.id}_$index").performScrollTo().performTextReplacement(key)
                compose.onNodeWithTag("entry_value_${dictionary.id}_$index").performScrollTo().performTextReplacement(value)
            }
            fill(0, "first", "one")
            compose.onNodeWithTag("add_entry_${dictionary.id}").performScrollTo().performClick(); fill(1, "second", "two")
            compose.onNodeWithTag("add_entry_${dictionary.id}").performScrollTo().performClick(); fill(2, "third", "three")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("entry_key_${dictionary.id}_0").performScrollTo()
            capture("dictionary")
            compose.onNodeWithTag("run_shortcut").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("quick_look").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("{first: one, second: two, third: three}") and hasAnyAncestor(hasTestTag("quick_look"))).assertExists()
            compose.onNodeWithTag("close_quick_look").performClick()
            compose.onNodeWithTag("save_shortcut").performClick()
            compose.waitUntil(15000) { runBlocking { app.shortcuts.list().find { it.id == source.id }?.steps?.firstOrNull()?.parameters?.let { dictionaryEntries(it).size == 3 } == true } }
        }
    }
    @Test fun outputPickerPreviewsAndInsertsTheSelectedOperation() = runBlocking {
        val first = step("text", "value" to "Chosen output")
        val field = step("text", "value" to "AB")
        val source = Shortcut(name = "Output picker regression", steps = listOf(first, field, step("quick_look")))
        withEditor(source) {
            compose.onNodeWithTag("field_${field.id}_value").performScrollTo().performClick()
            compose.onNodeWithTag("field_${field.id}_value").performTextInputSelection(TextRange(1))
            compose.onNodeWithTag("select_action_output").performClick()
            compose.onNodeWithTag("output_picker").assertDoesNotExist()
            compose.onNodeWithTag("shortcut_editor_fullscreen").assertExists()
            capture("output-picker")
            compose.onAllNodesWithTag("choose_output_${field.id}").assertCountEquals(0)
            compose.onNodeWithTag("choose_output_${first.id}").performScrollTo().performClick()
            compose.onNodeWithTag("field_${field.id}_value").performScrollTo().assertTextContains("A{{@${first.id}}}B")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.waitForIdle()
            compose.onNodeWithTag("run_shortcut").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("quick_look").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("AChosen outputB") and hasAnyAncestor(hasTestTag("quick_look"))).assertExists()
            compose.onNodeWithTag("close_quick_look").performClick()
        }
    }
    @Test fun draggingAHandleReordersAndPersistsTheActions() = runBlocking {
        val first = step("generate_uuid"); val second = step("current_date")
        val source = Shortcut(name = "Drag regression", steps = listOf(first, second))
        withEditor(source) {
            compose.onNodeWithTag("drag_${second.id}").performScrollTo()
            val viewport = compose.onNodeWithTag("editor_viewport").fetchSemanticsNode().boundsInRoot
            val start = compose.onNodeWithTag("drag_${second.id}").fetchSemanticsNode().boundsInRoot.center - viewport.topLeft
            val target = compose.onNodeWithTag("drag_${first.id}").fetchSemanticsNode().boundsInRoot.center - viewport.topLeft
            compose.onNodeWithTag("editor_viewport").performTouchInput {
                down(start); moveTo(start + Offset(0f, -24f), 100); moveTo(target - Offset(0f, 24f), 600); up()
            }
            capture("reordered")
            compose.onNodeWithTag("save_shortcut").performClick()
            compose.waitUntil(15000) { runBlocking { app.shortcuts.list().find { it.id == source.id }?.steps?.map { it.id } == listOf(second.id, first.id) } }
        }
    }
    @Test fun draggingNearTheEdgeScrollsPastTheOriginalRow() = runBlocking {
        val steps = List(8) { step("generate_uuid") }
        val source = Shortcut(name = "Auto scroll regression", steps = steps)
        withEditor(source) {
            val viewport = compose.onNodeWithTag("editor_viewport").fetchSemanticsNode().boundsInRoot
            val start = compose.onNodeWithTag("drag_${steps.first().id}").fetchSemanticsNode().boundsInRoot.center - viewport.topLeft
            compose.mainClock.autoAdvance = false
            try {
                compose.onNodeWithTag("editor_viewport").performTouchInput { down(start); moveTo(Offset(start.x, viewport.height - 24f), 500) }
                compose.mainClock.advanceTimeBy(1500)
                compose.waitForIdle()
                compose.onNodeWithTag("editor_viewport").performTouchInput { moveTo(Offset(start.x, viewport.height - 20f), 100); up() }
                compose.mainClock.advanceTimeByFrame()
            } finally {
                runCatching { compose.onNodeWithTag("editor_viewport").performTouchInput { cancel() } }
                compose.mainClock.autoAdvance = true
            }
            compose.onNodeWithTag("save_shortcut").performClick()
            compose.waitUntil(15000) { runBlocking { app.shortcuts.list().find { it.id == source.id }?.steps?.indexOfFirst { it.id == steps.first().id }?.let { it > 1 } == true } }
        }
    }
    @Test fun shortcutIconAndColorCanBeSelectedAndSaved() = runBlocking {
        val source = Shortcut(name = "Appearance regression", steps = listOf(step("text", "value" to "Hello")))
        withEditor(source) {
            compose.onNodeWithTag("edit_shortcut_icon").performClick()
            android.os.SystemClock.sleep(350)
            compose.onNodeWithTag("choose_color_green").performScrollTo().performClick()
            compose.onNodeWithTag("choose_color_green").assertIsSelected()
            compose.onNodeWithTag("choose_icon_heart").performScrollTo().performClick()
            compose.onNodeWithTag("choose_icon_heart").assertIsSelected()
            capture("appearance")
            compose.onNodeWithTag("close_appearance").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("shortcut_appearance_picker").fetchSemanticsNodes().isEmpty() }
            android.os.SystemClock.sleep(350)
            compose.onNodeWithTag("save_shortcut").assertIsEnabled().performClick()
            compose.waitUntil(15000) { runBlocking { app.shortcuts.list().find { it.id == source.id }?.let { it.icon == "heart" && it.color == "green" } == true } }
        }
    }
    @Test fun deletingTheResultOwnerClearsItWhileDeletingAnotherShortcutPreservesIt() = runBlocking {
        val owner = Shortcut(name = "Result owner", steps = listOf(step("text", "value" to "Owned output")))
        val other = Shortcut(name = "Other shortcut")
        app.shortcuts.save(owner); app.shortcuts.save(other)
        withModel { model ->
            onMain { model.run(owner) }
            withTimeout(10000) { model.state.first { it.result?.output == "Owned output" && it.runningId == null } }
            onMain { model.delete(other.id) }
            withTimeout(10000) { model.state.first { !it.saving && it.items.none { shortcut -> shortcut.id == other.id } } }
            assertEquals("Owned output", model.state.value.result?.output)
            onMain { model.delete(owner.id) }
            withTimeout(10000) { model.state.first { !it.saving && it.items.none { shortcut -> shortcut.id == owner.id } } }
            assertNull(model.state.value.result); assertNull(app.runs.state.value.shortcutId)
        }
        app.shortcuts.delete(owner.id); app.shortcuts.delete(other.id)
    }
    @Test fun deletingAFailedShortcutAlsoClearsItsError() = runBlocking {
        val source = Shortcut(name = "Failed owner", steps = listOf(step("get_variable", "name" to "missing")))
        app.shortcuts.save(source)
        try { withModel { model ->
            onMain { model.run(source) }
            withTimeout(10000) { model.state.first { it.result?.status == RunStatus.FAILED && it.runningId == null } }
            assertNotNull(model.state.value.error)
            onMain { model.delete(source.id) }
            withTimeout(10000) { model.state.first { !it.saving && it.items.none { shortcut -> shortcut.id == source.id } } }
            assertNull(model.state.value.result); assertNull(model.state.value.error); assertNull(model.state.value.currentStepId)
        } } finally { app.shortcuts.delete(source.id); app.runs.forget(source.id) }
    }
    @Test fun closingTheEditorKeepsItsContentDuringTheExitTransition() = runBlocking {
        val source = Shortcut(name = "Return transition regression", steps = listOf(step("text", "value" to "Final editor frame")))
        app.shortcuts.save(source)
        try { withModel { model ->
            onMain { model.openEditor(source.id, null) }
            withTimeout(10000) { model.editor.first { it != null } }
            val closing = CompletableDeferred<EditorState?>()
            onMain { model.onCloseEditor = { closing.complete(model.editor.value) }; model.closeEditor() }
            assertEquals(source, withTimeout(10000) { closing.await() }?.original)
            assertNotNull(model.editor.value)
        } } finally { app.shortcuts.delete(source.id) }
    }
    @Test fun keyboardBackspaceRemovesAWholeInlineVariable() = runBlocking {
        val first = step("text", "value" to "source")
        val token = "{{@${first.id}}}"
        val target = step("text", "value" to "before${token}after")
        val source = Shortcut(name = "Atomic variable regression", steps = listOf(first, target, step("quick_look")))
        withEditor(source) {
            val field = compose.onNodeWithTag("field_${target.id}_value")
            field.performScrollTo().performClick()
            field.performTextInputSelection(TextRange(6 + token.length))
            field.performKeyInput { pressKey(Key.Backspace) }
            field.assertTextContains("beforeafter")
            compose.onNodeWithTag("run_shortcut").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("quick_look").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("beforeafter") and hasAnyAncestor(hasTestTag("quick_look"))).assertExists()
            compose.onNodeWithTag("close_quick_look").performClick()
        }
    }
    @Test fun inlineVariableIsRenderedAsAPillAndCanRevealItsSource() = runBlocking {
        val first = step("text", "value" to "source")
        val target = step("text", "value" to "{{@${first.id}}}")
        withEditor(Shortcut(name = "Inline pill regression", steps = listOf(first, target))) {
            val field = compose.onNodeWithTag("field_${target.id}_value").performScrollTo()
            val results = mutableListOf<TextLayoutResult>()
            field.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.last()
            assertFalse(layout.layoutInput.text.text.contains("{{")); assertFalse(layout.layoutInput.text.text.contains(first.id))
            val position = layout.layoutInput.text.text.indexOf('x')
            assertTrue(position >= 0)
            compose.onNodeWithTag("tokens_${target.id}:value", useUnmergedTree = true).performTouchInput { click(layout.getBoundingBox(position).center) }
            compose.onNodeWithTag("variable_inspector").assertExists()
            capture("inline-variable")
            compose.onNodeWithTag("reveal_variable").performClick()
            compose.onNodeWithTag("action_${first.id}").assertIsDisplayed()
        }
    }
    @Test fun typingInAThousandActionEditorKeepsTheDraftAndCaret() = runBlocking {
        val steps = List(1000) { step("text", "value" to "") }
        withEditor(Shortcut(name = "Large editor regression", steps = steps)) {
            val field = compose.onNodeWithTag("field_${steps.first().id}_value").performScrollTo().performClick()
            val start = android.os.SystemClock.elapsedRealtime()
            "fluid".forEach { field.performTextInput(it.toString()) }
            field.assertTextContains("fluid")
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            java.io.File(app.cacheDir, "qa-typing.txt").writeText("1000 actions, 5 input events: ${elapsed}ms\n")
        }
    }
    @Test fun floatingNavigationAndSettingsGroupsExpandInPlace() = runBlocking {
        val original = app.settings.load()
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                compose.waitUntil(15000) { compose.onAllNodesWithTag("floating_navigation").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("nav_settings").performClick().assertIsSelected()
                compose.onNodeWithTag("settings_group_permissions").performScrollTo().performClick()
                compose.onNodeWithTag("settings_battery_permission").performScrollTo().assertIsDisplayed()
                capture("settings")
                compose.onNodeWithTag("settings_group_logs").performScrollTo().performClick()
                compose.onNodeWithTag("settings_export_logs").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("nav_automation").performClick().assertIsSelected()
                compose.onNodeWithTag("nav_shortcuts").performClick().assertIsSelected()
            }
        } finally { app.settings.save(original) }
        Unit
    }
    @Test fun numberActionAcceptsAnInlineOutputAndFeedsCalculation() = runBlocking {
        val first = step("number", "value" to "12.5")
        val second = step("number", "value" to "{{@${first.id}}}")
        withEditor(Shortcut(name = "Number input", steps = listOf(first, second, step("calculate", "left" to "{{input}}", "operation" to "*", "right" to "2"), step("quick_look")))) {
            val field = compose.onNodeWithTag("field_${first.id}_value").performScrollTo()
            field.performTextClearance(); field.performTextInput("3.25")
            compose.onNodeWithTag("run_shortcut").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("quick_look").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("6.5") and hasAnyAncestor(hasTestTag("quick_look"))).assertExists()
            compose.onNodeWithTag("close_quick_look").performClick()
        }
    }
    @Test fun customColorSlidersPersistAnRgbValue() = runBlocking {
        val source = Shortcut(name = "Custom color", steps = listOf(step("generate_uuid")))
        withEditor(source) {
            compose.onNodeWithTag("edit_shortcut_icon").performClick()
            compose.onNodeWithTag("custom_color").performScrollTo().performClick()
            compose.onNodeWithTag("color_hue").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { assertTrue(it(0.42f)) }
            compose.onNodeWithTag("color_saturation").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { assertTrue(it(0.75f)) }
            compose.onNodeWithTag("color_brightness").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { assertTrue(it(0.85f)) }
            capture("custom-color")
            compose.onNodeWithTag("close_appearance").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("shortcut_appearance_picker").fetchSemanticsNodes().isEmpty() }
            android.os.SystemClock.sleep(350)
            compose.onNodeWithTag("save_shortcut").performClick()
            compose.waitUntil(15000) { runBlocking { app.shortcuts.list().find { it.id == source.id }?.color?.matches(Regex("#[0-9A-F]{6}")) == true } }
            val saved = runBlocking { app.shortcuts.list().first { it.id == source.id } }
            assertEquals(saved.color, XmlCodec().decode(XmlCodec().encode(saved)).color)
        }
    }
    @Test fun dateAndTimezoneSelectorsStayInDialogsAndFormatCorrectly() = runBlocking {
        val date = step("format_date", "source" to "2026-10-28T00:00:00Z", "format" to "HH:mm", "zone" to "UTC")
        withEditor(Shortcut(name = "Timezone", steps = listOf(date, step("quick_look")))) {
            compose.onNodeWithTag("pick_date").performScrollTo().performClick()
            compose.onNode(isDialog()).assertExists()
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithTag("choose_timezone").performScrollTo().performClick()
            compose.onNode(isDialog()).assertExists()
            compose.onNodeWithTag("timezone_search").performTextInput("Tokyo")
            compose.onNodeWithTag("timezone_Asia/Tokyo").performClick()
            compose.onNodeWithTag("choose_timezone").assertTextContains("Asia/Tokyo", substring = true)
            compose.onNodeWithTag("run_shortcut").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("quick_look").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("09:00") and hasAnyAncestor(hasTestTag("quick_look"))).assertExists()
            compose.onNodeWithTag("close_quick_look").performClick()
        }
    }
    @Test fun bundleImportCreatesCopiesAndLeavesOriginalShortcutsUntouched() = runBlocking {
        val original = Shortcut(name = "Bundle source", color = "#12AB34", steps = listOf(step("number", "value" to "25")))
        app.shortcuts.save(original)
        val before = app.shortcuts.list().map { it.id }.toSet()
        var copies = emptySet<String>()
        try {
            withModel { model ->
                val decoded = XmlCodec().decodeImport(XmlCodec().encodeBundle(listOf(original)))
                onMain { model.importShortcuts(decoded) }
                withTimeout(10000) { model.state.first { !it.saving && it.items.any { item -> item.id !in before } } }
                val items = app.shortcuts.list(); copies = items.map { it.id }.toSet() - before
                assertEquals(1, copies.size); assertEquals(original, items.first { it.id == original.id })
                val copy = items.first { it.id in copies }; assertEquals(original.steps, copy.steps); assertEquals(original.color, copy.color)
            }
        } finally { copies.forEach { app.shortcuts.delete(it) }; app.shortcuts.delete(original.id) }
    }
    @Test fun controlCenterSlotRunsItsAssignedShortcutAndDeletionClearsBinding() = runBlocking {
        val preferences = app.getSharedPreferences("control_tiles", android.content.Context.MODE_PRIVATE)
        val previous = preferences.getString("slot3", null)
        val source = Shortcut(name = "Tile test", steps = listOf(step("number", "value" to "42"), step("quick_look")))
        app.shortcuts.save(source)
        try {
            onMain { ShortcutTiles.assign(app, 3, source.id) }
            val intent = Intent(app, TileRunActivity::class.java).putExtra("slot", 3).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<TileRunActivity>(intent).use { scenario ->
                scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                compose.waitUntil(15000) { compose.onAllNodesWithTag("quick_look").fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(hasText("42") and hasAnyAncestor(hasTestTag("quick_look"))).assertExists()
                compose.onNodeWithTag("close_quick_look").performClick()
            }
            withModel { model -> onMain { model.delete(source.id) }; withTimeout(10000) { model.state.first { !it.saving && it.items.none { item -> item.id == source.id } } } }
            assertNull(ShortcutTiles.shortcutId(app, 3))
        } finally {
            app.runs.forget(source.id); app.shortcuts.delete(source.id)
            if (previous == null) preferences.edit().remove("slot3").apply() else onMain { ShortcutTiles.assign(app, 3, previous) }
        }
    }
    @Test fun screenshotOperationRequestsFreshSystemCaptureAndCancelsCleanly() = runBlocking {
        val folder = "cancel-capture-${java.util.UUID.randomUUID()}"
        val capture = step("screenshot", "folder" to folder, "filename" to "test.png")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try { withEditor(Shortcut(name = "Capture permission", steps = listOf(capture))) {
            compose.onNodeWithTag("run_shortcut").performClick()
            compose.waitUntil(15000) { automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/button2")?.any { it.isEnabled } == true }
            val cancel = automation.rootInActiveWindow!!.findAccessibilityNodeInfosByViewId("android:id/button2").first { it.isEnabled }
            assertTrue(cancel.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitUntil(15000) { app.interactions.current.value == null && app.runs.state.value.runningId == null }
            assertNull(app.workspace.imageFile("$folder/test.png"))
        } } finally { runCatching { app.workspace.perform("delete_file", folder, "test.png", "") }; java.io.File(app.getExternalFilesDir(null), "workspace/$folder").delete() }
    }
    @Test fun systemScreenshotSavesAPngAndQuickLookShowsTheImage() = runBlocking {
        val folder = "capture-test-${java.util.UUID.randomUUID()}"
        val source = Shortcut(name = "Capture result", steps = listOf(step("screenshot", "folder" to folder, "filename" to "screen.png"), step("quick_look")))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try { withEditor(source) {
            compose.onNodeWithTag("run_shortcut").performClick()
            compose.waitUntil(15000) {
                automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/button1")?.any { it.isEnabled } == true || app.workspace.imageFile("$folder/screen.png") != null
            }
            val button = automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull { it.isEnabled }
            if (button != null) assertTrue(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitUntil(15000) { compose.onAllNodesWithTag("screenshot_preview").fetchSemanticsNodes().isNotEmpty() }
            val file = app.workspace.imageFile("$folder/screen.png") ?: error("PNG not saved")
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0); assertEquals("image/png", bounds.outMimeType)
            capture("screenshot-preview")
            compose.onNodeWithTag("close_quick_look").performClick()
        } } finally { runCatching { app.workspace.perform("delete_file", folder, "screen.png", "") }; java.io.File(app.getExternalFilesDir(null), "workspace/$folder").delete() }
    }
    @Test fun calendarProviderIncludesRepeatedCrossDayAndAllDayInstances() = runBlocking {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)
        val resolver = app.contentResolver
        var calendarUri: android.net.Uri? = null
        try {
            val account = "Shortcuts regression " + java.util.UUID.randomUUID()
            val base = android.provider.CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(android.provider.CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(android.provider.CalendarContract.Calendars.ACCOUNT_NAME, account)
                .appendQueryParameter(android.provider.CalendarContract.Calendars.ACCOUNT_TYPE, android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL).build()
            calendarUri = resolver.insert(base, android.content.ContentValues().apply {
                put(android.provider.CalendarContract.Calendars.ACCOUNT_NAME, account)
                put(android.provider.CalendarContract.Calendars.ACCOUNT_TYPE, android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(android.provider.CalendarContract.Calendars.NAME, account); put(android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, account)
                put(android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, android.provider.CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(android.provider.CalendarContract.Calendars.OWNER_ACCOUNT, account); put(android.provider.CalendarContract.Calendars.CALENDAR_COLOR, -16711936)
                put(android.provider.CalendarContract.Calendars.VISIBLE, 1); put(android.provider.CalendarContract.Calendars.SYNC_EVENTS, 1)
            }) ?: error("Test calendar could not be created")
            val id = android.content.ContentUris.parseId(calendarUri)
            val date = java.time.LocalDate.parse("2026-10-30"); val zone = java.time.ZoneId.of("Europe/Berlin")
            fun at(day: java.time.LocalDate, hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
            fun event(title: String, start: Long, end: Long, allDay: Boolean = false, repeat: Boolean = false) {
                assertNotNull(resolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, android.content.ContentValues().apply {
                    put(android.provider.CalendarContract.Events.CALENDAR_ID, id); put(android.provider.CalendarContract.Events.TITLE, title)
                    put(android.provider.CalendarContract.Events.DTSTART, start)
                    if (repeat) { put(android.provider.CalendarContract.Events.DURATION, "PT1H"); put(android.provider.CalendarContract.Events.RRULE, "FREQ=DAILY;COUNT=3") }
                    else put(android.provider.CalendarContract.Events.DTEND, end)
                    put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else zone.id)
                    put(android.provider.CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                }))
            }
            event("one", at(date, 9), at(date, 10))
            event("cross", at(date.minusDays(1), 23), at(date, 1))
            event("repeat", at(date.minusDays(1), 9), at(date.minusDays(1), 10), repeat = true)
            event("all-day", date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(), date.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(), allDay = true)
            event("outside", at(date.plusDays(1), 0), at(date.plusDays(1), 1))
            val values = app.actions.readCalendarDay(CalendarDaySpec(date, zone)) as Value.ListValue
            val rows = values.items.map { it as Value.Dictionary }.filter { it.entries["calendarId"]?.text() == id.toString() }
            assertEquals(setOf("one", "cross", "repeat", "all-day"), rows.map { it.entries.getValue("title").text() }.toSet())
            assertEquals(Value.BooleanValue(true), rows.first { it.entries["title"]?.text() == "all-day" }.entries["allDay"])
            assertTrue(rows.all { it.entries["start"] is Value.Number && it.entries["end"] is Value.Number })
        } finally {
            try { calendarUri?.let { resolver.delete(it.buildUpon().appendQueryParameter(android.provider.CalendarContract.CALLER_IS_SYNCADAPTER, "true").build(), null, null) } }
            finally { automation.dropShellPermissionIdentity() }
        }
    }
    @Test fun rustMathBackendRunsOnThePhoneWithDecimalAndScientificFunctions() {
        assertNotNull(Calculator.nativeEvaluate)
        mapOf("0.1+0.2" to "0.3", "2^3^2" to "512", "round(-2.5)" to "-3", "sin(0)" to "0", "cos(0)" to "1", "gcd(84,30)" to "6", "lcm(12,18)" to "36", "mod(-7,3)" to "2").forEach { (source, result) -> assertEquals(source, 0, Calculator().evaluate(source).compareTo(result.toBigDecimal())) }
        assertEquals(0, Calculator { if (it == "価格") "12.5".toBigDecimal() else error(it) }.evaluate("価格*2").compareTo(25.toBigDecimal()))
    }
    @Test fun resultDialogConfirmsAndThenContinuesWithoutACancelButtonByDefault() = runBlocking {
        val dialog = step("show_result", "title" to "Test title", "body" to "Test body")
        withEditor(Shortcut(name = "Result dialog", steps = listOf(dialog, step("text", "value" to "continued"), step("quick_look")))) {
            compose.onNodeWithTag("run_shortcut").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("confirm_result").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Test title") and hasAnyAncestor(isDialog())).assertExists(); compose.onNode(hasText("Test body") and hasAnyAncestor(isDialog())).assertExists()
            compose.onNodeWithTag("cancel_result").assertDoesNotExist()
            capture("result-dialog")
            compose.onNodeWithTag("confirm_result").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("quick_look").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("continued") and hasAnyAncestor(hasTestTag("quick_look"))).assertExists()
            compose.onNodeWithTag("close_quick_look").performClick()
        }
    }
    @Test fun resultDialogCancellationStopsAllFollowingActions() = runBlocking {
        val source = Shortcut(name = "Cancel dialog", steps = listOf(step("show_result", "title" to "Cancel test", "allowCancel" to "true"), step("text", "value" to "must not execute"), step("quick_look")))
        withEditor(source) {
            compose.onNodeWithTag("run_shortcut").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("cancel_result").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("cancel_result").performClick()
            compose.waitUntil(15000) { app.runs.state.value.runningId == null }
            assertNull(app.runs.state.value.result); compose.onNodeWithTag("quick_look").assertDoesNotExist()
        }
    }
    @Test fun editingOrRemovingAnActionClearsTheSharedCompletedOutput() = runBlocking {
        val first = step("number", "value" to "99"); val source = Shortcut(name = "Invalidate", steps = listOf(first))
        app.shortcuts.save(source)
        try { withModel { model ->
            onMain { model.openEditor(source.id, null) }; withTimeout(10000) { model.editor.first { it != null } }
            onMain { model.runEditor() }; withTimeout(10000) { model.state.first { it.result?.output == "99" && it.runningId == null } }
            onMain { model.remove(first.id) }
            withTimeout(10000) { model.state.first { it.result == null } }; assertNull(app.runs.state.value.result)
        } } finally { app.shortcuts.delete(source.id); app.runs.forget(source.id); java.io.File(app.filesDir, "drafts/${source.id}.xml").delete() }
    }
    @Test fun structuredFileContentsFeedDictionaryAndListOperations() = runBlocking {
        val folder = "test-${java.util.UUID.randomUUID()}"
        val value = Value.Dictionary(mapOf("values" to Value.ListValue(listOf(Value.Number(2.toBigDecimal()), Value.Number(3.toBigDecimal())))))
        try {
            app.actions.fileAction("write_file", folder, "data.xml", value, ExecutionMode.STANDARD, false)
            val read = app.actions.fileAction("file_info", folder, "data.xml", Value.Text(""), ExecutionMode.STANDARD, false)
            assertEquals(value, read)
            val result = app.engine.run(Shortcut(name = "Typed file", steps = listOf(step("file_info", "folder" to folder, "filename" to "data.xml"), step("get_dictionary_value", "key" to "values"), step("get_list_item", "selection" to "last"))), ExecutionMode.STANDARD)
            assertEquals("3", result.output)
        } finally { runCatching { app.workspace.perform("delete_file", folder, "data.xml", "") }; java.io.File(app.getExternalFilesDir(null), "workspace/$folder").delete() }
    }
    @Test fun imageCroppingUsesExactPixelsAndExposesIndividualProperties() = runBlocking {
        val folder = "image-test-${java.util.UUID.randomUUID()}"
        val bitmap = android.graphics.Bitmap.createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888)
        val data = java.io.ByteArrayOutputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }; bitmap.recycle()
        try {
            val source = app.workspace.writeBinary(folder, "input.png", data)
            val crop = app.actions.image("crop_image", source, mapOf("x" to "5", "y" to "4", "width" to "12", "height" to "10", "folder" to folder, "filename" to "crop.png"))
            val info = app.actions.image("image_info", crop.text(), emptyMap()) as Value.Dictionary
            assertEquals("12", info.entries.getValue("width").text()); assertEquals("10", info.entries.getValue("height").text())
            assertEquals("image/png", info.entries.getValue("mimeType").text())
        } finally {
            listOf("input.png", "crop.png").forEach { runCatching { app.workspace.perform("delete_file", folder, it, "") } }
            java.io.File(app.getExternalFilesDir(null), "workspace/$folder").delete()
        }
    }
    @Test fun shellStreamsAfterConfirmationAndRootWideDeletionIsRejected() = runBlocking {
        val denied = runCatching { app.actions.shell("rm -rf /*", ExecutionMode.STANDARD, 5000) }.exceptionOrNull()
        assertTrue(denied is Problem && denied.key == "err_shell_denied")
        val source = Shortcut(name = "Streaming shell", steps = listOf(step("shell", "command" to "printf first; sleep 1; printf second", "timeoutSeconds" to "5"), step("quick_look")))
        val original = app.settings.load(); app.settings.save(original.copy(mode = ExecutionMode.STANDARD))
        try { withEditor(source) {
            compose.onNodeWithTag("run_shortcut").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("confirm_result").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("confirm_result").performClick()
            compose.waitUntil(5000) { app.runs.state.value.runningId == source.id && app.runs.state.value.shellStdout == "first" }
            compose.waitUntil(15000) { compose.onAllNodesWithTag("quick_look").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("firstsecond") and hasAnyAncestor(hasTestTag("quick_look"))).assertExists()
            compose.onNodeWithTag("close_quick_look").performClick()
        } } finally { app.settings.save(original) }
    }
    @Test fun conditionalAndMenuHaveVisibleEndMarkers() = runBlocking {
        val conditional = step("if", "operator" to "is_empty", branches = listOf(ActionBranch("then")))
        val menu = step("menu", "title" to "Menu", branches = listOf(ActionBranch("choice", "Choice")))
        withEditor(Shortcut(name = "End markers", steps = listOf(conditional, menu))) {
            compose.onNodeWithTag("editor_actions").performScrollToKey("end:${conditional.id}")
            compose.onNodeWithTag("end_${conditional.id}").assertIsDisplayed()
            compose.onNodeWithTag("editor_actions").performScrollToKey("end:${menu.id}")
            compose.onNodeWithTag("end_${menu.id}").assertIsDisplayed()
            capture("end-markers")
        }
    }
    @Test fun widgetsBindToConfiguredShortcutsAndInflateOnARealAndroidHost() = runBlocking {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
        val manager = android.appwidget.AppWidgetManager.getInstance(app)
        val host = android.appwidget.AppWidgetHost(app, 100000 + (System.nanoTime() % 1000000).toInt())
        val id = host.allocateAppWidgetId()
        val source = Shortcut(name = "Widget regression", steps = listOf(step("number", "value" to "7")))
        app.shortcuts.save(source)
        try {
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, android.content.ComponentName(app, WidgetOne::class.java)))
            ShortcutWidgets.save(app, id, listOf(source.id)); ShortcutWidgets.updateAll(app)
            var view: android.appwidget.AppWidgetHostView? = null
            onMain { host.startListening(); view = host.createView(app, id, manager.getAppWidgetInfo(id)) }
            compose.waitUntil(10000) { view?.findViewById<android.widget.ImageView>(R.id.widget_icon) != null }
            assertEquals(android.view.View.VISIBLE, view!!.findViewById<android.widget.ImageView>(R.id.widget_idle_ring).visibility)
            assertEquals(android.view.View.GONE, view!!.findViewById<android.widget.ProgressBar>(R.id.widget_progress).visibility)
        } finally {
            onMain { host.stopListening(); host.deleteAppWidgetId(id); host.deleteHost() }
            ShortcutWidgets.remove(app, id); app.shortcuts.delete(source.id); automation.dropShellPermissionIdentity()
        }
    }
    @Test fun appearanceSaveCommitsThroughTheViewModelAndXmlRepository() = runBlocking {
        val source = Shortcut(name = "Save model regression", steps = listOf(step("number", "value" to "1")))
        app.shortcuts.save(source)
        try { withModel { model ->
            onMain { model.openEditor(source.id, null) }; withTimeout(10000) { model.editor.first { it != null } }
            onMain { model.editAppearance("heart", "green"); model.saveEditor(close = false) }
            withTimeout(10000) { model.state.first { !it.saving && it.items.any { s -> s.id == source.id && s.icon == "heart" && s.color == "green" } } }
            assertEquals("heart", app.shortcuts.list().first { it.id == source.id }.icon)
        } } finally { app.shortcuts.delete(source.id); java.io.File(app.filesDir, "drafts/${source.id}.xml").delete() }
    }
    @Test fun draggingFromTheLibraryInsertsAnActionAtTheChosenPosition() = runBlocking {
        val first = step("text", "value" to "first"); val second = step("text", "value" to "second")
        val source = Shortcut(name = "Library drag", steps = listOf(first, second))
        withEditor(source) {
            val target = compose.onNodeWithTag("drag_${first.id}").performScrollTo().fetchSemanticsNode().boundsInRoot.center + Offset(0f, 30f)
            compose.onNodeWithTag("open_action_library").performClick()
            capture("action-library")
            compose.onNodeWithTag("library_search").performTextInput("generate_uuid")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            val start = compose.onNodeWithTag("library_action_generate_uuid").performScrollTo().fetchSemanticsNode().boundsInRoot.center
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val down = android.os.SystemClock.uptimeMillis()
            fun event(action: Int, point: Offset) {
                val motion = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, point.x, point.y, 0)
                motion.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(automation.injectInputEvent(motion, true)) } finally { motion.recycle() }
            }
            event(android.view.MotionEvent.ACTION_DOWN, start)
            try {
                compose.waitUntil(5000) { LibraryDragSession.active != null }
                android.os.SystemClock.sleep(500)
                event(android.view.MotionEvent.ACTION_MOVE, target)
                android.os.SystemClock.sleep(200)
                capture("drag-insertion", waitForIdle = false)
                event(android.view.MotionEvent.ACTION_UP, target)
            } finally { runCatching { event(android.view.MotionEvent.ACTION_CANCEL, target) } }
            compose.onNodeWithTag("save_shortcut").assertIsEnabled().performClick()
            compose.waitUntil(15000) { runBlocking { app.shortcuts.list().find { it.id == source.id }?.steps?.map { it.type } == listOf("text", "generate_uuid", "text") } }
        }
    }
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    @Test fun currentDateAndTimeAllowsACustomFormatInTheEditor() = runBlocking {
        val date = ActionCatalog.find("current_date")!!.create(app).copy(parameters = mapOf("format" to "yyyy-MM-dd HH:mm:ss", "zone" to "UTC"))
        withEditor(Shortcut(name = "Current date and time", steps = listOf(date, step("quick_look")))) {
            compose.onNodeWithTag("field_${date.id}_format").performScrollTo().performTextReplacement("yyyy/MM/dd HH:mm:ss")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("run_shortcut").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("quick_look").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(Regex("[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}").matches((app.interactions.current.value as Interaction.QuickLook).text))
            compose.onNodeWithTag("close_quick_look").performClick()
            compose.waitUntil(10000) { app.runs.state.value.runningId == null && app.runs.state.value.result != null }
            assertTrue(app.runs.state.value.result!!.steps.first().value is Value.Text)
        }
    }
    @Test fun standaloneRoundNumberCanChooseDirectionAndDecimalPlaces() = runBlocking {
        val number = step("round_number", "source" to "-1.234", "rounding" to "nearest", "digits" to "0")
        withEditor(Shortcut(name = "Round Number", steps = listOf(number, step("quick_look")))) {
            compose.onNodeWithText(app.tr("round_down")).performScrollTo().performClick()
            compose.onNodeWithTag("field_${number.id}_digits").performScrollTo().performTextReplacement("2")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("run_shortcut").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("quick_look").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("-1.24") and hasAnyAncestor(hasTestTag("quick_look"))).assertExists()
            compose.onNodeWithTag("close_quick_look").performClick()
        }
    }
    @Test fun ordinaryRunsAndHandledFailuresDoNotWriteTraceFiles() = runBlocking {
        fun snapshot() = (app.diagnostics.directory.listFiles().orEmpty().toList() + java.io.File(app.filesDir, "logs").listFiles().orEmpty()).filter { it.isFile }.associate { it.absolutePath to (it.length() to it.lastModified()) }
        val before = snapshot()
        app.diagnostics.trace("ordinary operation", IllegalStateException("Handled test failure"))
        val source = Shortcut(name = "No trace", steps = listOf(step("text", "value" to "ok")))
        try { app.runs.start(source, ExecutionMode.STANDARD)!!.join(); assertEquals("ok", app.runs.state.value.result?.output); assertEquals(before, snapshot()) }
        finally { app.runs.forget(source.id) }
    }
    @Test fun homeSearchFiltersClearsAndRunsAMatchingShortcut() = runBlocking {
        val token = java.util.UUID.randomUUID().toString().take(8)
        val wanted = Shortcut(name = "Needle $token", steps = listOf(step("text", "value" to "Search run $token")))
        val other = Shortcut(name = "Haystack $token", steps = emptyList())
        app.shortcuts.save(wanted); app.shortcuts.save(other)
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
                compose.waitUntil(15000) { compose.onAllNodesWithTag("home_search").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("home_search").performScrollTo().performClick()
                compose.onNodeWithTag("shortcut_search_input").performTextReplacement("nEeDlE $token")
                compose.onNodeWithTag("search_result_${wanted.id}").assertExists()
                compose.onNodeWithTag("search_result_${other.id}").assertDoesNotExist()
                capture("home-search")
                compose.onNodeWithTag("shortcut_search_input").performTextReplacement("missing-$token")
                compose.onNodeWithTag("shortcut_search_empty").assertExists()
                compose.onNodeWithTag("clear_shortcut_search").performClick()
                compose.onNodeWithTag("search_result_${other.id}").assertExists()
                compose.onNodeWithTag("shortcut_search_input").performTextReplacement("Needle $token")
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                compose.waitForIdle()
                compose.onNodeWithTag("search_run_${wanted.id}").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithTag("shortcut_search").fetchSemanticsNodes().isEmpty() }
                compose.waitUntil(10000) { app.runs.state.value.result?.output == "Search run $token" && app.runs.state.value.runningId == null }
            }
        } finally { app.runs.forget(wanted.id); app.shortcuts.delete(wanted.id); app.shortcuts.delete(other.id) }
    }
    @Test fun rustTrigonometricOutputRootsAndDecimalRoundingAreClean() {
        val c = Calculator()
        mapOf("sin(30)" to "0.5", "cos(90)" to "0", "asin(0.5)" to "30", "sqrt(9)" to "3", "cbrt(-27)" to "-3", "root(81,4)" to "3", "ceil(1.234,2)" to "1.24", "floor(-1.234,2)" to "-1.24").forEach { (expression, expected) -> assertEquals(expression, expected, Value.Number(c.evaluate(expression)).text()) }
        assertNotNull(Calculator.nativeEvaluate)
    }
    @Test fun widgetServiceDisplaysItsResultAboveTheForegroundActivity() = runBlocking {
        val source = Shortcut(name = "Widget overlay", steps = listOf(step("text", "value" to "Widget overlay regression output")))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val previousMode = app.getSystemService(android.app.AppOpsManager::class.java).unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, android.os.Process.myUid(), app.packageName)
        fun overlayMode(mode: String) { automation.executeShellCommand("appops set ${app.packageName} SYSTEM_ALERT_WINDOW $mode").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
        app.shortcuts.save(source)
        try {
            overlayMode("allow")
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
                compose.waitUntil(15000) { compose.onAllNodesWithTag("floating_navigation").fetchSemanticsNodes().isNotEmpty() }
                onMain { androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, WidgetRunService::class.java).putExtra("shortcutId", source.id)) }
                compose.waitUntil(15000) { compose.onAllNodesWithText("Widget overlay regression output").fetchSemanticsNodes().isNotEmpty() }
                capture("widget-overlay")
            }
        } finally {
            app.stopService(Intent(app, WidgetRunService::class.java)); app.runs.forget(source.id); app.shortcuts.delete(source.id)
            overlayMode(when (previousMode) { android.app.AppOpsManager.MODE_ALLOWED -> "allow"; android.app.AppOpsManager.MODE_IGNORED -> "ignore"; android.app.AppOpsManager.MODE_ERRORED -> "deny"; else -> "default" })
        }
        Unit
    }
    @Test fun angleUnitSettingsPersistAndDriveTheRustRuntime() = runBlocking {
        val original = app.settings.load()
        val source = Shortcut(name = "Angle preference", steps = listOf(step("calculate_expression", "expression" to "asin(1)")))
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
                compose.waitUntil(15000) { compose.onAllNodesWithTag("floating_navigation").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("nav_settings").performClick()
                compose.onNodeWithTag("settings_group_calculation").performScrollTo().performClick()
                AngleUnit.entries.forEach { unit ->
                    compose.onNodeWithTag("angle_${unit.name}").performScrollTo().performClick()
                    compose.waitUntil(10000) { runBlocking { app.settings.load().angleUnit == unit } }
                    compose.onNodeWithTag("angle_${unit.name}").assertIsSelected()
                    val bounds = AngleUnit.entries.map { compose.onNodeWithTag("angle_${it.name}").fetchSemanticsNode().boundsInRoot }
                    bounds.forEach { rect -> assertEquals(bounds.first().width, rect.width, 1f); assertEquals(bounds.first().height, rect.height, 1f) }
                    val job = app.runs.start(source, ExecutionMode.STANDARD) ?: error("Runtime busy")
                    job.join()
                    val expected = when (unit) { AngleUnit.DEGREES -> 90.0; AngleUnit.RADIANS -> kotlin.math.PI / 2; AngleUnit.GRADIANS -> 100.0 }
                    assertEquals(RunStatus.SUCCEEDED, app.runs.state.value.result?.status)
                    assertEquals(expected, app.runs.state.value.result!!.output.toDouble(), 1e-10)
                }
                capture("angle-settings")
                compose.onNodeWithTag("settings_group_permissions").performScrollTo().performClick()
                compose.onNodeWithTag("settings_location_permission").performScrollTo().assertIsDisplayed()
            }
        } finally { app.settings.save(original); app.runs.forget(source.id) }
        Unit
    }
    @Test fun locationPropertiesRemainNumericAndIndividuallySelectable() {
        val fix = android.location.Location("synthetic").apply { latitude = 12.25; longitude = -45.5; accuracy = 8f; altitude = 100.0; time = 123456789L }
        val value = AndroidLocation.value(fix)
        assertEquals("12.25", value.entries.getValue("latitude").text())
        assertEquals("-45.5", value.entries.getValue("longitude").text())
        assertEquals("100", value.entries.getValue("altitude").text())
        assertTrue(value.entries.getValue("latitude") is Value.Number)
        assertEquals(ActionOutputs.properties("current_location").toSet(), value.entries.keys)
        assertNotNull(ActionCatalog.all.find { it.type == "current_location" })
    }
    private suspend fun withModel(block: suspend (ShortcutsViewModel) -> Unit) {
        val store = ViewModelStore()
        var model: ShortcutsViewModel? = null
        try {
            onMain { model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[ShortcutsViewModel::class.java] }
            withTimeout(10000) { model!!.state.first { it.storageReady } }; block(model!!)
        } finally { onMain { store.clear() } }
    }
    private fun capture(name: String, waitForIdle: Boolean = true) {
        if (waitForIdle) compose.waitForIdle()
        // Window-manager transitions run outside the Compose test clock.
        android.os.SystemClock.sleep(400)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        try { java.io.File(app.cacheDir, "qa-$name.png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private suspend fun withEditor(source: Shortcut, block: () -> Unit) {
        app.shortcuts.save(source)
        val intent = Intent(app, EditorActivity::class.java).putExtra("shortcutId", source.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ActivityScenario.launch<EditorActivity>(intent).use {
                var model: ShortcutsViewModel? = null
                it.onActivity { activity -> activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); model = ViewModelProvider(activity)[ShortcutsViewModel::class.java] }
                compose.waitUntil(15000) { model?.state?.value?.storageReady == true && model?.editor?.value != null }
                compose.waitUntil(15000) { runCatching { compose.onNodeWithTag("run_shortcut").assertIsEnabled(); true }.getOrDefault(false) }
                block()
            }
        } finally { app.runs.forget(source.id); app.shortcuts.delete(source.id); java.io.File(app.filesDir, "drafts/${source.id}.xml").delete() }
    }
}
