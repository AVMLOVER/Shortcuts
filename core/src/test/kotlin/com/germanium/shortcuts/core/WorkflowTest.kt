package com.germanium.shortcuts.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.time.ZoneId

class WorkflowTest {
    private fun action(type: String, vararg parameters: Pair<String, String>, branches: List<ActionBranch> = emptyList()) = ActionStep(type = type, parameters = parameters.toMap(), branches = branches)
    private class Fake : PlatformActions {
        var selection: String? = "selected"
        var notified = 0
        var calendar: CalendarEventSpec? = null
        var installation: Pair<String, ExecutionMode>? = null
        var preview: Value? = null
        override suspend fun shell(command: String, mode: ExecutionMode, timeoutMillis: Long) = ShellResult(0, "ok", "")
        override suspend fun launchIntent(spec: IntentSpec) = Unit
        override suspend fun chooseMenu(title: String, items: List<Pair<String, String>>) = selection
        override suspend fun postNotification(title: String, body: String) { notified++ }
        override suspend fun quickLook(value: Value) { preview = value }
        override suspend fun createCalendarEvent(event: CalendarEventSpec): String { calendar = event; return "content://calendar/events/1" }
        override suspend fun installApk(uri: String, mode: ExecutionMode): String { installation = uri to mode; return "installed" }
    }
    private suspend fun run(steps: List<ActionStep>, fake: Fake = Fake()): RunResult = ShortcutEngine(builtInActions(fake), fake).run(Shortcut(name = "test", steps = steps), ExecutionMode.STANDARD)
    @Test fun menuExecutesOnlySelectedChildrenThenContinues() = runTest {
        val fake = Fake()
        val result = run(listOf(action("text", "value" to "start"), action("menu", branches = listOf(
            ActionBranch("wrong", "Wrong", listOf(action("notification"))),
            ActionBranch("selected", "Selected", listOf(action("append_text", "value" to "-selected"))),
        )), action("append_text", "value" to "-end")), fake)
        assertEquals("start-selected-end", result.output); assertEquals(0, fake.notified)
        assertEquals("Selected", result.variables["menu_choice"]!!.text())
    }
    @Test fun cancellingMenuStopsBeforeAnyFollowingSideEffects() = runTest {
        val fake = Fake().apply { selection = null }
        try { run(listOf(action("menu", branches = listOf(ActionBranch("selected", "A"))), action("notification")), fake); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(0, fake.notified)
    }
    @Test fun ifSelectsThenOrOptionalElseAndPreservesInputWhenAbsent() = runTest {
        fun block(elseBranch: Boolean) = action("if", "left" to "{{input}}", "operator" to "greater", "right" to "5", branches = listOf(ActionBranch("then", steps = listOf(action("text", "value" to "then")))) + if (elseBranch) listOf(ActionBranch("else", steps = listOf(action("text", "value" to "else")))) else emptyList())
        assertEquals("then", run(listOf(action("text", "value" to "6"), block(true))).output)
        assertEquals("else", run(listOf(action("text", "value" to "2"), block(true))).output)
        assertEquals("2", run(listOf(action("text", "value" to "2"), block(false))).output)
    }
    @Test fun nestedIfAndMenuRemainExecutableAfterXmlRoundTrip() = runTest {
        val nested = action("if", "operator" to "is_not_empty", branches = listOf(ActionBranch("then", steps = listOf(
            action("menu", branches = listOf(ActionBranch("selected", "Selected", listOf(action("append_text", "value" to "!")))))
        ))))
        val source = Shortcut(name = "nested", steps = listOf(action("text", "value" to "ok"), nested))
        val codec = XmlCodec(); val bytes = codec.encode(source)
        assertTrue(bytes.toString(Charsets.UTF_8).contains("version=\"2\"")); assertEquals(source, codec.decode(bytes))
        val fake = Fake(); assertEquals("ok!", ShortcutEngine(builtInActions(fake), fake).run(codec.decode(bytes), ExecutionMode.STANDARD).output)
    }
    @Test fun repeatIndexRestoresAcrossNestedLoops() = runTest {
        val inner = action("repeat", "count" to "2", branches = listOf(ActionBranch("body", steps = listOf(action("append_text", "value" to "{{repeat_index}}")))))
        val outer = action("repeat", "count" to "2", branches = listOf(ActionBranch("body", steps = listOf(inner, action("append_text", "value" to "[{{repeat_index}}]")))))
        val result = run(listOf(action("text", "value" to ""), outer))
        assertEquals("12[1]12[2]", result.output); assertFalse(result.variables.containsKey("repeat_index"))
    }
    @Test fun loopsHaveAnExecutionLimit() = runTest {
        val body = List(51) { action("output") }
        val result = run(listOf(action("repeat", "count" to "1000", branches = listOf(ActionBranch("body", steps = body)))))
        assertEquals(RunStatus.FAILED, result.status); assertEquals("err_step_limit", result.error)
    }
    @Test fun stoppingInsideBranchStopsEntireWorkflow() = runTest {
        val fake = Fake()
        val result = run(listOf(action("if", "left" to "x", "operator" to "is_not_empty", branches = listOf(ActionBranch("then", steps = listOf(action("stop", "value" to "done"))))), action("notification")), fake)
        assertEquals(RunStatus.SUCCEEDED, result.status); assertEquals("done", result.output); assertEquals(0, fake.notified)
    }
    @Test fun disabledBlocksDoNotValidateOrExecuteTheirChildren() = runTest {
        val invalid = action("if", branches = listOf(ActionBranch("then", steps = listOf(action("unknown_action"), action("regex_replace", "pattern" to "["))))).copy(enabled = false)
        assertEquals("ok", run(listOf(invalid, action("text", "value" to "ok"))).output)
    }
    @Test fun literalReplacementHandlesCaseAndDoesNotExpandGroups() = runTest {
        assertEquals("$1 $1", run(listOf(action("text", "value" to "Hello hello"), action("replace_text", "find" to "hello", "replacement" to "$1", "ignoreCase" to "true"))).output)
    }
    @Test fun regexReplacementSupportsLiteralAndCaptureModes() = runTest {
        val source = action("text", "value" to "a12b34")
        assertEquals("a$1b$1", run(listOf(source, action("regex_replace", "pattern" to "([0-9]+)", "replacement" to "$1"))).output)
        assertEquals("a[12]b[34]", run(listOf(source, action("regex_replace", "pattern" to "([0-9]+)", "replacement" to "[$1]", "groups" to "true"))).output)
        assertEquals(RunStatus.FAILED, run(listOf(action("regex_replace", "pattern" to "["))).status)
        assertEquals("a#b#", run(listOf(action("set_variable", "name" to "pattern", "value" to "[0-9]+"), source, action("regex_replace", "pattern" to "{{pattern}}", "replacement" to "#"))).output)
    }
    @Test(timeout = 5000) fun regexAvoidsCatastrophicBacktracking() = runBlocking {
        val result = run(listOf(action("text", "value" to "a".repeat(16000) + "!"), action("regex_replace", "pattern" to "(a+)+$", "replacement" to "x")))
        assertEquals(RunStatus.SUCCEEDED, result.status)
    }
    @Test fun textToolsAndUnicodeCount() = runTest {
        assertEquals("2", run(listOf(action("text", "value" to "😀a"), action("count_text"))).output)
        assertEquals("HELLO", run(listOf(action("text", "value" to " hello "), action("trim_text"), action("text_case", "case" to "upper"))).output)
        assertEquals("a|b|c", run(listOf(action("text", "value" to "a,b,c"), action("split_text", "separator" to ","), action("join_text", "separator" to "|"))).output)
        assertEquals(RunStatus.FAILED, run(listOf(action("text", "value" to "x".repeat(257)), action("match_text", "pattern" to "."))).status)
    }
    @Test fun quickLookAndCalendarAndInstallationUsePlatformContracts() = runTest {
        val fake = Fake()
        val result = run(listOf(action("text", "value" to "hello"), action("quick_look"), action("notification"), action("calendar", "title" to "Meeting", "start" to "2026-10-28T09:00:00+08:00", "end" to "2026-10-28T10:00:00+08:00", "reminderMinutes" to "10")), fake)
        assertEquals(RunStatus.SUCCEEDED, result.status); assertEquals("hello", fake.preview!!.text()); assertEquals(1, fake.notified); assertEquals(10, fake.calendar!!.reminderMinutes)
        for (mode in ExecutionMode.entries) {
            val engine = ShortcutEngine(builtInActions(fake), fake)
            val installation = engine.run(Shortcut(name = "apk", steps = listOf(action("install_apk", "uri" to "content://test/app.apk"))), mode)
            assertEquals(RunStatus.SUCCEEDED, installation.status); assertEquals(mode, fake.installation!!.second)
        }
    }
    @Test fun dateParsingAndInvalidCalendarRange() = runTest {
        assertEquals(parseEventTime("2026-10-28T09:00:00+08:00"), parseEventTime("2026-10-28T09:00:00", ZoneId.of("Asia/Shanghai")))
        assertEquals(RunStatus.FAILED, run(listOf(action("calendar", "title" to "Meeting", "start" to "2000", "end" to "1000"))).status)
    }
    @Test fun treeEditsPreserveSiblingsAndRefreshAllCopiedIds() {
        val first = action("text", "value" to "a"); val second = action("text", "value" to "b")
        val menu = action("menu", branches = listOf(ActionBranch("selected", "A", listOf(first, second))))
        val moved = TreeEdits.move(listOf(menu), second.id, -1)
        assertEquals(second.id, moved.single().branches.single().steps.first().id)
        val duplicated = TreeEdits.copyAfter(moved, menu.id)
        val ids = allSteps(duplicated).map { it.id }; assertEquals(ids.size, ids.distinct().size)
        assertEquals(2, TreeEdits.remove(duplicated, second.id).size)
    }
    @Test fun traceContainsFailuresAndActionIds() = runTest {
        val trace = mutableListOf<RunTrace>(); val source = Shortcut(name = "trace", steps = listOf(action("text", "value" to "{{missing}}")))
        val result = ShortcutEngine().run(source, ExecutionMode.STANDARD, onTrace = { trace += it })
        assertEquals(RunStatus.FAILED, result.status); assertTrue(trace.any { it.event == "step_start" && it.stepId == source.steps.single().id }); assertTrue(trace.any { it.failure != null })
    }
    @Test fun historiesStayBoundedAndPreserveFinalOutput() = runTest {
        val block = action("repeat", "count" to "30", branches = listOf(ActionBranch("body", steps = listOf(action("text", "value" to "x".repeat(100000))))))
        val result = run(listOf(block))
        assertEquals(RunStatus.SUCCEEDED, result.status); assertTrue(result.historyTruncated); assertEquals(31, result.executedCount)
        assertEquals(100000, result.output.length); assertTrue(result.steps.sumOf { it.output.length } <= 2097152)
    }
    @Test fun aggregateVariableMemoryIsBounded() {
        val variables = VariableScope()
        repeat(16) { variables.set("v$it", Value.Text("x".repeat(262144))) }
        assertThrows(Problem::class.java) { variables.set("overflow", Value.Text("x")) }
        variables.remove("v0"); variables.set("reused", Value.Text("x".repeat(262144)))
    }
    @Test fun processCanStreamInstallInputWithoutBlockingOutput() {
        val java = File(System.getProperty("java.home"), "bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
        val classpath = listOf(ProcessFixture::class.java, Unit::class.java).map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        val result = ShellProcessTask().run(listOf(java.path, "-cp", classpath, ProcessFixture::class.java.name, "stdin"), 10000, ByteArrayInputStream(ByteArray(250000) { 42 }))
        assertEquals(0, result.exitCode); assertEquals("250000", result.stdout)
    }
}
