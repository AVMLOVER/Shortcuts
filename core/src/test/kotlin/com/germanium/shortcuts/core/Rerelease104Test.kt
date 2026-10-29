package com.germanium.shortcuts.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class Rerelease104Test {
    private fun step(type:String,vararg p:Pair<String,String>)=ActionStep(type=type,parameters=p.toMap())
    private open class Base:PlatformActions {
        override suspend fun shell(command:String,mode:ExecutionMode,timeoutMillis:Long)=ShellResult(0,"","")
        override suspend fun launchIntent(spec:IntentSpec)=Unit
    }
    private class Rich:Base(){
        var shown:Value?=null;var notified:Value?=null;var accept=true
        override suspend fun showResult(title:String,body:Value,allowCancel:Boolean):Boolean {shown=body;return accept}
        override suspend fun postNotification(title:String,body:Value){notified=body}
    }
    @Test fun outputsDialogsAndNotificationsKeepAFileListTyped()=runTest {
        val input=Value.ListValue(listOf(Value.Text("Images/photo.png"),Value.Text("content://documents/report.pdf")))
        val output=step("output","value" to "{{input}}")
        val backend=Rich()
        val source=Shortcut(name="Rich result",steps=listOf(output,step("show_result","body" to "{{@${output.id}}}"),step("notification","body" to "{{@${output.id}}}")))
        val result=ShortcutEngine(builtInActions(backend),backend).run(source,ExecutionMode.STANDARD,typedInput=input)
        assertEquals(RunStatus.SUCCEEDED,result.status);assertEquals(input,result.outputValue)
        assertEquals(input,backend.shown);assertEquals(input,backend.notified)
    }
    @Test fun sharedImageAndDocumentPayloadKeepsItsStructureThroughNamedVariables()=runTest {
        val input=Value.Dictionary(mapOf("_shared" to Value.BooleanValue(true),"images" to Value.ListValue(listOf(Value.Text("photo.png"))),"files" to Value.ListValue(listOf(Value.Text("report.pdf")))))
        val backend=Rich()
        val source=Shortcut(name="Named rich result",steps=listOf(step("set_variable","name" to "attachment","value" to "{{input}}"),step("show_result","body" to "{{attachment}}"),step("notification","body" to "{{attachment}}")))
        val result=ShortcutEngine(builtInActions(backend),backend).run(source,ExecutionMode.STANDARD,typedInput=input)
        assertEquals(RunStatus.SUCCEEDED,result.status);assertEquals(input,backend.shown);assertEquals(input,backend.notified)
    }
    @Test fun sharedDisplayDeduplicatesFilesAndPreservesOrdinaryDictionaries() {
        val image=Value.Text("photo.png");val pdf=Value.Text("report.pdf")
        val shared=Value.Dictionary(mapOf("_shared" to Value.BooleanValue(true),"text" to Value.Text("caption"),"images" to Value.ListValue(listOf(image)),"files" to Value.ListValue(listOf(image,pdf))))
        assertEquals(listOf(Value.Text("caption"),image,pdf),DisplayContent.items(shared))
        val ordinary=Value.Dictionary(mapOf("text" to image));assertEquals(listOf(ordinary),DisplayContent.items(ordinary))
    }
    @Test fun cancellingADocumentResultStopsFollowingEffects()=runTest {
        val backend=Rich().apply{accept=false}
        val source=Shortcut(name="Cancel",steps=listOf(step("show_result","allowCancel" to "true"),step("notification")))
        try{ShortcutEngine(builtInActions(backend),backend).run(source,ExecutionMode.STANDARD,typedInput=Value.Text("report.pdf"));fail("Expected cancellation")}catch(_:CancellationException){}
        assertEquals(Value.Text("report.pdf"),backend.shown);assertNull(backend.notified)
    }
    @Test fun oldStringBackendsRemainCompatibleAndInterpolatedCaptionsRemainText()=runTest {
        var shown="";var notified=""
        val backend=object:Base(){override suspend fun showResult(title:String,body:String,allowCancel:Boolean):Boolean{shown=body;return true};override suspend fun postNotification(title:String,body:String){notified=body}}
        val input=Value.ListValue(listOf(Value.Text("a.png"),Value.Text("b.pdf")))
        val source=Shortcut(name="Compatibility",steps=listOf(step("show_result"),step("notification","body" to "Files: {{input}}")))
        val result=ShortcutEngine(builtInActions(backend),backend).run(source,ExecutionMode.STANDARD,typedInput=input)
        assertEquals(RunStatus.SUCCEEDED,result.status);assertEquals("a.png\nb.pdf",shown);assertEquals("Files: a.png\nb.pdf",notified)
    }
    @Test fun finalTypedOutputTakesPriorityOverTheRetainedHistory() {
        val value=Value.ListValue(listOf(Value.Text("final.pdf")))
        val result=RunResult(RunStatus.SUCCEEDED,listOf(StepResult("old","text",Value.Text("old"))),finalValue=value,historyTruncated=true)
        assertEquals(value,result.outputValue);assertEquals("final.pdf",result.output)
        assertEquals(Value.Text(""),RunResult(RunStatus.SUCCEEDED,emptyList()).outputValue)
    }
}
