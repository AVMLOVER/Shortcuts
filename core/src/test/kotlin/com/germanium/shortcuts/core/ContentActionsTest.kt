package com.germanium.shortcuts.core

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContentActionsTest {
    private fun step(type: String, vararg parameters: Pair<String, String>) = ActionStep(type = type, parameters = mapOf(*parameters))
    private suspend fun run(vararg steps: ActionStep, platform: PlatformActions = PlatformActions.Unavailable, input: Value? = null) = ShortcutEngine(builtInActions(platform), platform).run(Shortcut(name = "Test", steps = steps.toList()), ExecutionMode.STANDARD, typedInput = input)
    private fun numbers(vararg items: String) = Value.ListValue(items.map { Value.Number(BigDecimal(it)) })
    private fun payload(text: String) = Value.Dictionary(mapOf("_shared" to Value.BooleanValue(true), "text" to Value.Text(text), "images" to Value.ListValue(emptyList()), "files" to Value.ListValue(emptyList()), "urls" to Value.ListValue(ContentInput.urls(text))))
    private open class Backend : PlatformActions {
        override suspend fun shell(command: String, mode: ExecutionMode, timeoutMillis: Long): ShellResult = error("Unused")
        override suspend fun launchIntent(spec: IntentSpec) = Unit
    }
    @Test fun shareInputRetainsOriginalTypedPayloadAcrossActions() = runTest {
        val input = payload("original")
        val result = run(step("text", "value" to "changed"), step("share_input"), input = input)
        assertEquals(RunStatus.SUCCEEDED, result.status); assertEquals(input, result.steps.last().value)
        assertEquals("original", run(step("share_input", "contentType" to "text"), input = input).output)
    }
    @Test fun shareEligibilityRespectsEnabledAncestorsAndContentTypes() {
        val action = step("share_input", "contentType" to "images")
        assertFalse(ContentInput.accepts(Shortcut(name = "A", steps = listOf(action)), payload("text")))
        val disabled = ActionStep(type = "if", enabled = false, branches = listOf(ActionBranch("then", steps = listOf(action))))
        assertTrue(ContentInput.shareActions(Shortcut(name = "A", steps = listOf(disabled))).isEmpty())
        assertFalse(ContentInput.accepts(Shortcut(name = "A", steps = listOf(action.copy(enabled = false))), payload("text")))
        assertTrue(ContentInput.accepts(Shortcut(name = "A", steps = listOf(step("share_input"))), payload("text")))
    }
    @Test fun commentsPassThroughWithoutProducingMagicVariables() = runTest {
        val comment = step("comment", "note" to "{{missing}}", "resultVariable" to "ignored")
        val result = run(step("text", "value" to "hello"), comment)
        assertEquals("hello", result.output); assertFalse(ActionOutputs.produces("comment")); assertFalse(result.variables.containsKey("ignored"))
        assertEquals(RunStatus.FAILED, run(step("text", "value" to "x"), comment, step("text", "value" to "{{@${comment.id}}}")).status)
    }
    @Test fun countsUseUnicodeCharactersAndContainerItems() = runTest {
        assertEquals("2", run(step("count", "countType" to "characters"), input = Value.Text("A😀")).output)
        assertEquals("3", run(step("count"), input = numbers("1", "2", "3")).output)
        assertEquals("3", run(step("count", "countType" to "lines"), input = Value.Text("a\r\nb\nc")).output)
        assertEquals("2", run(step("count", "countType" to "words"), input = Value.Text("Hello, world!")).output)
        assertEquals("0", run(step("count", "countType" to "lines"), input = Value.Text("")).output)
    }
    @Test fun combineTextSupportsPresetsCustomAndLegacyXml() = runTest {
        val input = Value.ListValue(listOf(Value.Text("a"), Value.Text("b")))
        assertEquals("a\nb", run(step("join_text", "separatorType" to "newline"), input = input).output)
        assertEquals("a b", run(step("join_text", "separatorType" to "space"), input = input).output)
        assertEquals("ab", run(step("join_text", "separatorType" to "none"), input = input).output)
        assertEquals("a|b", run(step("join_text", "separator" to "|"), input = input).output)
        assertEquals("a,b", run(step("join_text"), input = input).output)
        assertEquals("only", run(step("join_text", "separatorType" to "newline"), input = Value.Text("only")).output)
    }
    @Test fun hashesMatchPublishedKnownVectors() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", ContentHash.digest("abc".toByteArray(), "MD5"))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ContentHash.digest("abc".toByteArray(), "SHA-256"))
        assertEquals("a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a", ContentHash.digest(byteArrayOf(), "SHA3-256"))
        assertEquals("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0", ContentHash.digest("abc".toByteArray(), "SM3"))
        assertEquals("cbf43926", ContentHash.digest("123456789".toByteArray(), "CRC32"))
        ContentHash.algorithms.forEach { assertTrue(ContentHash.digest(byteArrayOf(), it).matches(Regex("[0-9a-f]+"))) }
        assertThrows(Problem::class.java) { ContentHash.digest(byteArrayOf(), "unknown") }
    }
    @Test fun hashFileUsesBinaryBytesInsteadOfItsPath() = runTest {
        val backend = object : Backend() { override suspend fun readBinary(source: String): ByteArray { assertEquals("Images/a.png", source); return byteArrayOf(0, 1, -1) } }
        val result = run(step("hash_text", "source" to "Images/a.png", "inputType" to "file"), platform = backend)
        assertEquals(ContentHash.digest(byteArrayOf(0, 1, -1), "SHA-256"), result.output)
    }
    @Test fun statisticsKeepDecimalPrecisionAndHandleTiedModes() {
        val input = numbers("2", "4", "4.0", "4", "5", "5", "7", "9")
        assertEquals("5", ContentNumbers.stats(input, "average").text()); assertEquals("4", ContentNumbers.stats(input, "variance").text())
        assertEquals("2", ContentNumbers.stats(input, "stddev").text()); assertEquals("4.5", ContentNumbers.stats(input, "median").text())
        assertEquals("4", ContentNumbers.stats(input, "mode").text()); assertEquals("40", ContentNumbers.stats(input, "sum").text())
        assertEquals("0.3", ContentNumbers.stats(numbers("0.1", "0.2"), "sum").text())
        assertEquals(numbers("1", "2"), ContentNumbers.stats(numbers("1", "1.0", "2", "2.00"), "mode"))
    }
    @Test fun statisticsRejectEmptyAndNonnumericInputs() {
        assertThrows(Problem::class.java) { ContentNumbers.stats(Value.ListValue(emptyList()), "average") }
        assertThrows(Problem::class.java) { ContentNumbers.stats(Value.Text("hello"), "sum") }
        assertEquals("6", ContentNumbers.stats(Value.Text("1, 2\n3"), "sum").text())
    }
    @Test fun numberFormattingUsesExplicitLocaleAndDecimalPlaces() {
        assertEquals("1,234.50", ContentNumbers.format(BigDecimal("1234.5"), 2, true, "en-US"))
        assertEquals("1.234,50", ContentNumbers.format(BigDecimal("1234.5"), 2, true, "de-DE"))
        assertEquals("1234.50", ContentNumbers.format(BigDecimal("1234.5"), 2, false, "ja-JP"))
        assertEquals("-1.24", ContentNumbers.format(BigDecimal("-1.235"), 2, false, "en-US"))
        assertThrows(Problem::class.java) { ContentNumbers.format(BigDecimal.ONE, 21, false, "en-US") }
    }
    @Test fun numberExtractionHandlesSignsFractionsExponentsAndFullWidthDigits() {
        assertEquals(listOf("-12", "0.5", "1200", "1234.56", "42"), ContentInput.numbers("a=-12; b=.5; c=1.2e3; d=1,234.56; ４２").items.map { it.text() })
        assertTrue(ContentInput.numbers("no numbers").items.isEmpty())
        assertThrows(Problem::class.java) { ContentInput.numbers("1 ".repeat(257)) }
        assertThrows(Problem::class.java) { ContentInput.numbers("1e999999") }
    }
    @Test fun extractionAndStatisticsChainWithTypedNumbers() = runTest {
        val result = run(step("get_numbers_from_input"), step("statistics", "statistic" to "average"), step("format_number", "digits" to "2", "locale" to "en-US"), input = Value.Text("Price 1.2 and 2.4"))
        assertEquals("1.80", result.output)
    }
    @Test fun sharingAndWaitingRemainOrderedEffectsWithNoOutputVariables() = runTest {
        val events = mutableListOf<String>()
        val backend = object : Backend() {
            override suspend fun share(value: Value) { events += "share:${value.text()}" }
            override suspend fun waitForReturn() { events += "return" }
            override suspend fun batteryLevel() = 72
        }
        assertEquals("72", run(step("battery_level"), step("share"), step("wait_return"), platform = backend).output)
        assertEquals(listOf("share:72", "return"), events)
        assertFalse(ActionOutputs.produces("share")); assertFalse(ActionOutputs.produces("wait_return"))
    }
    @Test fun imageTransformsReceiveListsAndRenderedOptions() = runTest {
        val images = Value.ListValue(listOf(Value.Text("Images/a.png"), Value.Text("Images/b.png")))
        val backend = object : Backend() { override suspend fun transformImage(operation: String, source: Value, options: Map<String, String>): Value {
            assertEquals("combine_images", operation); assertEquals(images, source); assertEquals("8", options["spacing"]); return Value.Text("Images/out.png")
        } }
        assertEquals("Images/out.png", run(step("combine_images", "spacing" to "8"), platform = backend, input = images).output)
    }
    @Test fun imageCompositionPreservesAspectAndCentersDifferentSizes() {
        val plan = ImageComposition.plan(listOf(ImageSize(100, 50), ImageSize(50, 100)), "horizontal", 10, 2, true)
        assertEquals(135, plan.width); assertEquals(50, plan.height); assertEquals(ImagePlacement(110, 0, 25, 50), plan.placements[1])
        val vertical = ImageComposition.plan(listOf(ImageSize(100, 50), ImageSize(50, 100)), "vertical", 5, 2, false)
        assertEquals(ImagePlacement(25, 55, 50, 100), vertical.placements[1])
        val grid = ImageComposition.plan(List(3) { ImageSize(10, 20) }, "grid", 2, 2, true)
        assertEquals(22, grid.width); assertEquals(42, grid.height); assertEquals(3, grid.placements.size)
        assertThrows(Problem::class.java) { ImageComposition.plan(List(16) { ImageSize(16000, 16000) }, "grid", 1024, 16, true) }
    }
    @Test fun returnGateHandlesLateAndEarlyObservers() = runTest {
        val gate = ExternalReturnGate(); gate.arm()
        val waiting = async { gate.await() }; runCurrent(); assertFalse(waiting.isCompleted)
        gate.leave(); runCurrent(); assertFalse(waiting.isCompleted); gate.resume(); assertTrue(waiting.await())
        gate.arm(); gate.leave(); gate.resume(); assertTrue(gate.await()); assertFalse(gate.await())
    }
    @Test fun extractedUrlsKeepBalancedDelimitersAndRecognizeCustomSchemes() {
        assertEquals(listOf("https://example.org/a(b)", "shortcuts://run?id=1", "mailto:a@example.org"), ContentInput.urls("(https://example.org/a(b)) shortcuts://run?id=1 mailto:a@example.org.").map { it.text() })
    }
    @Test fun incomingFilesCanBeStoredAsBinaryWithoutImageExtension() = runTest {
        val directory = java.nio.file.Files.createTempDirectory("shortcuts-share-test").toFile()
        try {
            val workspace = WorkspaceFiles(directory); val bytes = byteArrayOf(0, -1, 42)
            val path = workspace.importBinary("Shared", "example.pdf", bytes)
            assertArrayEquals(bytes, workspace.readableFile(path).readBytes())
            assertThrows(Problem::class.java) { kotlinx.coroutines.runBlocking { workspace.importBinary("Shared", "../escape.pdf", bytes) } }
        } finally { directory.deleteRecursively() }
    }
}
