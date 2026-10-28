package com.germanium.shortcuts

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import java.util.UUID

/** All external-system operations are user initiated by the test, with unique output names. */
@RunWith(AndroidJUnit4::class)
class SystemImageEntrypointsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as ShortcutsApplication
    private fun waitFor(timeout: Long=15000, condition: ()->Boolean) { compose.waitUntil(timeout) { condition() } }
    private fun back() { instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
    private fun click(ids: List<String>, texts: List<String>, timeout: Long=10000): Boolean {
        val end=SystemClock.uptimeMillis()+timeout
        while(SystemClock.uptimeMillis()<end) {
            val root=instrumentation.uiAutomation.rootInActiveWindow
            if(root!=null) {
                val nodes=ids.flatMap { root.findAccessibilityNodeInfosByViewId(it) }+texts.flatMap { root.findAccessibilityNodeInfosByText(it) }
                for(node in nodes) { var target: AccessibilityNodeInfo?=node; repeat(3) { if(target?.isEnabled==true&&target?.isClickable==true&&target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true) return true; target=target?.parent } }
            }
            SystemClock.sleep(150)
        }
        return false
    }
    private suspend fun editor(step: ActionStep, body: (ShortcutsViewModel)->Unit) {
        val source=Shortcut(id="verify102-${UUID.randomUUID()}",name="Verify102 system entry",steps=listOf(step))
        app.shortcuts.save(source)
        try { ActivityScenario.launch<EditorActivity>(Intent(app,EditorActivity::class.java).putExtra("shortcutId",source.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            var model: ShortcutsViewModel?=null
            scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); model=ViewModelProvider(it)[ShortcutsViewModel::class.java] }
            compose.waitUntil(15000) { model?.editor?.value!=null&&model?.state?.value?.storageReady==true }
            compose.onNodeWithTag("run_shortcut").performClick(); body(model!!)
        } } finally { app.runs.forget(source.id); app.shortcuts.delete(source.id); java.io.File(app.filesDir,"drafts/${source.id}.xml").delete(); app.updateSharingTargets() }
    }
    @Test fun imagePickerOpensAndCancelStopsCleanly() = runBlocking {
        editor(ActionStep(type="get_image")) { model ->
            waitFor { (app.interactions.current.value as? Interaction.PickFile)?.launched==true }
            waitFor { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.let { it!=app.packageName }==true }
            repeat(4) {
                if (app.interactions.current.value is Interaction.PickFile) {
                    back()
                    runCatching { waitFor(2000) { app.interactions.current.value !is Interaction.PickFile } }
                }
            }
            waitFor { model.state.value.runningId==null }
            assertNull(model.state.value.result); assertNull(model.state.value.error)
        }
    }
    @Test fun cameraEntryCanBeCancelledWithoutTakingAPhoto() = runBlocking {
        editor(ActionStep(type="take_photo")) { model ->
            waitFor { app.interactions.current.value?.launched==true }
            val active=app.interactions.current.value
            if(active is Interaction.Permissions) {
                val denied=click(listOf("com.android.permissioncontroller:id/permission_deny_button","com.google.android.permissioncontroller:id/permission_deny_button","android:id/button2"),listOf("不允许","拒绝","Don’t allow","Deny"))
                if(!denied) back()
                waitFor { model.state.value.runningId==null }
                assertEquals(RunStatus.FAILED,model.state.value.result?.status)
                println("CAMERA_ENTRY=permission_denial_handled")
            } else { assertTrue(active is Interaction.TakePhoto); SystemClock.sleep(1000); back(); waitFor { model.state.value.runningId==null }; assertNull(model.state.value.error); println("CAMERA_ENTRY=system_camera_cancelled") }
        }
    }
    @Test fun screenshotSavesToPublicPicturesAndCanBeRead() = runBlocking {
        var output: String?=null
        try { editor(ActionStep(type="screenshot",parameters=mapOf("filename" to "verify102-${UUID.randomUUID()}.png"))) { model ->
            waitFor { (app.interactions.current.value as? Interaction.Capture)?.launched==true }
            assertTrue("Screen capture confirmation unavailable",click(listOf("android:id/button1","com.android.systemui:id/start_button"),listOf("立即开始","开始录制","Start now","开始共享")))
            waitFor(20000) { model.state.value.runningId==null&&model.state.value.result!=null }
            assertEquals(RunStatus.SUCCEEDED,model.state.value.result?.status)
            output=model.state.value.result!!.output; assertTrue(output!!.startsWith("content://"))
            val info=runBlocking { app.actions.image("image_info",output!!,emptyMap()) } as Value.Dictionary
            assertTrue(info.entries.getValue("width").number().signum()>0)
            val name=info.entries.getValue("name").text(); assertTrue(name.startsWith("verify102-"))
            app.contentResolver.query(Uri.parse(output),arrayOf(android.provider.MediaStore.Images.Media.RELATIVE_PATH),null,null,null)?.use { assertTrue(it.moveToFirst()); assertEquals("Pictures/Screenshots/",it.getString(0)) }
        } } finally { output?.let { app.contentResolver.delete(Uri.parse(it),null,null) } }
    }
    @Test fun pdfWithoutDirectoryOpensTheSystemSaveWindow() = runBlocking {
        var output: String?=null
        try { editor(ActionStep(type="make_pdf",parameters=mapOf("source" to "PDF system save test","filename" to "verify102-${UUID.randomUUID()}.pdf"))) { model ->
            waitFor { (app.interactions.current.value as? Interaction.SavePdf)?.launched==true }
            assertTrue("System PDF save button unavailable",click(listOf("com.android.documentsui:id/button_save","com.google.android.documentsui:id/button_save","android:id/button1"),listOf("保存","Save")))
            waitFor(20000) { model.state.value.runningId==null&&model.state.value.result!=null }
            assertEquals(RunStatus.SUCCEEDED,model.state.value.result?.status); output=model.state.value.result!!.output
            assertTrue(output!!.startsWith("content://")); assertTrue(runBlocking { AndroidImages(app).bytes(output!!).take(4).toByteArray().toString(Charsets.US_ASCII) }=="%PDF")
        } } finally { output?.let { uri -> runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver,Uri.parse(uri)) }; runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(uri),Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } } }
    }
}
