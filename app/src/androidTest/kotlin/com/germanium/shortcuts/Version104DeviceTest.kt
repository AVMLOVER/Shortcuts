package com.germanium.shortcuts

import android.app.AppOpsManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** No test in this class invokes root power commands or partition writes. */
class Version104DeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as ShortcutsApplication
    private fun onMain(action:()->Unit)=instrumentation.runOnMainSync(action)
    private fun step(type:String,vararg p:Pair<String,String>)=ActionStep(type=type,parameters=p.toMap())
    private suspend fun run(vararg steps:ActionStep)=app.engine.run(Shortcut(name="Verify104",steps=steps.toList()),ExecutionMode.STANDARD)
    @Test fun nativeRustDurationJsonAndUrlWorkTogether()=runBlocking {
        assertNotNull(Calculator.nativeEvaluate)
        assertEquals("1.5",run(step("date_difference","start" to "2026-10-29T00:00:00Z","end" to "2026-10-29T01:30:00Z","unit" to "hours")).output)
        assertEquals("42",run(step("text","value" to "{\"n\":42,\"empty\":null}"),step("get_dictionary_from_input"),step("get_dictionary_value","dictionary" to "{{input}}","key" to "n")).output)
        assertEquals("https://example.com",run(step("url","url" to "example.com")).output)
    }
    @Test fun imageRotationConversionAndQrRoundTrip()=runBlocking {
        val folder="Verify104-${UUID.randomUUID()}";val bitmap=Bitmap.createBitmap(12,8,Bitmap.Config.ARGB_8888)
        val original=ByteArrayOutputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it);it.toByteArray()};bitmap.recycle()
        val image=app.workspace.writeBinary(folder,"input.png",original)
        var qr:String?=null
        try {
            val rotated=run(step("rotate_image","source" to image,"rotation" to "90","folder" to folder,"filename" to "rotated.png"))
            assertEquals(RunStatus.SUCCEEDED,rotated.status)
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};val bytes=app.actions.readBinary(rotated.output);BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds);assertEquals(8,bounds.outWidth);assertEquals(12,bounds.outHeight)
            val converted=run(step("convert_image","source" to image,"imageFormat" to "jpeg","quality" to "85","folder" to folder,"filename" to "converted.jpg"))
            assertEquals(RunStatus.SUCCEEDED,converted.status);val jpeg=app.actions.readBinary(converted.output);assertEquals(0xff,jpeg[0].toInt() and 255);assertEquals(0xd8,jpeg[1].toInt() and 255)
            val generated=run(step("generate_qr","source" to "Shortcuts 1.0.4 https://example.com","size" to "512","correction" to "M"));assertEquals(RunStatus.SUCCEEDED,generated.status);qr=generated.output
            val decoded=run(step("read_barcode","source" to generated.output));assertEquals(RunStatus.SUCCEEDED,decoded.status);assertEquals("Shortcuts 1.0.4 https://example.com",decoded.output)
        } finally { cleanupFolder(folder);qr?.let{runCatching{app.workspace.streamFile(it).delete()}} }
    }
    @Test fun zipArchivesPreserveBinaryFilesAndTypedValues()=runBlocking {
        val folder="Verify104-${UUID.randomUUID()}"
        val one=app.workspace.importBinary(folder,"a.bin",byteArrayOf(0,1,2,-1));val two=app.workspace.importBinary(folder,"b.txt","hello".toByteArray())
        try {
            val archive=app.actions.archive(Value.ListValue(listOf(Value.Text(one),Value.Text(two))),"$folder/Archives","sample.zip",false)
            val result=app.actions.archive(archive,"$folder/Extracted","",true) as Value.ListValue
            assertEquals(2,result.items.size);assertArrayEquals(byteArrayOf(0,1,2,-1),app.actions.readBinary(result.items.first().text()))
            assertEquals("hello",app.actions.readBinary(result.items.last().text()).toString(Charsets.UTF_8))
        } finally { cleanupFolder(folder) }
    }
    @Test fun quickLookShowsImagesAndSharedImageListsWithoutDisplayingTheirPaths():Unit=runBlocking {
        val folder="Verify104-${UUID.randomUUID()}"
        val bitmap=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(80,160,220)) }
        val bytes=ByteArrayOutputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it);it.toByteArray()};bitmap.recycle()
        val path=app.workspace.importBinary(folder,"preview.bin",bytes)
        val cache=File(app.cacheDir,"shared/quicklook-${UUID.randomUUID()}.dat").apply { parentFile!!.mkdirs();writeBytes(bytes) }
        val uri=FileProvider.getUriForFile(app,"${app.packageName}.files",cache).toString()
        try { ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            val inputs=listOf<Value>(Value.ListValue(listOf(Value.Text(path),Value.Text(uri))),Value.Dictionary(linkedMapOf("_shared" to Value.BooleanValue(true),"images" to Value.ListValue(listOf(Value.Text(uri))),"files" to Value.ListValue(listOf(Value.Text(uri))))),Value.Text("Verify104 ordinary text"))
            for ((index,input) in inputs.withIndex()) {
                val task=async(Dispatchers.Default){app.actions.quickLook(input)}
                try {
                    compose.waitUntil(15000){runCatching{compose.onAllNodesWithTag("quick_look").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
                    if(index<2) {
                        compose.waitUntil(15000){compose.onAllNodesWithTag("quick_look_image_0").fetchSemanticsNodes().isNotEmpty()}
                        compose.onNodeWithText(path).assertDoesNotExist();compose.onNodeWithText(uri).assertDoesNotExist()
                        if(index==0) {compose.waitUntil(15000){compose.onAllNodesWithTag("quick_look_image_1").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithTag("quick_look_image_1").performScrollTo()}
                        if(index==1) {val screen=instrumentation.uiAutomation.takeScreenshot();try{File(app.cacheDir,"qa-quicklook104.png").outputStream().use{screen.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{screen.recycle()}}
                    } else {compose.waitUntil(15000){compose.onAllNodesWithText("Verify104 ordinary text").fetchSemanticsNodes().isNotEmpty()}}
                    compose.onNodeWithTag("close_quick_look").performClick();task.await()
                } finally {task.cancelAndJoin()}
            }
        } } finally {cleanupFolder(folder);cache.delete()}
    }
    @Test fun editorAcceptsTenThousandActionsAndEnforcesTheNewBoundary():Unit=runBlocking {
        val source=Shortcut(id="verify104-${UUID.randomUUID()}",name="Verify104 capacity",steps=List(10000){step("text","value" to "$it")})
        app.shortcuts.save(source)
        assertEquals(10000,app.shortcuts.list().single{it.id==source.id}.steps.size)
        try { ActivityScenario.launch<EditorActivity>(Intent(app,EditorActivity::class.java).putExtra("shortcutId",source.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            var model:ShortcutsViewModel?=null;scenario.onActivity{model=ViewModelProvider(it)[ShortcutsViewModel::class.java]}
            compose.waitForIdle()
            try { compose.waitUntil(15000){model?.editor?.value!=null || model?.state?.value?.error!=null} }
            catch(e:Exception) { println("CAPACITY_READY=${model?.state?.value?.storageReady} EDITOR=${model?.editor?.value!=null} ERROR=${model?.state?.value?.error}");throw e }
            assertNull(model!!.state.value.error)
            assertEquals(source.id,model!!.editor.value!!.original.id)
            compose.waitUntil(15000){runCatching{compose.onAllNodesWithTag("open_action_library").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
            assertEquals(10000,allSteps(model!!.editor.value!!.steps).size);compose.onNodeWithTag("open_action_library").assertIsNotEnabled()
            onMain{model!!.remove(source.steps.last().id)};compose.waitUntil(15000){model!!.editor.value!!.steps.size==9999};compose.onNodeWithTag("open_action_library").assertIsEnabled()
        } } finally { app.shortcuts.delete(source.id);File(app.filesDir,"drafts/${source.id}.xml").delete();app.updateSharingTargets() }
    }
    @Test fun powerOperationsRejectStandardAndShizukuWithoutCallingRoot()=runBlocking {
        for(mode in listOf(ExecutionMode.STANDARD,ExecutionMode.SHIZUKU)){
            for(reboot in listOf(false,true)){
                val failure=runCatching{app.actions.power(reboot,mode,"system")}.exceptionOrNull();assertTrue(failure is Problem);assertEquals("err_root_required",(failure as Problem).key)
            }
        }
        assertEquals(129,ActionCatalog.all.size)
        assertEquals(PowerTargets.all,Version104Catalog.all.single{it.type=="reboot"}.fields.single().choices.map{it.first})
    }
    @Test fun widgetWithoutOverlayPermissionOnlyOpensGuidance()=runBlocking {
        widget(false,listOf(step("text","value" to "must-not-run"))) { view,source ->
            val before=app.runs.state.value.shortcutId
            onMain{view.findViewById<android.view.View>(R.id.widget_item).performClick()}
            compose.waitUntil(15000){compose.onAllNodesWithTag("widget_permission_required").fetchSemanticsNodes().isNotEmpty()}
            assertEquals(before,app.runs.state.value.shortcutId);assertNotEquals(source.id,app.runs.state.value.runningId)
            onMain{app.foreground()?.finish()}
        }
    }
    @Test fun widgetRunsAndInteractsOnTheDesktopWithoutOpeningAnActivity()=runBlocking {
        val steps=listOf(step("ask_input","title" to "Verify104 input","prompt" to "Value","inputType" to "text"),step("show_result","title" to "Verify104 result","body" to "{{input}}","allowCancel" to "true"))
        widget(true,steps) { view,source ->
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            compose.waitUntil(15000){app.foreground()==null}
            onMain{view.findViewById<android.view.View>(R.id.widget_item).performClick()}
            compose.waitUntil(15000){runCatching{compose.onAllNodesWithTag("widget_overlay_input").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
            assertNull(app.foreground());compose.onNodeWithTag("widget_overlay_input").performTextInput("desktop result");compose.onNodeWithTag("widget_overlay_confirm").performClick()
            compose.waitUntil(15000){compose.onAllNodesWithText("Verify104 result").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("widget_overlay_confirm").performClick()
            compose.waitUntil(15000){app.runs.state.value.shortcutId==source.id&&app.runs.state.value.runningId==null}
            compose.onNodeWithText("desktop result").assertExists();assertNull(app.foreground())
            onMain{view.findViewById<android.view.View>(R.id.widget_item).performClick()}
            compose.waitUntil(15000){runCatching{compose.onAllNodesWithTag("widget_overlay_input").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
            compose.onNodeWithText(app.tr("cancel")).performClick()
        }
    }
    private suspend fun widget(allowed:Boolean,steps:List<ActionStep>,block:suspend(android.appwidget.AppWidgetHostView,Shortcut)->Unit){
        val automation=instrumentation.uiAutomation;val ops=app.getSystemService(AppOpsManager::class.java)
        val previous=ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,android.os.Process.myUid(),app.packageName)
        fun setMode(mode:String){automation.executeShellCommand("appops set ${app.packageName} SYSTEM_ALERT_WINDOW $mode").use{ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}}
        automation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
        val manager=AppWidgetManager.getInstance(app);val host=AppWidgetHost(app,104000+(System.nanoTime()%100000).toInt());val id=host.allocateAppWidgetId()
        val source=Shortcut(id="verify104-${UUID.randomUUID()}",name="Verify104 widget",steps=steps)
        app.shortcuts.save(source)
        try {
            setMode(if(allowed)"allow" else "ignore")
            assertTrue(manager.bindAppWidgetIdIfAllowed(id,ComponentName(app,WidgetOne::class.java)))
            ShortcutWidgets.save(app,id,listOf(source.id));ShortcutWidgets.updateAll(app)
            var view:android.appwidget.AppWidgetHostView?=null
            onMain{host.startListening();view=host.createView(app,id,manager.getAppWidgetInfo(id))}
            compose.waitUntil(15000){view?.findViewById<android.view.View>(R.id.widget_item)!=null}
            block(view!!,source)
        } finally {
            onMain{app.stopService(Intent(app,WidgetRunService::class.java));host.stopListening();host.deleteAppWidgetId(id);host.deleteHost()}
            app.runs.forget(source.id);ShortcutWidgets.remove(app,id);app.shortcuts.delete(source.id);app.updateSharingTargets()
            setMode(when(previous){AppOpsManager.MODE_ALLOWED->"allow";AppOpsManager.MODE_IGNORED->"ignore";AppOpsManager.MODE_ERRORED->"deny";else->"default"});automation.dropShellPermissionIdentity()
        }
    }
    private fun cleanupFolder(name:String){val root=File(app.getExternalFilesDir(null)?:app.filesDir,"workspace").canonicalFile;val target=File(root,name).canonicalFile;require(target.parentFile==root&&name.startsWith("Verify104-"));target.deleteRecursively()}
}
