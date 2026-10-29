package com.germanium.shortcuts

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.UUID

class Version103DeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShortcutsApplication
    private fun step(type: String, vararg p: Pair<String,String>) = ActionStep(type = type, parameters = p.toMap())
    private fun flow(vararg steps: ActionStep) = Shortcut(id="verify103-${UUID.randomUUID()}",name="Verify103-${UUID.randomUUID()}",steps=steps.toList())
    private suspend fun withEditor(source: Shortcut, block: (ActivityScenario<EditorActivity>,ShortcutsViewModel)->Unit) {
        app.shortcuts.save(source)
        try { ActivityScenario.launch<EditorActivity>(Intent(app,EditorActivity::class.java).putExtra("shortcutId",source.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            var model: ShortcutsViewModel?=null
            scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);model=ViewModelProvider(it)[ShortcutsViewModel::class.java] }
            compose.waitUntil(15000) { model?.editor?.value!=null&&model?.state?.value?.storageReady==true&&compose.onAllNodesWithTag("run_shortcut").fetchSemanticsNodes().isNotEmpty() }
            block(scenario,model!!)
        } } finally { app.runs.forget(source.id);app.shortcuts.delete(source.id);File(app.filesDir,"drafts/${source.id}.xml").delete();app.updateSharingTargets() }
    }
    @Test fun basicActionsComeFirstAndEditorActionsUseIcons() = runBlocking {
        assertEquals("basic",ActionCatalog.all.first().group)
        val firstOther=ActionCatalog.all.indexOfFirst { it.group!="basic" }
        assertTrue(ActionCatalog.all.drop(firstOther).none { it.group=="basic" })
        val action=step("text","value" to "test")
        withEditor(flow(action)) { _,_ ->
            compose.onNodeWithText(app.tr("edit_shortcut")).assertExists()
            compose.onNodeWithTag("editor_actions").performScrollToNode(hasTestTag("move_up_${action.id}"))
            compose.onNodeWithTag("move_up_${action.id}").assertIsNotEnabled()
            compose.onNodeWithTag("move_down_${action.id}").assertIsNotEnabled()
            compose.onNodeWithTag("remove_action_${action.id}").assertHasClickAction()
        }
    }
    @Test fun originChoicesAppearForSharedAliasesAndDisappearForLiteralInputs() = runBlocking {
        val shared=step("share_input");val alias=step("set_variable","name" to "payload","value" to "{{@${shared.id}}}")
        val condition=ActionCatalog.find("if")!!.create(app).copy(parameters=mapOf("left" to "{{payload}}","operator" to "equals","right" to "x"))
        withEditor(flow(shared,alias,condition)) { scenario,model ->
            compose.onNodeWithTag("editor_actions").performScrollToNode(hasTestTag("choice_${condition.id}_operator_source_shortcut"))
            compose.onNodeWithTag("choice_${condition.id}_operator_source_shortcut").assertExists()
            scenario.onActivity { model.update(condition.copy(parameters=condition.parameters+("left" to "literal"))) }
            compose.onNodeWithTag("choice_${condition.id}_operator_source_shortcut").assertDoesNotExist()
            compose.onNodeWithTag("choice_${condition.id}_operator_source_system_share").assertDoesNotExist()
        }
    }
    @Test fun callsReturnToTheirCallerAndEditorStopCancelsAChild() = runBlocking {
        val child=flow(step("share_input"),step("return","value" to "child {{input}}"),step("text","value" to "unreachable"));app.shortcuts.save(child)
        try {
            val parent=flow(step("run_shortcut","shortcutUuid" to child.uuid,"input" to "payload"),step("append_text","value" to " done"))
            assertEquals("child payload done",app.engine.run(parent,ExecutionMode.STANDARD).output)
            app.shortcuts.save(child.copy(steps=listOf(step("delay","milliseconds" to "10000"))))
            withEditor(parent) { _,model ->
                compose.onNodeWithTag("run_shortcut").performClick()
                compose.waitUntil(5000) { model.state.value.runningId==parent.id }
                compose.onNodeWithTag("run_shortcut").assertContentDescriptionEquals(app.tr("stop")).performClick()
                compose.waitUntil(5000) { model.state.value.runningId==null }
                assertNull(model.state.value.result)
            }
        } finally { app.runs.forget(child.id);app.shortcuts.delete(child.id);app.updateSharingTargets() }
    }
    @Test fun mainRunButtonBecomesAStopButton() = runBlocking {
        val source=flow(step("delay","milliseconds" to "10000"));app.shortcuts.save(source)
        try { ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            var model:ShortcutsViewModel?=null;scenario.onActivity { model=ViewModelProvider(it)[ShortcutsViewModel::class.java] }
            compose.waitUntil(15000) { model?.state?.value?.items?.any { it.id==source.id }==true }
            compose.onNodeWithTag("home_content").performScrollToNode(hasTestTag("home_run_${source.id}"))
            compose.onNodeWithTag("home_run_${source.id}").performClick()
            compose.waitUntil(5000) { model!!.state.value.runningId==source.id }
            compose.onNodeWithTag("home_run_${source.id}").assertIsEnabled().performClick()
            compose.waitUntil(5000) { model!!.state.value.runningId==null }
        } } finally { app.runs.forget(source.id);app.shortcuts.delete(source.id) }
    }
    @Test fun applicationListProducesPackagesAndPermissionEntriesExist() = runBlocking {
        val value=app.actions.applicationList(false,"package",0,256) as Value.ListValue
        assertTrue(value.items.all { it is Value.Text&&'.' in it.text() });assertTrue(value.items.map { it.text() }.distinct().size==value.items.size)
        val permissions=app.packageManager.getPackageInfo(app.packageName,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(android.Manifest.permission.READ_CALENDAR in permissions)
        assertTrue(android.Manifest.permission.WRITE_CALENDAR in permissions)
        assertTrue(android.Manifest.permission.QUERY_ALL_PACKAGES in permissions)
    }
    @Test fun cancellingHttpDisconnectsAWaitingResponse() = runBlocking {
        val server=ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))
        var socket:java.net.Socket?=null
        val accepted=CompletableDeferred<Unit>()
        val serving=launch(Dispatchers.IO) { try { socket=server.accept();accepted.complete(Unit);while(socket?.getInputStream()?.read()!=-1) { } }catch(_:Exception){} }
        try {
            val request=async { app.actions.requestHttp(HttpRequestSpec("http://127.0.0.1:${server.localPort}/slow")) }
            withTimeout(5000) { accepted.await() }
            withTimeout(3000) { request.cancelAndJoin() };assertTrue(request.isCancelled)
        } finally { socket?.close();server.close();serving.cancelAndJoin() }
    }
}
