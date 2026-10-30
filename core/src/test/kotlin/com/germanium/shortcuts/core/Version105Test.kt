package com.germanium.shortcuts.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class Version105Test {
    @Test fun richTextPreservesFormattingAndPlainTextThroughFilesAndVariables() {
        val rich=RichTexts.fromHtml("<p>Hello <strong>世界</strong></p><p>Next</p>")
        assertTrue(rich.html.contains("<strong>"));assertEquals("Hello 世界\nNext",rich.plain.replace(Regex("\n+"),"\n"))
        assertEquals(rich,ValueDocument.decode(ValueDocument.encode(rich)))
        val scope=VariableScope();scope.set("rich",rich);assertEquals(rich,RunContext(ExecutionMode.STANDARD,Value.Null,scope).resolve("{{rich}}"))
        assertEquals("Hello 世界\nNext",scope.get("rich").text().replace(Regex("\n+"),"\n"))
    }
    @Test fun markdownAndHtmlConversionsEscapeTextAndRemoveActiveContent() {
        val rich=RichTexts.fromMarkdown("# Title\n\n**Bold** and [site](https://example.com)")
        assertTrue(rich.html.contains("<h1>"));assertTrue(RichTexts.markdown(rich).contains("**Bold**"))
        val safe=RichTexts.fromHtml("<script>alert(1)</script><p onclick='bad()'>Hello</p><img src='https://invalid/x'>")
        assertFalse(safe.html.contains("script"));assertFalse(safe.html.contains("onclick"));assertFalse(safe.html.contains("img"))
        assertTrue(RichTexts.html(Value.Text("<script>unsafe</script>")).contains("&lt;script&gt;"))
    }
    @Test fun richTextFormattingCountsTowardsContainerAndVariableBudgets() {
        val rich=Value.RichText("x".repeat(130000),"")
        assertThrows(Problem::class.java){Value.ListValue(listOf(rich,rich,rich))}
        assertThrows(Problem::class.java){Value.Dictionary(mapOf("a" to rich,"b" to rich,"c" to rich))}
    }
    @Test fun pdfPageRangesAreOneBasedDeduplicatedAndBounded() {
        assertEquals(listOf(0,2,3,4),MediaParameters.pages("1,3-5,3",8))
        assertEquals(listOf(0,1),MediaParameters.pages("all",2))
        for(bad in listOf("0","2-1","1-9","abc","1,"))assertThrows(Problem::class.java){MediaParameters.pages(bad,8)}
        assertThrows(Problem::class.java){MediaParameters.pages("all",101)}
    }
    @Test fun imageFilteringComparesNumbersNumericallyAndTextWithoutCase() {
        assertTrue(MediaParameters.matches(Value.Number(100.toBigDecimal()),"greater","20"))
        assertFalse(MediaParameters.matches(Value.Number(100.toBigDecimal()),"less","20"))
        assertTrue(MediaParameters.matches(Value.Text("IMG_001.PNG"),"ends_with",".png"))
        assertTrue(MediaParameters.matches(Value.Text("portrait"),"equals","PORTRAIT"))
        assertThrows(Problem::class.java){MediaParameters.matches(Value.Number(10.toBigDecimal()),"greater","abc")}
    }
    @Test fun renamingPreservesBinaryDataAndRefusesCollisionsAndTraversal()=runTest {
        val root=Files.createTempDirectory("rename105-").toFile()
        try {
            val files=WorkspaceFiles(root);val bytes=byteArrayOf(0,1,2,3,-1)
            val source=files.importBinary("A","old.bin",bytes)
            val renamed=files.rename(source,"new.bin").text()
            assertEquals("A/new.bin",renamed);assertArrayEquals(bytes,files.readableFile(renamed).readBytes());assertFalse(java.io.File(root,source).exists())
            files.importBinary("A","occupied.bin",byteArrayOf(9))
            try{files.rename(renamed,"occupied.bin");fail("collision")}catch(_:Problem){}
            assertArrayEquals(bytes,files.readableFile(renamed).readBytes())
            for(name in listOf("../escape","a/b","..","C:bad","x\u0000"))assertThrows(Problem::class.java){FileNames.validate(name)}
        }finally{root.deleteRecursively()}
    }
    @Test fun old104WorkflowsUpgradeWithoutChangingIdentityOrActions()=runTest {
        val root=Files.createTempDirectory("migrate105-").toFile()
        try {
            val source=Shortcut(name="Keep",steps=listOf(ActionStep(type="text",parameters=mapOf("value" to "keep"))))
            val file=java.io.File(root,"${source.id}.xml")
            file.writeText(XmlCodec().encode(source).toString(Charsets.UTF_8).replace("appVersion=\"$SHORTCUTS_VERSION\"","appVersion=\"10004\""))
            assertEquals(source,XmlShortcutRepository(root).list().single());assertTrue(file.readText().contains("appVersion=\"10005\""))
        }finally{root.deleteRecursively()}
    }
    @Test fun mediaActionsPassTypedFramesAndRenderedOptionsToTheBackend()=runTest {
        var frames:Value?=null
        val platform=object:PlatformActions {
            override suspend fun shell(command:String,mode:ExecutionMode,timeoutMillis:Long)=error("Unused")
            override suspend fun launchIntent(spec:IntentSpec)=error("Unused")
            override suspend fun media105(operation:String,source:Value,secondary:Value,options:Map<String,String>):Value {frames=secondary;assertEquals("original.gif",source.text());return Value.Text("result.gif")}
        }
        val input=Value.ListValue(listOf(Value.Text("one.png"),Value.Text("two.png")))
        val action=ActionStep(type="add_frames_to_gif",parameters=mapOf("source" to "original.gif","frames" to "{{input}}"))
        val result=ShortcutEngine(builtInActions(platform),platform).run(Shortcut(name="GIF",steps=listOf(action)),ExecutionMode.STANDARD,typedInput=input)
        assertEquals(RunStatus.SUCCEEDED,result.status);assertEquals(input,frames);assertEquals("result.gif",result.output)
    }
}
