package com.germanium.shortcuts.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class RuntimeTest {
    private fun step(type: String, vararg pairs: Pair<String, String>) = ActionStep(type = type, parameters = mapOf(*pairs))
    private suspend fun run(vararg steps: ActionStep) = ShortcutEngine().run(Shortcut(name = "test", steps = steps.toList()), ExecutionMode.STANDARD)
    @Test fun decimalPrecedenceAndFunctions() {
        val calculator = Calculator { if (it == "价格") BigDecimal("12.5") else error(it) }
        mapOf("0.1+0.2" to "0.3", "2+3*4" to "14", "(2+3)*4" to "20", "-2^2" to "-4", "2^3^2" to "512", "2^-2" to "0.25", "min(价格, 10)+abs(-2)" to "12", "round(2.5)" to "3", "7%3" to "1").forEach { (expression, expected) ->
            assertEquals(expression, 0, calculator.evaluate(expression).compareTo(BigDecimal(expected)))
        }
    }
    @Test fun mathRejectsInvalidAndUnboundedExpressions() {
        listOf("1/0", "1%0", "2^101", "1e999999", "1+", "(1", "1abc", "unknown(2)", "(".repeat(150) + "1" + ")".repeat(150)).forEach {
            assertThrows(Exception::class.java) { Calculator().evaluate(it) }
        }
    }
    @Test fun variablesCarryTypesAndCaptureResults() = runTest {
        val result = run(step("set_variable", "name" to "price", "valueType" to "number", "value" to "12.5"), step("calculate", "expression" to "price * 2", "resultVariable" to "total"), step("text", "value" to "合计 {{total}}"))
        assertEquals("合计 25", result.output)
        assertTrue(result.variables["total"] is Value.Number)
    }
    @Test fun exactReferencePreservesType() = runTest {
        val result = run(step("set_variable", "name" to "flag", "valueType" to "boolean", "value" to "true"), step("text", "value" to "{{flag}}"))
        assertTrue(result.steps.last().value is Value.BooleanValue)
    }
    @Test fun variablesAreIsolatedPerRunAndMissingReferenceFails() = runTest {
        val engine = ShortcutEngine()
        engine.run(Shortcut(name = "A", steps = listOf(step("set_variable", "name" to "a", "value" to "1"))), ExecutionMode.STANDARD)
        val result = engine.run(Shortcut(name = "B", steps = listOf(step("text", "value" to "{{a}}"))), ExecutionMode.STANDARD)
        assertEquals(RunStatus.FAILED, result.status)
        assertNotNull(result.failedStepId)
    }
    @Test fun reservedVariableCannotBeOverwritten() = runTest {
        assertEquals(RunStatus.FAILED, run(step("set_variable", "name" to "input", "value" to "bad")).status)
    }
    @Test fun dictionaryCreateReadUpdateAndNestedValues() = runTest {
        val result = run(
            step("dictionary", "entries" to "text|name|Shortcuts\nnumber|count|3\nboolean|enabled|true", "resultVariable" to "config"),
            step("set_dictionary_value", "dictionary" to "{{config}}", "key" to "count", "valueType" to "number", "value" to "4", "resultVariable" to "config"),
            step("dictionary", "entries" to "auto|nested|{{config}}"),
            step("get_dictionary_value", "key" to "nested"), step("get_dictionary_value", "key" to "count"), step("calculate", "expression" to "input * 10"),
        )
        assertEquals(RunStatus.SUCCEEDED, result.status)
        assertEquals("40", result.output)
    }
    @Test fun dictionaryRejectsMissingKeysDuplicateKeysAndInvalidTypes() = runTest {
        assertEquals(RunStatus.FAILED, run(step("dictionary", "entries" to "text|a|x\ntext|a|y")).status)
        assertEquals(RunStatus.FAILED, run(step("dictionary", "entries" to "number|a|abc")).status)
        assertEquals(RunStatus.FAILED, run(step("dictionary", "entries" to ""), step("get_dictionary_value", "key" to "missing")).status)
    }
    @Test fun templatesDoNotRecursivelyEvaluate() {
        val scope = VariableScope().apply { set("a", Value.Text("{{missing}}")) }
        assertEquals("{{missing}}", RunContext(ExecutionMode.STANDARD, Value.Text(""), scope).render("{{a}}"))
    }
    @Test fun structuredEntriesPreserveMultilineKeysAndValuesThroughXml() = runTest {
        val params = writeEntrySpecs(emptyMap(), "entry", "entries", listOf(EntrySpec("text", "a|b", "你好\n世界|data"), EntrySpec("number", "n", "2.5")))
        val source = Shortcut(name = "dict", steps = listOf(ActionStep(type = "dictionary", parameters = params), step("get_dictionary_value", "key" to "a|b")))
        val codec = XmlCodec()
        val result = ShortcutEngine().run(codec.decode(codec.encode(source)), ExecutionMode.STANDARD)
        assertEquals(RunStatus.SUCCEEDED, result.status)
        assertEquals("你好\n世界|data", result.output)
    }
    @Test fun structuredExtrasPreserveMultilineValuesAndMigrateLegacyWithoutLoss() {
        val legacy = mapOf("extras" to "string|text|hello|world", "action" to "send")
        val entries = readEntrySpecs(legacy, "extra", "extras") + EntrySpec("string", "multiline", "a\nb")
        val parameters = writeEntrySpecs(legacy, "extra", "extras", entries)
        assertFalse(parameters.containsKey("extras"))
        assertEquals("send", parameters["action"])
        val extras = intentExtrasFromParameters(parameters) { it }
        assertEquals("hello|world", extras.first().value)
        assertEquals("a\nb", extras.last().value)
    }
    @Test fun templateAndDictionaryExpansionAreBounded() {
        val scope = VariableScope().apply { set("large", Value.Text("a".repeat(200000))) }
        val context = RunContext(ExecutionMode.STANDARD, Value.Text(""), scope)
        assertThrows(IllegalArgumentException::class.java) { context.render("{{large}}{{large}}") }
        assertThrows(IllegalArgumentException::class.java) { Value.Dictionary(mapOf("a" to scope.get("large"), "b" to scope.get("large"))) }
    }
    @Test fun intentCarriesTypedExtrasAndResolvesVariables() = runTest {
        var captured: IntentSpec? = null
        val fake = object : PlatformActions {
            override suspend fun shell(command: String, mode: ExecutionMode, timeoutMillis: Long) = error("unexpected")
            override suspend fun launchIntent(spec: IntentSpec) { captured = spec }
        }
        val result = ShortcutEngine(builtInActions(fake)).run(Shortcut(name = "intent", steps = listOf(
            step("text", "value" to "hello\nworld"), step("intent", "action" to "android.intent.action.SEND", "mimeType" to "text/plain", "extras" to "string|android.intent.extra.TEXT|{{input}}\nint|count|3\nboolean|flag|true"),
        )), ExecutionMode.STANDARD)
        assertEquals(RunStatus.SUCCEEDED, result.status)
        assertEquals("hello\nworld", captured!!.extras.first().value)
        assertEquals("int", captured!!.extras[1].type)
    }
    @Test fun extrasRejectDuplicateAndInvalidScalarTypes() {
        listOf("int|count|x", "boolean|flag|yes", "double|a|NaN", "string|a|x\nstring|a|y", "bad").forEach {
            assertThrows(IllegalArgumentException::class.java) { parseIntentExtras(it) }
        }
    }
    @Test fun shellReceivesModeTimeoutAndReportsExitFailure() = runTest {
        var selected: ExecutionMode? = null
        val fake = object : PlatformActions {
            override suspend fun launchIntent(spec: IntentSpec) = Unit
            override suspend fun shell(command: String, mode: ExecutionMode, timeoutMillis: Long): ShellResult {
                selected = mode; assertEquals("echo hi", command); assertEquals(5000L, timeoutMillis); return ShellResult(7, "", "denied")
            }
        }
        val result = ShortcutEngine(builtInActions(fake)).run(Shortcut(name = "shell", steps = listOf(step("shell", "command" to "echo hi", "timeoutSeconds" to "5"))), ExecutionMode.SHIZUKU)
        assertEquals(ExecutionMode.SHIZUKU, selected)
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals("7", result.variables["shell_exit_code"]!!.text())
    }
    @Test fun themeRoundTripAndLegacyDefault() {
        val codec = XmlCodec()
        ThemeMode.entries.forEach { assertEquals(AppSettings(theme = it), codec.decodeSettings(codec.encode(AppSettings(theme = it)))) }
        assertEquals(ThemeMode.SYSTEM, codec.decodeSettings("<settings version=\"1\" mode=\"ROOT\"/>".toByteArray()).theme)
    }
    @Test fun processDrainsBothStreamsAndReportsExit() {
        val result = ShellProcessTask().run(fixture("normal"), 10000)
        assertEquals(7, result.exitCode); assertEquals("out", result.stdout); assertTrue(result.stderr.endsWith("err"))
    }
    @Test fun processOutputIsBoundedWithoutDeadlock() {
        val result = ShellProcessTask().run(fixture("large"), 10000)
        assertTrue(result.truncated); assertEquals(65536, result.stdout.length); assertEquals(65536, result.stderr.length)
    }
    @Test fun processTimeoutAndCancellation() {
        assertThrows(TimeoutException::class.java) { ShellProcessTask().run(fixture("sleep"), 100) }
        val task = ShellProcessTask()
        val started = java.util.concurrent.CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<ShellResult> { task.run(fixture("sleep"), 10000, onStarted = { started.countDown() }) }
            assertTrue("Process did not start", started.await(10, TimeUnit.SECONDS)); task.cancel()
            assertThrows(java.util.concurrent.ExecutionException::class.java) { result.get(3, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
    }
    private fun fixture(mode: String): List<String> {
        val java = File(System.getProperty("java.home"), "bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
        val classpath = listOf(ProcessFixture::class.java, Unit::class.java).map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        return listOf(java.path, "-cp", classpath, ProcessFixture::class.java.name, mode)
    }
}

object ProcessFixture {
    @JvmStatic fun main(args: Array<String>) {
        when (args.single()) {
            "normal" -> { print("out"); System.err.print("err"); kotlin.system.exitProcess(7) }
            "large" -> { repeat(100) { print("x".repeat(2048)); System.err.print("y".repeat(2048)) } }
            "sleep" -> Thread.sleep(10000)
            "stdin" -> print(System.`in`.readBytes().size)
        }
    }
}
