package com.germanium.shortcuts.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Version104Test {
    private fun step(type:String,vararg p:Pair<String,String>)=ActionStep(type=type,parameters=p.toMap())
    private suspend fun run(vararg steps:ActionStep,backend:PlatformActions=PlatformActions.Unavailable,mode:ExecutionMode=ExecutionMode.STANDARD)=ShortcutEngine(builtInActions(backend),backend).run(Shortcut(name="Version104",steps=steps.toList()),mode)
    @Test fun urlDefaultsAndExplicitSchemes() {
        assertEquals("https://example.com",UrlValues.normalize("example.com"))
        assertEquals("http://example.com/path",UrlValues.normalize("example.com/path","http"))
        assertEquals("http://localhost:8080/a",UrlValues.normalize("localhost:8080/a","http"))
        assertEquals("http://example.com:8080",UrlValues.normalize("example.com:8080","http"))
        assertEquals("https://example.com",UrlValues.normalize("//example.com"))
        for (value in listOf("https://example.com","mailto:test@example.com","demo://screen/1","tel:123")) assertEquals(value,UrlValues.normalize(value))
        for (value in listOf("","https://","http://","abc\nexample.com")) assertThrows(Problem::class.java) { UrlValues.normalize(value) }
    }
    @Test fun openUrlUsesHttpAndReceivesTheUrlOutput()=runTest {
        val opened=mutableListOf<String>(); val backend=object:PlatformActions by PlatformActions.Unavailable { override suspend fun openUrl(url:String) { opened+=url } }
        assertEquals(RunStatus.SUCCEEDED,run(step("open_url","url" to "example.com"),backend=backend).status)
        val result=run(step("url","url" to "example.com"),step("open_url"),backend=backend)
        assertEquals(RunStatus.SUCCEEDED,result.status);assertEquals(listOf("http://example.com","https://example.com"),opened)
        assertFalse(ActionOutputs.produces("open_url"));assertTrue(ActionOutputs.produces("url"))
    }
    @Test fun tenThousandActionsRoundTripAndExecute()=runTest {
        val steps=List(10000){step("text","value" to "$it")};val shortcut=Shortcut(name="Ten thousand",steps=steps)
        val bytes=XmlCodec().encode(shortcut);assertTrue(bytes.size>1024*1024);assertEquals(shortcut,XmlCodec().decode(bytes))
        val result=ShortcutEngine().run(shortcut,ExecutionMode.STANDARD)
        assertEquals(RunStatus.SUCCEEDED,result.status);assertEquals(10000,result.executedCount);assertEquals("9999",result.output);assertTrue(result.historyTruncated)
        assertThrows(IllegalArgumentException::class.java){Shortcut(name="Too large",steps=steps+step("text","value" to "extra"))}
    }
    @Test fun upgradingOldXmlKeepsIdentityAndSteps()=runTest {
        val folder=Files.createTempDirectory("shortcuts104-").toFile()
        try {
            val original=Shortcut(name="Keep",steps=listOf(step("text","value" to "unchanged")))
            val file=File(folder,"${original.id}.xml");file.writeText(XmlCodec().encode(original).toString(Charsets.UTF_8).replace("appVersion=\"$SHORTCUTS_VERSION\"","appVersion=\"10003\""))
            assertEquals(original,XmlShortcutRepository(folder).list().single());assertTrue(file.readText().contains("appVersion=\"$SHORTCUTS_VERSION\""))
        } finally { folder.deleteRecursively() }
    }
    @Test fun jsonRetainsExactNumbersBooleansListsAndNull() {
        val text="{\"n\":12345678901234567890.125,\"yes\":true,\"list\":[1,\"x\",null]}"
        val parsed=JsonValues.parse(text) as Value.Dictionary
        assertEquals("12345678901234567890.125",parsed.entries.getValue("n").text());assertEquals(Value.BooleanValue(true),parsed.entries["yes"])
        assertEquals(Value.Null,(parsed.entries.getValue("list") as Value.ListValue).items.last())
        assertEquals(parsed,JsonValues.parse(HttpActions.json(parsed)));assertEquals(parsed,ValueDocument.decode(ValueDocument.encode(parsed)))
    }
    @Test fun invalidJsonIsRejected() {
        for(text in listOf("{\"x\":1,\"x\":2}","[1,]","{\"x\":}","01","true trailing","\"\\uD800\"","[".repeat(18)+"0"+"]".repeat(18))) assertThrows(Problem::class.java){JsonValues.parse(text)}
    }
    @Test fun jsonHandlersCanFeedDictionaryActions()=runTest {
        val result=run(step("text","value" to "{\"value\":42,\"missing\":null}"),step("get_dictionary_from_input"),step("get_dictionary_value","dictionary" to "{{input}}","key" to "value"))
        assertEquals("42",result.output);assertTrue(result.finalValue is Value.Number)
    }
    @Test fun dateDifferenceSupportsFractionsSignsAndUnits()=runTest {
        val base=arrayOf("start" to "2026-10-29T00:00:00Z","end" to "2026-10-29T01:30:00Z","unit" to "hours","zone" to "UTC")
        assertEquals("1.5",run(step("date_difference",*base)).output)
        assertEquals("-90",run(step("date_difference","start" to "2026-10-29T01:30:00Z","end" to "2026-10-29T00:00:00Z","unit" to "minutes")).output)
        assertEquals("90",run(step("date_difference","start" to "2026-10-29T01:30:00Z","end" to "2026-10-29T00:00:00Z","unit" to "minutes","absolute" to "true")).output)
    }
    @Test fun elapsedDaysAndCalendarDaysRespectDaylightSaving()=runTest {
        val adjusted=run(step("adjust_date","source" to "2026-03-07T12:00:00-05:00","amount" to "1","unit" to "days","zone" to "America/New_York"))
        assertEquals("2026-03-08T12:00-04:00",adjusted.output)
        val difference=run(step("date_difference","start" to "2026-03-07T12:00:00-05:00","end" to adjusted.output,"unit" to "hours","zone" to "America/New_York"))
        assertEquals("23",difference.output)
    }
    @Test fun monthAdjustmentUsesCalendarBoundaries()=runTest {
        assertEquals("2026-02-28T00:00Z",run(step("adjust_date","source" to "2026-01-31T00:00:00Z","amount" to "1","unit" to "months","zone" to "UTC")).output)
        assertEquals("12",run(step("date_difference","start" to "2025-10-29","end" to "2026-10-29","unit" to "months","zone" to "UTC")).output)
    }
    @Test fun dateDurationUsesTheNativeMathHook()=runTest {
        val old=Calculator.nativeEvaluate;var called=false
        try { Calculator.nativeEvaluate={expression,_-> called=true;assertTrue(expression.endsWith("/3600000"));"1.5"}
            assertEquals("1.5",run(step("date_difference","start" to "2026-10-29T00:00:00Z","end" to "2026-10-29T01:30:00Z","unit" to "hours")).output);assertTrue(called)
        } finally {Calculator.nativeEvaluate=old}
    }
    @Test fun formatFileSizeUsesBinaryOrDecimalUnits()=runTest {
        assertTrue(run(step("format_filesize","source" to "1024","digits" to "0")).output.endsWith(" KiB"))
        assertTrue(run(step("format_filesize","source" to "1000","digits" to "0","binary" to "false")).output.endsWith(" kB"))
    }
    @Test fun powerIsRejectedBeforeCallingTheBackendWithoutRootMode()=runTest {
        var invoked=false;val backend=object:PlatformActions by PlatformActions.Unavailable {override suspend fun power(reboot:Boolean,mode:ExecutionMode,target:String){invoked=true}}
        for(mode in listOf(ExecutionMode.STANDARD,ExecutionMode.SHIZUKU)) for(type in listOf("shutdown","reboot")) {
            val result=run(step(type),backend=backend,mode=mode);assertEquals("err_root_required",(result.failure as Problem).key)
        };assertFalse(invoked)
    }
    @Test fun rootPowerDispatchesWithoutAnyRealPowerCommand()=runTest {
        val calls=mutableListOf<Boolean>();val backend=object:PlatformActions by PlatformActions.Unavailable {override suspend fun power(reboot:Boolean,mode:ExecutionMode,target:String){assertEquals(ExecutionMode.ROOT,mode);calls+=reboot}}
        assertEquals(RunStatus.SUCCEEDED,run(step("shutdown"),backend=backend,mode=ExecutionMode.ROOT).status)
        assertEquals(RunStatus.SUCCEEDED,run(step("reboot"),backend=backend,mode=ExecutionMode.ROOT).status)
        assertEquals(listOf(false,true),calls);assertFalse(ActionOutputs.produces("shutdown"));assertFalse(ActionOutputs.produces("reboot"))
    }
    @Test fun rebootTargetsAreWhitelistedWithoutExecutingCommands()=runTest {
        val targets=mutableListOf<String>();val backend=object:PlatformActions by PlatformActions.Unavailable {override suspend fun power(reboot:Boolean,mode:ExecutionMode,target:String){targets+=target}}
        for(target in PowerTargets.all) assertEquals(RunStatus.SUCCEEDED,run(step("reboot","target" to target),backend=backend,mode=ExecutionMode.ROOT).status)
        assertEquals(PowerTargets.all,targets)
        assertEquals("reboot",PowerTargets.command(true,"system"));assertEquals("reboot -p",PowerTargets.command(false))
        for(target in PowerTargets.all.drop(1))assertEquals("reboot $target",PowerTargets.command(true,target))
        assertThrows(Problem::class.java){PowerTargets.command(true,"recovery; echo unsafe")}
    }
    @Test fun partitionParametersAreCheckedWithoutOpeningAnyDevice() {
        for(name in listOf("boot.img","boot.IMG"))PartitionWrites.imageName(name)
        for(name in listOf("boot.zip","boot.img.zip","boot.img ",".img","boot.bin"))assertThrows(Problem::class.java){PartitionWrites.imageName(name)}
        PartitionWrites.target("/dev/block/by-name/boot_a")
        for(path in listOf("/dev/block/../other","/dev/block//boot","/dev/block/boot;id","/data/boot","/dev/block/"))assertThrows(Problem::class.java){PartitionWrites.target(path)}
        assertFalse(ActionOutputs.produces("write_partition"))
    }
    @Test fun zipRoundTripPreservesBytesAndNestedNames()=runTest {
        val entries=listOf(ArchiveEntry("a.txt","hello".toByteArray()),ArchiveEntry("nested/b.bin",byteArrayOf(0,1,2,-1)))
        val unpacked=ZipValues.unpack(ZipValues.pack(entries));assertEquals(entries.map{it.name},unpacked.map{it.name});entries.zip(unpacked).forEach{(a,b)->assertArrayEquals(a.bytes,b.bytes)}
        val root=Files.createTempDirectory("archive104-").toFile()
        try {val files=WorkspaceFiles(root).importArchive("Extracted",unpacked) as Value.ListValue;assertEquals(2,files.items.size);assertEquals("hello",File(root,files.items.first().text()).readText())}finally{root.deleteRecursively()}
    }
    @Test fun zipTraversalAndDuplicateNamesAreRejected() {
        for(name in listOf("../escape.txt","/absolute.txt","a/../../b","a\\..\\b","C:/disk","a//b")) {
            val data=ByteArrayOutputStream();ZipOutputStream(data).use{it.putNextEntry(ZipEntry(name));it.write(1);it.closeEntry()}
            assertThrows(Problem::class.java){ZipValues.unpack(data.toByteArray())}
        }
        assertThrows(Problem::class.java){ZipValues.pack(listOf(ArchiveEntry("a",byteArrayOf(1)),ArchiveEntry("a",byteArrayOf(2))))}
    }
}
