package com.germanium.shortcuts.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId

class LibraryTest {
    private fun step(type: String, vararg p: Pair<String, String>, branches: List<ActionBranch> = emptyList()) = ActionStep(type = type, parameters = p.toMap(), branches = branches)
    private open class Fake : PlatformActions {
        var preview: String? = null
        var notification: Pair<String, String>? = null
        var intent: IntentSpec? = null
        var answer: String? = "12.5"
        var inputOptions: List<String>? = null
        var options = emptyList<Pair<String, String>>()
        override suspend fun shell(command: String, mode: ExecutionMode, timeoutMillis: Long) = ShellResult(0, "", "")
        override suspend fun launchIntent(spec: IntentSpec) { intent = spec }
        override suspend fun quickLook(value: Value) { preview = value.text() }
        override suspend fun postNotification(title: String, body: String) { notification = title to body }
        override suspend fun askInput(title: String, prompt: String, initial: String, type: String): String? { inputOptions = listOf(title, prompt, initial, type); return answer }
        override suspend fun chooseMenu(title: String, items: List<Pair<String, String>>): String? { options = items; return "1" }
        override suspend fun chooseMany(title: String, items: List<Pair<String, String>>): List<String>? { options = items; return listOf("0", "2") }
    }
    private suspend fun run(vararg steps: ActionStep, fake: Fake = Fake()): RunResult = ShortcutEngine(builtInActions(fake), fake).run(Shortcut(name = "Library", steps = steps.toList()), ExecutionMode.STANDARD)
    @Test fun createGetAppendAccumulateTypedList() = runTest {
        val result = run(step("create_variable", "name" to "values", "valueType" to "number", "value" to "1"), step("append_variable", "name" to "values", "value" to "second"), step("get_variable", "name" to "values"))
        assertEquals(RunStatus.SUCCEEDED, result.status)
        assertEquals(listOf(Value.Number(1.toBigDecimal()), Value.Text("second")), (result.variables.getValue("values") as Value.ListValue).items)
        assertEquals("1\nsecond", result.output)
    }
    @Test fun newVariableRejectsOverwriteAndAppendRequiresExisting() = runTest {
        assertEquals(RunStatus.FAILED, run(step("create_variable", "name" to "v"), step("create_variable", "name" to "v")).status)
        assertEquals(RunStatus.FAILED, run(step("append_variable", "name" to "missing")).status)
    }
    @Test fun everyActionOutputRemainsReferenceableAfterInterveningSteps() = runTest {
        val first = step("list", "items" to "a\nb")
        val result = run(first, step("text", "value" to "elsewhere"), step("get_list_item", "source" to "{{@${first.id}}}", "selection" to "last"))
        assertEquals("b", result.output)
    }
    @Test fun typedMultilineListSurvivesXml() = runTest {
        val parameters = writeEntrySpecs(emptyMap(), "item", "items", listOf(EntrySpec("text", "", "a\nb"), EntrySpec("number", "", "42")))
        val shortcut = Shortcut(name = "List", steps = listOf(ActionStep(type = "list", parameters = parameters)))
        val codec = XmlCodec(); val decoded = codec.decode(codec.encode(shortcut))
        val result = ShortcutEngine().run(decoded, ExecutionMode.STANDARD)
        val list = result.steps.single().value as Value.ListValue
        assertEquals(Value.Text("a\nb"), list.items[0]); assertEquals(Value.Number(42.toBigDecimal()), list.items[1])
    }
    @Test fun listSelectionIsOneBasedAndSupportsRangesAndRandom() = runTest {
        val list = step("list", "items" to "a\nb\nc")
        assertEquals("a", run(list, step("get_list_item", "index" to "1")).output)
        assertEquals("b\nc", run(list, step("get_list_item", "selection" to "range", "index" to "2", "endIndex" to "3")).output)
        assertTrue(run(list, step("get_list_item", "selection" to "random")).output in listOf("a", "b", "c"))
        assertEquals(RunStatus.FAILED, run(list, step("get_list_item", "index" to "0")).status)
    }
    @Test fun chooseFromDictionaryPresentsKeysAndReturnsTypedValues() = runTest {
        val fake = Fake()
        val result = run(step("dictionary", "entries" to "text|First|one\nnumber|Second|2"), step("choose_from_list"), fake = fake)
        assertEquals(listOf("First", "Second"), fake.options.map { it.second }); assertEquals("2", result.output)
        assertTrue(result.steps.last().value is Value.Number)
    }
    @Test fun multipleSelectionPreservesIndividualValues() = runTest {
        assertEquals("a\nc", run(step("list", "items" to "a\nb\nc"), step("choose_from_list", "multiple" to "true")).output)
    }
    @Test fun askInputStoresItsOwnVariableAndCustomDialogFields() = runTest {
        val fake = Fake()
        val result = run(step("ask_input", "title" to "Price", "prompt" to "Enter price", "initial" to "1", "inputType" to "number", "resultVariable" to "price"), step("calculate_expression", "expression" to "{{price}} * 2 + 1"), fake = fake)
        assertEquals("26", result.output); assertEquals(listOf("Price", "Enter price", "1", "number"), fake.inputOptions)
        assertTrue(result.variables["price"] is Value.Number)
    }
    @Test fun cancelledInputCannotRunFollowingSideEffects() = runTest {
        val fake = Fake().apply { answer = null }
        try { run(step("ask_input"), step("notification"), fake = fake); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertNull(fake.notification)
    }
    @Test fun simpleMathAndExpressionsAcceptVariables() = runTest {
        assertEquals("12", run(step("text", "value" to "3"), step("calculate", "operation" to "*", "right" to "4")).output)
        assertEquals("7", run(step("create_variable", "name" to "n", "valueType" to "number", "value" to "3"), step("calculate_expression", "expression" to "n * 2 + 1")).output)
    }
    @Test fun repeatEachProducesListAndRestoresLoopVariables() = runTest {
        val body = step("calculate_expression", "expression" to "{{repeat_item}} * {{repeat_index}}")
        val result = run(step("list", "items" to "2\n3\n4"), step("repeat_each", branches = listOf(ActionBranch("body", steps = listOf(body)))))
        assertEquals("2\n6\n12", result.output); assertFalse(result.variables.containsKey("repeat_item")); assertFalse(result.variables.containsKey("repeat_index"))
        assertEquals(5, result.executedCount)
    }
    @Test fun repeatEachBoundsAccumulatedOutputBeforeRetainingAllIterations() = runTest {
        val loop = step("repeat_each", branches = listOf(ActionBranch("body", steps = listOf(step("text", "value" to "x".repeat(20000))))))
        val result = run(step("list", "items" to (1..200).joinToString("\n")), loop)
        assertEquals(RunStatus.FAILED, result.status); assertEquals("err_size", (result.failure as Problem).key)
        assertEquals(loop.id, result.failedStepId); assertTrue(result.executedCount < 20)
    }
    @Test fun quickLookOnlyDisplaysItsConfiguredValue() = runTest {
        val fake = Fake()
        run(step("text", "value" to "silent"), fake = fake); assertNull(fake.preview)
        val result = run(step("text", "value" to "previous"), step("quick_look", "value" to "chosen"), step("text", "value" to "later"), fake = fake)
        assertEquals("chosen", fake.preview); assertEquals("later", result.output)
    }
    @Test fun reminderAndNotificationUseDifferentTitleRules() = runTest {
        val fake = Fake()
        run(step("notification", "title" to "Custom", "body" to "Body"), fake = fake); assertEquals("Custom" to "Body", fake.notification)
        run(step("reminder", "body" to "Remember"), fake = fake); assertEquals("Shortcuts" to "Remember", fake.notification)
    }
    @Test fun appActionUsesSelectedLauncherComponent() = runTest {
        val fake = Fake(); run(step("open_app", "packageName" to "test.app", "className" to "test.app.Main"), fake = fake)
        assertEquals("android.intent.action.MAIN", fake.intent!!.action); assertEquals("test.app.Main", fake.intent!!.className)
    }
    @Test fun encodingHashSortingAndDateAreDeterministic() = runTest {
        assertEquals("Hello 日本", run(step("encode_base64", "source" to "Hello 日本"), step("decode_base64")).output)
        assertEquals("a b+?", run(step("encode_url", "source" to "a b+?"), step("decode_url")).output)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", run(step("hash_text", "source" to "abc")).output)
        assertEquals("2\n10", run(step("list", "items" to "10\n2"), step("sort_list", "numeric" to "true")).output)
        assertEquals("2026-10-28", run(step("format_date", "source" to "2026-10-28T09:00:00Z", "format" to "yyyy-MM-dd", "zone" to "UTC")).output)
    }
    @Test fun workspaceRoundTripAppendsListsAndDeletesOnlyRequestedFile() = runTest {
        val directory = Files.createTempDirectory("shortcuts-files").toFile()
        try {
            val files = WorkspaceFiles(directory)
            files.perform("create_folder", "Notes/Day1", "", "")
            val path = files.perform("create_file", "Notes/Day1", "note.txt", "").text()
            files.perform("write_file", "Notes/Day1", path, "hello\n"); files.perform("append_file", "Notes/Day1", "note.txt", "世界")
            assertEquals("hello\n世界", files.perform("read_file", "Notes/Day1", "note.txt", "").text())
            assertEquals("Notes/Day1/note.txt", files.perform("list_files", "Notes/Day1", "", "").text())
            assertEquals("12", ((files.perform("file_info", "Notes/Day1", "note.txt", "") as Value.Dictionary).entries.getValue("size")).text())
            files.perform("delete_file", "Notes/Day1", "note.txt", ""); assertEquals("", files.perform("list_files", "Notes/Day1", "", "").text())
        } finally { directory.deleteRecursively() }
    }
    @Test fun workspaceRejectsTraversalAndPreservesExistingFilesOnFailure() = runTest {
        val directory = Files.createTempDirectory("shortcuts-path").toFile()
        try {
            val files = WorkspaceFiles(directory)
            files.perform("write_file", "A", "safe.txt", "old")
            for ((folder, name) in listOf(".." to "x", "A" to "../../x", "/absolute" to "x", "C:/bad" to "x", "A" to "C:\\bad")) {
                try { files.perform("write_file", folder, name, "unsafe"); fail("Expected path rejection") } catch (_: Problem) { }
            }
            try { files.perform("create_file", "A", "safe.txt", ""); fail("Expected existing file rejection") } catch (_: Problem) { }
            try { files.perform("write_file", "A", "safe.txt", "x".repeat(1048577)); fail("Expected size rejection") } catch (_: Problem) { }
            assertEquals("old", files.perform("read_file", "A", "safe.txt", "").text())
        } finally { directory.deleteRecursively() }
    }
    @Test fun automationEdgesFireOnceUntilConditionClears() {
        var rule = AutomationRule(name = "Battery", shortcutId = "shortcut", trigger = TriggerKind.BATTERY_BELOW, parameters = mapOf("level" to "20"))
        val first = evaluateTrigger(rule, DeviceSnapshot(battery = 19), "battery", 0); assertTrue(first.fire); rule = first.rule
        assertFalse(evaluateTrigger(rule, DeviceSnapshot(battery = 18), "tick", 0).fire)
        rule = evaluateTrigger(rule, DeviceSnapshot(battery = 80), "battery", 0).rule
        assertTrue(evaluateTrigger(rule, DeviceSnapshot(battery = 10), "battery", 0).fire)
        assertFalse(evaluateTrigger(rule.copy(enabled = false), DeviceSnapshot(battery = 10), "battery", 0).fire)
    }
    @Test fun automationTimeDeduplicatesAcrossReloadAndFiresNextDay() = runTest {
        val directory = Files.createTempDirectory("shortcuts-automation").toFile()
        try {
            val repository = AutomationRepository(directory)
            val rule = AutomationRule(name = "Morning & <日>", shortcutId = "s1", trigger = TriggerKind.TIME, parameters = mapOf("time" to "09:00"))
            val now = Instant.parse("2026-10-28T01:00:00Z").toEpochMilli(); val zone = ZoneId.of("Asia/Shanghai")
            val decision = evaluateTrigger(rule, DeviceSnapshot(), "tick", now, zone); assertTrue(decision.fire)
            repository.save(decision.rule); val restored = repository.list().single(); assertEquals(decision.rule, restored)
            assertFalse(evaluateTrigger(restored, DeviceSnapshot(), "tick", now + 15000, zone).fire)
            assertTrue(evaluateTrigger(restored, DeviceSnapshot(), "tick", now + 86400000, zone).fire)
            repository.delete(rule.id); assertTrue(repository.list().isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun staleMonitorStateCannotUndoAnEditOrResurrectADeletedAutomation() = runTest {
        val directory = Files.createTempDirectory("shortcuts-cas").toFile()
        try {
            val repository = AutomationRepository(directory)
            val rule = AutomationRule(name = "Power", shortcutId = "s", trigger = TriggerKind.CHARGING)
            repository.save(rule)
            val updated = evaluateTrigger(rule, DeviceSnapshot(charging = true), "charging", 0).rule
            repository.save(rule.copy(enabled = false))
            assertFalse(repository.saveState(rule, updated)); assertFalse(repository.list().single().enabled)
            repository.delete(rule.id); assertFalse(repository.saveState(rule, updated)); assertTrue(repository.list().isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun duplicatingNestedActionsRemapsInternalMagicReferences() = runTest {
        val source = step("text", "value" to "copy")
        val loop = step("repeat_each", "source" to "{{input}}", branches = listOf(ActionBranch("body", steps = listOf(source, step("output", "value" to "{{@${source.id}}}")))))
        val duplicated = TreeEdits.duplicate(loop)
        val children = duplicated.branches.single().steps
        assertEquals("body", duplicated.branches.single().id)
        assertEquals("{{@${children.first().id}}}", children.last().parameters["value"])
        assertEquals("copy", run(step("list", "items" to "a"), duplicated).output)
    }
    @Test fun automationOnlyAcceptsRelevantSystemEventsAndValidConditions() {
        for ((kind, event) in listOf(TriggerKind.SCREEN_ON to "screen_on", TriggerKind.SCREEN_OFF to "screen_off", TriggerKind.BOOT to "boot")) {
            val rule = AutomationRule(name = "Event", shortcutId = "s1", trigger = kind)
            assertTrue(evaluateTrigger(rule, DeviceSnapshot(), event, 0).fire); assertFalse(evaluateTrigger(rule, DeviceSnapshot(), "tick", 0).fire)
        }
        assertThrows(Problem::class.java) { AutomationRule(name = "Bad", shortcutId = "s", trigger = TriggerKind.TIME, parameters = mapOf("time" to "24:30")) }
        assertThrows(Problem::class.java) { AutomationRule(name = "Bad", shortcutId = "s", trigger = TriggerKind.BATTERY_BELOW, parameters = mapOf("level" to "0")) }
    }
}
