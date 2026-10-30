package com.germanium.shortcuts

import android.content.Intent
import android.graphics.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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

class Version105PreviewTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as ShortcutsApplication
    private fun capture(name:String) {compose.waitForIdle();android.os.SystemClock.sleep(600);val screen=instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable");try{File(app.getExternalFilesDir(null),"qa105/$name").also{it.parentFile!!.mkdirs()}.outputStream().use{screen.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{screen.recycle()}}
    private fun cleanup(folder:String) {val root=File(app.getExternalFilesDir(null),"workspace").canonicalFile;val target=File(root,folder).canonicalFile;require(target.parentFile==root&&folder.startsWith("Verify105-"));target.deleteRecursively()}
    @Test fun storedRichTextRendersInQuickLookAndShowResult():Unit=runBlocking {
        val folder="Verify105-${UUID.randomUUID()}"
        val rich=RichTexts.fromHtml("<h1>Shortcuts 1.0.5</h1><p><b>Bold text</b> and <i>italic text</i></p>")
        val file=app.workspace.perform("write_file",folder,"rich.xml",ValueDocument.encode(rich))
        try{ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            scenario.onActivity{it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            val quick=async(Dispatchers.Default){app.actions.quickLook(file)}
            try{compose.waitUntil(15000){compose.onAllNodesWithTag("rich_text_preview_0").fetchSemanticsNodes().isNotEmpty()};capture("qa105-rich-quicklook.png");compose.onNodeWithTag("close_quick_look").performClick();withTimeout(10000){quick.await()}}
            finally{quick.cancelAndJoin()}
            val result=async(Dispatchers.Default){app.actions.showResult("RichText file",file,false)}
            try{compose.waitUntil(15000){compose.onAllNodesWithTag("confirm_result").fetchSemanticsNodes().isNotEmpty()&&compose.onAllNodesWithTag("rich_text_preview_0").fetchSemanticsNodes().isNotEmpty()};capture("qa105-rich-result.png");compose.onNodeWithTag("confirm_result").performClick();assertTrue(withTimeout(10000){result.await()})}
            finally{result.cancelAndJoin()}
        }}finally{cleanup(folder)}
    }
    private fun imageBounds():Rect? {
        fun find(node:AccessibilityNodeInfo?,depth:Int=0):Rect? {
            if(node==null||depth>30)return null
            if(node.className=="android.widget.ImageView"&&node.contentDescription?.toString()==app.tr("image_preview"))return Rect().also{node.getBoundsInScreen(it)}
            for(index in 0 until node.childCount){find(node.getChild(index),depth+1)?.let{return it}}
            return null
        }
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }
    @Test fun gifAnimatesInQuickLookAndAlsoAppearsInShowResult():Unit=runBlocking {
        val folder="Verify105-${UUID.randomUUID()}"
        try {
            val refs=mutableListOf<Value>()
            for(color in listOf(Color.RED,Color.BLUE)) {
                val bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
                try{bitmap.eraseColor(color);val bytes=ByteArrayOutputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it);it.toByteArray()};refs+=Value.Text(app.workspace.writeBinary(folder,"${refs.size}.png",bytes))}finally{bitmap.recycle()}
            }
            val gif=AndroidMedia105(app).perform("make_gif",Value.ListValue(refs),Value.Null,mapOf("width" to "128","height" to "128","frameDelay" to "300","folder" to folder))
            ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                scenario.onActivity{it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
                val quick=async(Dispatchers.Default){app.actions.quickLook(gif)}
                try {
                    compose.waitUntil(15000){imageBounds()!=null}
                    val bounds=imageBounds()!!;val colors=mutableSetOf<String>()
                    repeat(8){android.os.SystemClock.sleep(130);val screen=instrumentation.uiAutomation.takeScreenshot()!!;try{val color=screen.getPixel(bounds.centerX(),bounds.centerY());if(Color.red(color)>200&&Color.blue(color)<100)colors+="red";if(Color.blue(color)>200&&Color.red(color)<100)colors+="blue"}finally{screen.recycle()}}
                    assertEquals(setOf("red","blue"),colors);capture("qa105-gif-quicklook.png")
                    compose.onNodeWithTag("close_quick_look").performClick();withTimeout(10000){quick.await()}
                }finally{quick.cancelAndJoin()}
                val result=async(Dispatchers.Default){app.actions.showResult("Animated GIF",gif,false)}
                try{compose.waitUntil(15000){compose.onAllNodesWithTag("confirm_result").fetchSemanticsNodes().isNotEmpty()&&imageBounds()!=null};capture("qa105-gif-result.png");compose.onNodeWithTag("confirm_result").performClick();assertTrue(withTimeout(10000){result.await()})}
                finally{result.cancelAndJoin()}
            }
        }finally{cleanup(folder)}
    }
}
