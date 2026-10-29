package com.germanium.shortcuts.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Version103Test {
    private class Backend(var shortcuts: List<Shortcut> = emptyList()) : PlatformActions {
        var chosen: String? = null
        override suspend fun shortcutsForCall() = shortcuts
        override suspend fun chooseShortcut(items: List<Shortcut>) = chosen
        override suspend fun shell(command: String, mode: ExecutionMode, timeoutMillis: Long): ShellResult = throw Problem("err_backend")
        override suspend fun launchIntent(spec: IntentSpec) { }
    }
    private fun action(type: String, vararg p: Pair<String, String>) = ActionStep(type = type, parameters = p.toMap())
    private fun flow(vararg steps: ActionStep) = Shortcut(name = "Call test", steps = steps.toList())
    private fun engine(b: Backend) = ShortcutEngine(builtInActions(b), b)
    private fun call(child: Shortcut, input: String = "{{input}}") = action("run_shortcut", "shortcutUuid" to child.uuid, "input" to input)
    @Test fun typedInputReachesSharedContentAndReturnStopsTheChild() = runTest {
        val child = flow(action("share_input"), action("return"), action("text", "value" to "unreachable"))
        val invoke = call(child, "{{payload}}")
        val parent = flow(action("dictionary", "entries" to "number|answer|42"), action("create_variable", "name" to "payload"), invoke)
        val result = engine(Backend(listOf(child))).run(parent, ExecutionMode.STANDARD)
        assertEquals(RunStatus.SUCCEEDED, result.status)
        assertEquals(Value.Number(42.toBigDecimal()), (result.finalValue as Value.Dictionary).entries["answer"])
        assertEquals(setOf(InputOrigin.SHORTCUT), result.valueOrigins)
        assertEquals(invoke.id, result.steps.last().stepId)
    }
    @Test fun nestedCallsKeepIndependentVariablesAndResumeTheirCallers() = runTest {
        val leaf = flow(action("set_variable", "name" to "local", "value" to "child"), action("return", "value" to "returned"))
        val middle = flow(call(leaf), action("append_text", "value" to "-middle"))
        val parent = flow(action("create_variable", "name" to "local", "value" to "parent"), call(middle), action("text", "value" to "{{input}}/{{local}}"))
        assertEquals("returned-middle/parent", engine(Backend(listOf(leaf, middle))).run(parent, ExecutionMode.STANDARD).output)
    }
    @Test fun missingTargetStopsBeforeAnyFollowingActionAndRenameDoesNotBreakCalls() = runTest {
        val child = flow(action("return", "value" to "ok")); val b = Backend(listOf(child.copy(name = "Renamed"))); val e = engine(b)
        assertEquals("ok", e.run(flow(call(child)), ExecutionMode.STANDARD).output)
        b.shortcuts = emptyList()
        val result = e.run(flow(call(child), action("text", "value" to "must not run")), ExecutionMode.STANDARD)
        assertEquals(RunStatus.FAILED, result.status); assertEquals("err_shortcut_missing", (result.failure as Problem).key); assertTrue(result.steps.isEmpty())
    }
    @Test fun runtimeChoiceAndExplicitEndOnlyEndTheCurrentShortcut() = runTest {
        val child = flow(action("end_shortcut"), action("text", "value" to "unreachable")); val b = Backend(listOf(child)).also { it.chosen = child.uuid }
        val result = engine(b).run(flow(action("run_shortcut", "selection" to "ask"), action("append_text", "value" to "continued")), ExecutionMode.STANDARD)
        assertEquals("continued", result.output); assertFalse(ActionOutputs.produces("end_shortcut")); assertFalse(ActionOutputs.produces("return"))
    }
    @Test fun directAndIndirectCyclesFailWithoutHangingTheEngine() = runTest {
        val a = flow(); val b = flow(call(a)); val recursiveA = a.copy(steps = listOf(call(b))); val backend = Backend(listOf(recursiveA, b)); val e = engine(backend)
        val result = e.run(recursiveA, ExecutionMode.STANDARD)
        assertEquals("err_call_cycle", (result.failure as Problem).key)
        assertEquals("fresh", e.run(flow(action("text", "value" to "fresh")), ExecutionMode.STANDARD).output)
    }
    @Test fun cancellationStopsTheWholeCallChainAndReleasesTheRootLock() = runTest {
        val child = flow(action("delay", "milliseconds" to "10000"), action("text", "value" to "must not run")); val e = engine(Backend(listOf(child)))
        val job = async { e.run(flow(call(child)), ExecutionMode.STANDARD) }; runCurrent(); job.cancelAndJoin(); assertTrue(job.isCancelled)
        assertEquals("ready", e.run(flow(action("text", "value" to "ready")), ExecutionMode.STANDARD).output)
    }
    @Test fun executionBudgetIsSharedAcrossRepeatedCalls() = runTest {
        val child = flow(*Array(51) { action("text", "value" to "x") })
        val loop = action("repeat", "count" to "1000").copy(branches = listOf(ActionBranch("body", steps = listOf(call(child)))))
        val result = engine(Backend(listOf(child))).run(flow(loop), ExecutionMode.STANDARD)
        assertEquals("err_step_limit", (result.failure as Problem).key)
    }
    @Test fun originConditionsFollowMagicAndNamedAliasesButNotLiteralReassignments() = runTest {
        val shared = action("share_input")
        val alias = action("create_variable", "name" to "alias", "value" to "{{@${shared.id}}}")
        val alias2 = action("set_variable", "name" to "alias2", "value" to "{{alias}}")
        val condition = action("if", "left" to "{{alias2}}", "operator" to "source_system_share").copy(branches = listOf(ActionBranch("then", steps = listOf(action("text", "value" to "system"))), ActionBranch("else", steps = listOf(action("text", "value" to "other")))))
        val source = flow(shared, alias, alias2, condition)
        assertTrue(InputProvenance.isSharedReference(source.steps, condition.id, "{{alias2}}"))
        assertFalse(InputProvenance.isSharedReference(source.steps, condition.id, "literal"))
        val e = engine(Backend())
        assertEquals("system", e.run(source, ExecutionMode.STANDARD, input = "payload", inputOrigin = InputOrigin.SYSTEM_SHARE).output)
        assertEquals("other", e.run(source, ExecutionMode.STANDARD, input = "payload", inputOrigin = InputOrigin.SHORTCUT).output)
        val replaced = source.copy(steps = source.steps.dropLast(1) + action("set_variable", "name" to "alias2", "value" to "literal") + condition)
        assertFalse(InputProvenance.isSharedReference(replaced.steps, condition.id, "{{alias2}}"))
        assertEquals("other", e.run(replaced, ExecutionMode.STANDARD, inputOrigin = InputOrigin.SYSTEM_SHARE).output)
    }
    @Test fun sourceConditionsWorkInsideCalledShortcutAfterAnAlias() = runTest {
        val shared = action("share_input"); val alias = action("get_variable", "name" to "@${shared.id}")
        val condition = action("if", "left" to "{{@${alias.id}}}", "operator" to "source_shortcut").copy(branches = listOf(ActionBranch("then", steps = listOf(action("return", "value" to "called"))), ActionBranch("else", steps = listOf(action("return", "value" to "system")))))
        val child = flow(shared, alias, condition)
        assertEquals("called", engine(Backend(listOf(child))).run(flow(call(child, "payload")), ExecutionMode.STANDARD).output)
    }
    @Test fun oldXmlMigratesAtomicallyWithoutChangingIdsContentOrTimestamps() = runTest {
        val directory = Files.createTempDirectory("shortcuts-migrate").toFile()
        try {
            val source = Shortcut(id = "legacy", name = "Legacy", steps = listOf(action("text", "value" to "keep")))
            val codec = XmlCodec(); val old = codec.encode(source).toString(Charsets.UTF_8).replace(Regex(" uuid=\"[^\"]+\"| appVersion=\"[^\"]+\""), "")
            val file = File(directory, "legacy.xml"); file.writeText(old)
            val repository = XmlShortcutRepository(directory); val migrated = repository.list().single()
            assertEquals(source, migrated); assertTrue(file.readText().contains("appVersion=\"10004\"")); assertFalse(codec.decodeMigrating(file.readBytes()).needsMigration)
            val bytes = file.readBytes(); repository.list(); assertArrayEquals(bytes, file.readBytes())
        } finally { directory.deleteRecursively() }
    }
    @Test fun futureVersionIsRejectedAndNeverRewritten() = runTest {
        val directory = Files.createTempDirectory("shortcuts-future").toFile()
        try {
            val source = flow(action("text", "value" to "keep")); val bytes = XmlCodec().encode(source).toString(Charsets.UTF_8).replace("appVersion=\"10004\"", "appVersion=\"10005\"").toByteArray()
            val file = File(directory, "${source.id}.xml"); file.writeBytes(bytes)
            try { XmlShortcutRepository(directory).list(); fail("Must reject newer version") } catch(e: FutureShortcutVersion) { assertEquals(10005, e.requiredVersion) }
            assertArrayEquals(bytes, file.readBytes())
            assertThrows(FutureShortcutVersion::class.java) { XmlCodec().decodeImport(bytes) }
        } finally { directory.deleteRecursively() }
    }
    @Test fun copyingAndImportingKeepUniqueUuidsAndRemapCallsInBundles() {
        val child = flow(action("return", "value" to "ok")); val parent = flow(call(child))
        val copies = ShortcutCopies.importCopies(listOf(parent, child), System.currentTimeMillis())
        assertNotEquals(parent.uuid, copies[0].uuid); assertEquals(copies[1].uuid, copies[0].steps.single().parameters["shortcutUuid"])
        assertNotEquals(parent.uuid, ShortcutCopies.duplicate(parent).uuid)
        assertEquals(copies, XmlCodec().decodeImport(XmlCodec().encodeBundle(copies)))
    }
    @Test fun aliasesFromEitherConditionalBranchRemainSelectable() {
        val share = action("share_input")
        val branches = action("if", "left" to "x", "right" to "x").copy(branches = listOf(
            ActionBranch("then", steps = listOf(action("set_variable", "name" to "alias", "value" to "{{@${share.id}}}"))),
            ActionBranch("else", steps = listOf(action("set_variable", "name" to "alias", "value" to "literal")))))
        val target = action("if", "left" to "{{alias}}")
        assertTrue(InputProvenance.isSharedReference(listOf(share, branches, target), target.id, "{{alias}}"))
        assertFalse(InputProvenance.isSharedReference(listOf(share, branches, action("set_variable", "name" to "alias", "value" to "cleared"), target), target.id, "{{alias}}"))
    }
    @Test fun nestedItemLoopsRestoreTheOuterItemsInputOrigin() = runTest {
        val inner = action("repeat_each", "source" to "{{input}}").copy(branches = listOf(ActionBranch("body")))
        val condition = action("if", "left" to "{{repeat_item}}", "operator" to "source_system_share").copy(branches = listOf(
            ActionBranch("then", steps = listOf(action("return", "value" to "system"))), ActionBranch("else", steps = listOf(action("return", "value" to "lost")))))
        val outer = action("repeat_each", "source" to "{{input}}").copy(branches = listOf(ActionBranch("body", steps = listOf(inner, condition))))
        val source = flow(action("share_input"), outer)
        assertTrue(InputProvenance.isSharedReference(source.steps, condition.id, "{{repeat_item}}"))
        val result = engine(Backend()).run(source, ExecutionMode.STANDARD, typedInput = Value.ListValue(listOf(Value.ListValue(listOf(Value.Text("item"))))), inputOrigin = InputOrigin.SYSTEM_SHARE)
        assertEquals(RunStatus.SUCCEEDED, result.status)
        assertEquals("system", result.output)
    }

}
