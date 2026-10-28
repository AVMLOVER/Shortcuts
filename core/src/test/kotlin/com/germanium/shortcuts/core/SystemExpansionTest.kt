package com.germanium.shortcuts.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.*

class SystemExpansionTest {
    private fun step(type: String, vararg pairs: Pair<String, String>) = ActionStep(type = type, parameters = pairs.toMap())
    private open class Backend : PlatformActions {
        var day: CalendarDaySpec? = null
        var destination: Pair<String, String>? = null
        override suspend fun shell(command: String, mode: ExecutionMode, timeoutMillis: Long) = ShellResult(0, "", "")
        override suspend fun launchIntent(spec: IntentSpec) = Unit
        override suspend fun readCalendarDay(day: CalendarDaySpec): Value { this.day = day; return Value.ListValue(listOf(Value.Dictionary(mapOf("title" to Value.Text("Meeting"))))) }
        override suspend fun screenshot(folder: String, filename: String): Value { destination = folder to filename; return Value.Text("$folder/$filename") }
    }
    @Test fun numberConvertsInputAndPreservesNumericType() = runTest {
        val source = listOf(step("text", "value" to "12.50"), step("number", "value" to "{{input}}"), step("calculate", "left" to "{{input}}", "operation" to "*", "right" to "2"))
        val result = ShortcutEngine().run(Shortcut(name = "Number", steps = source), ExecutionMode.STANDARD)
        assertEquals(RunStatus.SUCCEEDED, result.status); assertEquals("25", result.output); assertTrue(result.steps[1].value is Value.Number)
    }
    @Test fun numberResolvesSelectedActionOutputAndRejectsInvalidText() = runTest {
        val first = step("number", "value" to "-3.25")
        val result = ShortcutEngine().run(Shortcut(name = "Ref", steps = listOf(first, step("number", "value" to "{{@${first.id}}}"))), ExecutionMode.STANDARD)
        assertEquals("-3.25", result.output)
        val invalid = ShortcutEngine().run(Shortcut(name = "Invalid", steps = listOf(step("number", "value" to "hello"))), ExecutionMode.STANDARD)
        assertEquals(RunStatus.FAILED, invalid.status)
    }
    @Test fun calendarDayHonorsDstRatherThanAddingTwentyFourHours() {
        val spring = CalendarDaySpec(LocalDate.parse("2026-03-29"), ZoneId.of("Europe/Berlin"))
        val autumn = CalendarDaySpec(LocalDate.parse("2026-10-25"), ZoneId.of("Europe/Berlin"))
        assertEquals(23 * 3600000L, spring.endMillis - spring.startMillis)
        assertEquals(25 * 3600000L, autumn.endMillis - autumn.startMillis)
    }
    @Test fun calendarReadsSelectedDateAndReturnsStructuredList() = runTest {
        val backend = Backend()
        val engine = ShortcutEngine(builtInActions(backend), backend)
        val result = engine.run(Shortcut(name = "Calendar", steps = listOf(step("read_calendar", "date" to "2026-10-28", "zone" to "Asia/Tokyo"))), ExecutionMode.STANDARD)
        assertEquals(RunStatus.SUCCEEDED, result.status); assertEquals(LocalDate.parse("2026-10-28"), backend.day!!.date)
        assertEquals("Asia/Tokyo", backend.day!!.zone.id); assertTrue(result.steps.single().value is Value.ListValue)
    }
    @Test fun calendarResolvesTimestampAndTimezoneVariables() = runTest {
        val backend = Backend(); val engine = ShortcutEngine(builtInActions(backend), backend)
        val date = step("text", "value" to "2026-10-28T23:30:00Z")
        val result = engine.run(Shortcut(name = "Day", steps = listOf(date, step("read_calendar", "date" to "{{input}}", "zone" to "Asia/Tokyo"))), ExecutionMode.STANDARD)
        assertEquals(RunStatus.SUCCEEDED, result.status); assertEquals(LocalDate.parse("2026-10-29"), backend.day!!.date)
    }
    @Test fun dateOnlyCanBeFormattedAndInvalidZoneFailsCleanly() = runTest {
        assertEquals(Instant.parse("2026-10-28T00:00:00Z").toEpochMilli(), parseEventTime("2026-10-28", ZoneOffset.UTC))
        val engine = ShortcutEngine()
        val result = engine.run(Shortcut(name = "Date", steps = listOf(step("format_date", "source" to "2026-10-28", "format" to "yyyy-MM-dd", "zone" to "UTC"))), ExecutionMode.STANDARD)
        assertEquals("2026-10-28", result.output)
        val invalid = engine.run(Shortcut(name = "Bad zone", steps = listOf(step("read_calendar", "date" to "2026-10-28", "zone" to "Nowhere"))), ExecutionMode.STANDARD)
        assertEquals(RunStatus.FAILED, invalid.status)
    }
    @Test fun capturePassesResolvedDestinationAndReturnsPath() = runTest {
        val backend = Backend(); val engine = ShortcutEngine(builtInActions(backend), backend)
        val result = engine.run(Shortcut(name = "Capture", steps = listOf(step("text", "value" to "My shots"), step("screenshot", "folder" to "{{input}}", "filename" to "one.png"))), ExecutionMode.STANDARD)
        assertEquals("My shots" to "one.png", backend.destination); assertEquals("My shots/one.png", result.output)
    }
    @Test fun customRgbColorsRoundTripAndRejectMalformedValues() {
        val codec = XmlCodec(); val source = Shortcut(name = "Custom", color = "#aB09fE")
        assertEquals(source, codec.decode(codec.encode(source)))
        listOf("#123", "#12345678", "#notrgb", "#12345G").forEach { bad -> assertThrows(Problem::class.java) { source.copy(color = bad) } }
    }
    @Test fun bundlePreservesNestedActionsCustomColorsAndUnknownParameters() {
        val first = Shortcut(name = "One", color = "#F0A020", steps = listOf(ActionStep(type = "if", branches = listOf(ActionBranch("then", steps = listOf(step("future_action", "extra" to "a\nb<&")))))))
        val items = listOf(first, Shortcut(name = "Two")); val codec = XmlCodec()
        assertEquals(items, codec.decodeImport(codec.encodeBundle(items)))
        assertEquals(listOf(first), codec.decodeImport(codec.encode(first)))
        assertTrue(codec.decodeImport(codec.encodeBundle(emptyList())).isEmpty())
    }
    @Test fun bundleRejectsDuplicatesDoctypeUnsupportedVersionAndOversize() {
        val codec = XmlCodec(); val item = Shortcut(name = "One")
        assertThrows(Problem::class.java) { codec.encodeBundle(listOf(item, item)) }
        val duplicate = codec.encode(item).toString(Charsets.UTF_8).substringAfter("?>")
        assertThrows(InvalidDocument::class.java) { codec.decodeImport("<shortcuts version=\"1\">$duplicate$duplicate</shortcuts>".toByteArray()) }
        assertThrows(InvalidDocument::class.java) { codec.decodeImport("<!DOCTYPE shortcuts [<!ENTITY x SYSTEM 'file:///secret'>]><shortcuts version=\"1\"/>".toByteArray()) }
        assertThrows(InvalidDocument::class.java) { codec.decodeImport("<shortcuts version=\"2\"/>".toByteArray()) }
        assertThrows(InvalidDocument::class.java) { codec.decodeImport(ByteArray(XmlCodec.MAX_BUNDLE_BYTES + 1)) }
    }
    @Test fun importAllNeverOverwritesExistingAndValidatesBeforeWriting() = runTest {
        val directory = Files.createTempDirectory("bundle-test").toFile()
        try {
            val repository = XmlShortcutRepository(directory)
            val existing = Shortcut(name = "Existing"); repository.save(existing)
            val fresh = Shortcut(name = "Fresh")
            assertThrows(Problem::class.java) { kotlinx.coroutines.runBlocking { repository.importNew(listOf(fresh, existing)) } }
            assertEquals(listOf(existing), repository.list())
            val tooBig = Shortcut(name = "Too big", steps = listOf(step("text", "value" to "a".repeat(XmlCodec.MAX_BYTES))))
            assertThrows(Problem::class.java) { kotlinx.coroutines.runBlocking { repository.importNew(listOf(fresh, tooBig)) } }
            assertEquals(listOf(existing), repository.list())
            repository.importNew(listOf(fresh)); assertEquals(setOf(existing, fresh), repository.list().toSet())
        } finally { directory.deleteRecursively() }
    }
    @Test fun screenshotFilesUseWorkspaceBoundaryAndBinaryBytes() = runTest {
        val directory = Files.createTempDirectory("capture-files").toFile()
        try {
            val files = WorkspaceFiles(directory); val bytes = byteArrayOf(-119, 80, 78, 71)
            assertEquals("Shots/a.png", files.writeBinary("Shots", "a.png", bytes)); assertArrayEquals(bytes, files.imageFile("Shots/a.png")!!.readBytes())
            assertNull(files.imageFile("../secret.png"))
            assertThrows(Problem::class.java) { kotlinx.coroutines.runBlocking { files.writeBinary("../outside", "a.png", bytes) } }
            assertThrows(Problem::class.java) { kotlinx.coroutines.runBlocking { files.writeBinary("Shots", "a.txt", bytes) } }
        } finally { directory.deleteRecursively() }
    }
}
