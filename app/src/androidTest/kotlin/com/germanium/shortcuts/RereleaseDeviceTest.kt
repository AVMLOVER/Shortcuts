package com.germanium.shortcuts

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RereleaseDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShortcutsApplication
    private fun step(type: String, vararg parameters: Pair<String, String>) = ActionStep(type = type, parameters = parameters.toMap())
    private fun flow(vararg steps: ActionStep) = Shortcut(id = "verify102fix-${UUID.randomUUID()}", name = "Verify102fix-${UUID.randomUUID()}", steps = steps.toList())
    private suspend fun withEditor(source: Shortcut, block: (ActivityScenario<EditorActivity>, ShortcutsViewModel) -> Unit) {
        app.shortcuts.save(source)
        try { ActivityScenario.launch<EditorActivity>(Intent(app, EditorActivity::class.java).putExtra("shortcutId", source.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            var model: ShortcutsViewModel? = null
            scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); model = ViewModelProvider(it)[ShortcutsViewModel::class.java] }
            compose.waitUntil(15000) { model?.state?.value?.storageReady == true && model?.editor?.value != null }
            compose.waitUntil(15000) { runCatching { compose.onNodeWithTag("run_shortcut").assertIsEnabled(); compose.onNodeWithTag("editor_export").assertExists(); true }.getOrDefault(false) }
            block(scenario, model!!)
        } } finally { app.runs.forget(source.id); app.shortcuts.delete(source.id); java.io.File(app.filesDir, "drafts/${source.id}.xml").delete(); app.updateSharingTargets() }
    }
    @Test fun conditionalCounterReplacementRunsThroughNativeDivision() = runBlocking {
        val divide = step("calculate", "left" to "{{counter}}", "operation" to "/", "right" to "3")
        val loop = step("repeat_until", "left" to "{{counter}}", "operator" to "less", "right" to "1").copy(branches = listOf(ActionBranch("body", steps = listOf(divide, step("set_variable", "name" to "counter", "value" to "{{@${divide.id}}}")))))
        val source = flow(step("create_variable", "name" to "counter", "valueType" to "number", "value" to "111"), loop)
        val result = app.engine.run(source, ExecutionMode.STANDARD)
        assertEquals(RunStatus.SUCCEEDED, result.status); assertTrue(result.variables.getValue("counter").number() < 1.toBigDecimal())
        assertEquals("5", result.steps.last().properties["repeatCount"]?.text())
    }
    @Test fun dragTargetsUseTheWholeControlAndSupportAllBranches() {
        listOf("if", "menu", "repeat_until").forEach { type ->
            val drag = EditorDragState()
            drag.items = mapOf("outside" to DragItem("outside", DragScope(null, null), "Outside"), "control" to DragItem("control", DragScope(null, null), type), "child" to DragItem("child", DragScope("control", "body"), "Child"))
            drag.beforeBounds["control"] = Rect(0f, 100f, 400f, 180f)
            drag.beforeBounds["child"] = Rect(10f, 200f, 400f, 280f)
            drag.afterBounds["child"] = Rect(10f, 200f, 400f, 280f)
            drag.afterBounds["control"] = Rect(0f, 400f, 400f, 500f)
            drag.branchEnds["body"] = DragScope("control", "body") to Rect(10f, 300f, 400f, 350f)
            assertEquals(DragDestination("child", "control", true), drag.destinationAt(Offset(50f, 99f), "child"))
            assertEquals(DragDestination("child", "control", false), drag.destinationAt(Offset(50f, 501f), "child"))
            assertEquals(DragDestination("outside", "control", false, "body"), drag.destinationAt(Offset(50f, 325f), "outside"))
            assertNull(drag.destinationAt(Offset(50f, 325f), "control"))
            drag.origin=Offset(0f,60f)
            drag.handles["child"]=Rect(10f,200f,100f,280f)
            assertEquals("child",drag.hit(Offset(50f,180f)))
            assertEquals(DragDestination("child","control",true),drag.destinationAt(Offset(50f,39f),"child"))
        }
    }
    @Test fun variableActionsAlwaysAppearAndGetVariableChoosesNamedOrMagicSources() = runBlocking {
        val source = step("create_variable", "name" to "counter", "value" to "42", "inVariableBar" to "false")
        val get = step("get_variable", "name" to "input")
        withEditor(flow(source, get)) { _, model ->
            compose.onNodeWithTag("variable_bar_toggle_${source.id}").assertDoesNotExist()
            compose.onNodeWithTag("editor_actions").performScrollToNode(hasTestTag("variable_source_${get.id}"))
            compose.onNodeWithTag("variable_bar_toggle_${get.id}").assertDoesNotExist()
            compose.onNodeWithTag("variable_source_${get.id}").performClick()
            compose.onNodeWithTag("variable_source_named_${get.id}_counter").performClick()
            compose.waitUntil { allSteps(model.editor.value!!.steps).last().parameters["name"] == "counter" }
            compose.onNodeWithTag("variable_source_${get.id}").performClick()
            compose.onNodeWithTag("variable_source_magic_${get.id}").performClick()
            compose.onNodeWithTag("editor_actions").performScrollToNode(hasTestTag("choose_output_${source.id}"))
            compose.onNodeWithTag("choose_output_${source.id}").performClick()
            compose.waitUntil { allSteps(model.editor.value!!.steps).last().parameters["name"] == "@${source.id}" }
            val result = runBlocking { app.engine.run(model.editor.value!!.shortcut(), ExecutionMode.STANDARD) }
            assertEquals("42", result.output)
        }
    }
    @Test fun removingASourceMarksItsExistingReferenceAsAnError() = runBlocking {
        val source = step("number", "value" to "9"); val target = step("text", "value" to "{{@${source.id}}}")
        withEditor(flow(source, target)) { scenario, model ->
            scenario.onActivity { model.remove(source.id) }
            compose.onNodeWithTag("editor_actions").performScrollToNode(hasTestTag("field_${target.id}_value"))
            compose.onNodeWithTag("field_${target.id}_value").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            assertEquals("{{@${source.id}}}", model.editor.value!!.steps.single().parameters["value"])
            compose.onNodeWithTag("field_${target.id}_value").performTextReplacement("repaired")
            compose.onNodeWithTag("field_${target.id}_value").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        }
    }
    @Test fun removeButtonDeletesTheControlAndItsEntireNestedBody() = runBlocking {
        val inner = step("if", "left" to "x", "right" to "x").copy(branches = listOf(ActionBranch("then", steps = listOf(step("text", "value" to "nested")))))
        val group = step("repeat_until", "operator" to "is_not_empty").copy(branches = listOf(ActionBranch("body", steps = listOf(inner))))
        val after = step("comment", "note" to "keep")
        withEditor(flow(group, after)) { _, model ->
            compose.onNodeWithTag("editor_actions").performScrollToNode(hasTestTag("remove_action_${group.id}"))
            compose.onNodeWithTag("remove_action_${group.id}").performClick()
            compose.waitUntil { model.editor.value!!.steps.map { it.id } == listOf(after.id) }
            assertEquals(listOf(after.id), allSteps(model.editor.value!!.steps).map { it.id })
        }
    }
    @Test fun aBodyActionCanBeDraggedBeforeItsRepeatHeader() = runBlocking {
        val child = step("comment", "note" to "move")
        val group = step("repeat_until", "operator" to "is_not_empty").copy(branches = listOf(ActionBranch("body", steps = listOf(child))))
        withEditor(flow(group)) { _, model ->
            compose.onNodeWithTag("drag_${child.id}").performScrollTo()
            val viewport = compose.onNodeWithTag("editor_viewport").fetchSemanticsNode().boundsInRoot
            val start = compose.onNodeWithTag("drag_${child.id}").fetchSemanticsNode().boundsInRoot.center - viewport.topLeft
            val target = compose.onNodeWithTag("action_${group.id}").fetchSemanticsNode().boundsInRoot.topLeft - viewport.topLeft + Offset(20f, 1f)
            compose.onNodeWithTag("editor_viewport").performTouchInput { down(start); moveTo(start + Offset(0f,-24f),100); moveTo(target,400); up() }
            compose.waitUntil { model.editor.value!!.steps.first().id == child.id }
            assertTrue(model.editor.value!!.steps.last().branches.single().steps.isEmpty())
        }
    }
    @Test fun homeCopyIconCreatesAnIndependentMatchingShortcutWithCopySuffix() = runBlocking {
        val number = step("number", "value" to "7")
        val source = flow(number, step("get_variable", "name" to "@${number.id}")).copy(icon = "star", color = "#246080")
        app.shortcuts.save(source)
        val created = mutableListOf<String>()
        try { ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            var model: ShortcutsViewModel? = null
            scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); model = ViewModelProvider(it)[ShortcutsViewModel::class.java] }
            compose.waitUntil(15000) { model?.state?.value?.storageReady == true && model!!.state.value.items.any { it.id == source.id } }
            compose.onNodeWithTag("home_content").performScrollToNode(hasTestTag("copy_shortcut_${source.id}"))
            compose.onNodeWithTag("copy_shortcut_${source.id}").performClick()
            compose.waitUntil(15000) { model!!.state.value.items.any { it.name == source.name + " Copy" } }
            val copy = model!!.state.value.items.single { it.name == source.name + " Copy" }; created += copy.id
            assertNotEquals(source.id,copy.id); assertEquals(source.icon,copy.icon); assertEquals(source.color,copy.color)
            assertEquals("7",app.engine.run(copy,ExecutionMode.STANDARD).output)
            assertEquals(source,app.shortcuts.list().single { it.id == source.id })
        } } finally { created.forEach { app.shortcuts.delete(it) }; app.shortcuts.list().filter { it.name == source.name + " Copy" }.forEach { app.shortcuts.delete(it.id) }; app.shortcuts.delete(source.id); app.updateSharingTargets() }
    }
    @Test fun undoAndRedoRestoreGroupsParametersAndAppearanceWithoutOldResults() = runBlocking {
        val child=step("text","value" to "value")
        val group=step("if","left" to "x","right" to "x").copy(branches=listOf(ActionBranch("then",steps=listOf(child))))
        val after=step("comment","note" to "keep"); val source=flow(group,after)
        withEditor(source) { scenario,model ->
            compose.onNodeWithTag("editor_undo").assertIsNotEnabled();compose.onNodeWithTag("editor_redo").assertIsNotEnabled()
            scenario.onActivity { model.runEditor() }
            compose.waitUntil(15000) { model.state.value.runningId==null&&model.state.value.result!=null }
            scenario.onActivity { model.remove(group.id) }
            compose.onNodeWithTag("editor_undo").assertIsEnabled().performClick()
            compose.waitUntil { model.editor.value!!.steps==source.steps }
            assertNull(model.state.value.result);compose.onNodeWithTag("editor_redo").performClick()
            compose.waitUntil { model.editor.value!!.steps.map{it.id}==listOf(after.id) }
            compose.onNodeWithTag("editor_undo").performClick()
            scenario.onActivity { model.editName(source.name+" changed");model.editAppearance("star","#246080") }
            compose.onNodeWithTag("editor_undo").performClick();assertEquals(source.icon,model.editor.value!!.icon)
            compose.onNodeWithTag("editor_undo").performClick();assertEquals(source.name,model.editor.value!!.name)
            compose.onNodeWithTag("editor_redo").performClick();assertEquals(source.name+" changed",model.editor.value!!.name)
            scenario.onActivity { model.place(after.id,group.id,true) }
            compose.onNodeWithTag("editor_redo").assertIsNotEnabled()
            compose.onNodeWithTag("editor_undo").performClick();assertEquals(source.steps,model.editor.value!!.steps)
        }
    }
    @Test fun exportUndoAndRedoShowLongPressTooltips() = runBlocking {
        withEditor(flow(step("comment","note" to "tooltip"))) { scenario,model ->
            scenario.onActivity { model.editName("Tooltip changed");model.editAppearance("star","#246080");model.undo() }
            compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("editor_export").assertIsEnabled();compose.onNodeWithTag("editor_undo").assertIsEnabled();compose.onNodeWithTag("editor_redo").assertIsEnabled();true }.getOrDefault(false) }
            compose.mainClock.autoAdvance=false
            try { listOf("editor_export" to "export","editor_undo" to "undo","editor_redo" to "redo").forEach { (tag,label) ->
                compose.onNodeWithTag(tag).performTouchInput { longClick() }
                compose.mainClock.advanceTimeBy(200)
                compose.onNodeWithText(app.tr(label)).assertIsDisplayed()
                compose.mainClock.advanceTimeBy(2000)
            } } finally { compose.mainClock.autoAdvance=true }
        }
    }
}
