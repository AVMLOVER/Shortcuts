package com.germanium.shortcuts.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class Expansion07Test {
    private fun step(type: String, vararg p: Pair<String, String>) = ActionStep(type = type, parameters = p.toMap())
    @Test fun base64WrappingDecodesBackToTheOriginalText() = runTest {
        val input = "A".repeat(100)
        val result = ShortcutEngine().run(Shortcut(name = "Base64", steps = listOf(step("text", "value" to input), step("encode_base64", "lineLength" to "64"), step("decode_base64"))), ExecutionMode.STANDARD)
        assertEquals(input, result.output); assertEquals(listOf(64, 64, 8), result.steps[1].output.lines().map { it.length })
    }
    @Test fun seededUuidIsStableAndBlankSeedRemainsRandom() = runTest {
        suspend fun uuid(seed: String) = ShortcutEngine().run(Shortcut(name = "Seed", steps = listOf(step("generate_uuid", "seed" to seed))), ExecutionMode.STANDARD).output
        assertEquals(uuid("same"), uuid("same")); assertNotEquals(uuid("first"), uuid("second")); assertNotEquals(uuid(""), uuid(""))
    }
    @Test fun shellRejectsRootRemovalAndRawDeviceWrites() {
        listOf("rm -rf /*", "rm -fr /", "busybox rm -rf -- /*", "sudo rm --recursive --force /", "dd if=/dev/zero of=/dev/block/sda").forEach { assertTrue(it, ShellPolicy.assess(it).denied) }
        assertFalse(ShellPolicy.assess("rm -f ./test.txt").denied)
        assertTrue(ShellPolicy.assess("rm -f ./test.txt").needsConfirmation)
        assertTrue(ShellPolicy.assess("echo hello > output.txt").needsConfirmation)
        assertFalse(ShellPolicy.assess("ls -la").needsConfirmation)
        assertEquals("'a'\\''b'", ShellPolicy.quote("a'b"))
    }
    @Test fun taggedFilesPreserveNestedTypesAndPlainText() {
        val value = Value.Dictionary(linkedMapOf("a\nb" to Value.ListValue(listOf(Value.Number("1.25".toBigDecimal()), Value.BooleanValue(true), Value.Text("<x>\r\n")))))
        assertEquals(value, ValueDocument.decode(ValueDocument.encode(value)))
        assertEquals("hello", ValueDocument.encode(Value.Text("hello")))
        assertEquals(Value.Text("hello"), ValueDocument.decode("hello"))
        val reserved = Value.Text("SHORTCUTS-VALUE-1\nhello"); assertEquals(reserved, ValueDocument.decode(ValueDocument.encode(reserved)))
    }
    @Test fun valueFilesRejectEntitiesAndBadVersions() {
        assertThrows(Problem::class.java) { ValueDocument.decode(ValueDocument.HEADER + "<!DOCTYPE x><shortcuts-value version=\"1\"/>") }
        assertThrows(Problem::class.java) { ValueDocument.decode(ValueDocument.HEADER + "<shortcuts-value version=\"2\"><v type=\"text\">bad</v></shortcuts-value>") }
    }
    @Test fun mathFunctionsAndCongruenceHaveDefinedResults() {
        mapOf("sin(0)" to "0", "cos(0)" to "1", "tan(0)" to "0", "asin(0)" to "0", "acos(1)" to "0", "atan(0)" to "0", "gcd(84,30)" to "6", "lcm(12,18)" to "36", "mod(-7,3)" to "2").forEach { (expression, result) -> assertEquals(expression, 0, Calculator().evaluate(expression).compareTo(result.toBigDecimal())) }
        assertThrows(Exception::class.java) { Calculator().evaluate("gcd(1.5,2)") }
    }
    @Test fun percentCalculationIsMultiplicationByPercentage() = runTest {
        val result = ShortcutEngine().run(Shortcut(name = "Percent", steps = listOf(step("calculate", "left" to "200", "operation" to "%", "right" to "11"))), ExecutionMode.STANDARD)
        assertEquals("22", result.output)
    }
    @Test fun durationUnitsAreExactAndBounded() {
        assertEquals(250L, durationMillis("0.25", "seconds")); assertEquals(250L, durationMillis("250", "milliseconds"))
        assertThrows(Problem::class.java) { durationMillis("61", "seconds") }
        assertThrows(Problem::class.java) { durationMillis("0.5", "milliseconds") }
    }
    @Test fun queryParametersAreEncodedBeforeFragments() {
        assertEquals("https://example.com/a?old=1&q=a%20b%26c#top", urlWithQuery("https://example.com/a?old=1#top", listOf("q" to "a b&c")))
    }
    @Test fun effectActionsAreExcludedFromMagicVariables() {
        val wait = step("delay", "milliseconds" to "1"); val value = step("number", "value" to "1"); val target = step("text", "value" to "")
        assertEquals(listOf(value), TreeEdits.availableBefore(listOf(wait, value, target), target.id))
        listOf("quick_look", "show_result", "vibrate", "notification", "view_html").forEach { assertFalse(ActionOutputs.produces(it)) }
    }
    @Test fun imagePropertiesAreIndividuallyAddressableAndCopied() {
        val first = step("image_info"); val next = step("number", "value" to "{{@${first.id}.width}}")
        val scope = VariableScope(); scope.record(first.id, Value.Dictionary(mapOf("width" to Value.Number(100.toBigDecimal()))))
        assertEquals(Value.Number(100.toBigDecimal()), RunContext(ExecutionMode.STANDARD, Value.Text(""), scope).reference("@${first.id}.width"))
        val owner = ActionStep(type = "if", branches = listOf(ActionBranch("then", steps = listOf(first, next))))
        val copy = TreeEdits.duplicate(owner).branches.single().steps
        assertEquals("{{@${copy.first().id}.width}}", copy.last().parameters["value"])
    }
    @Test fun foldersAndMembershipSurviveXmlAndRepository() = runTest {
        val directory = Files.createTempDirectory("folders").toFile()
        try {
            val folder = ShortcutFolder(name = "Work", icon = "bolt", color = "#12AB34"); val repository = FolderRepository(directory)
            repository.save(folder); assertEquals(listOf(folder), repository.list())
            val source = Shortcut(name = "Inside", folderId = folder.id); assertEquals(source, XmlCodec().decode(XmlCodec().encode(source)))
        } finally { directory.deleteRecursively() }
    }
    @Test fun specificWifiAndNfcTriggerOnlyForTheSelectedIdentity() {
        val wifi = AutomationRule(name = "Wi-Fi", shortcutId = "a", trigger = TriggerKind.SPECIFIC_WIFI, parameters = mapOf("ssid" to "Home"))
        assertFalse(evaluateTrigger(wifi, DeviceSnapshot(wifi = true, ssid = "Other"), "network", 0).fire)
        val matched = evaluateTrigger(wifi, DeviceSnapshot(wifi = true, ssid = "Home"), "network", 0); assertTrue(matched.fire)
        assertFalse(evaluateTrigger(matched.rule, DeviceSnapshot(wifi = true, ssid = "Home"), "network", 1).fire)
        val tag = AutomationRule(name = "Tag", shortcutId = "a", trigger = TriggerKind.NFC, parameters = mapOf("tagId" to "0102AB"))
        assertTrue(evaluateTrigger(tag, DeviceSnapshot(), "nfc:0102ab", 0).fire); assertFalse(evaluateTrigger(tag, DeviceSnapshot(), "nfc:0102AC", 0).fire)
    }
}
