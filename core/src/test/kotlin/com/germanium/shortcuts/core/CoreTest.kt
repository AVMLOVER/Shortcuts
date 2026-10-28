package com.germanium.shortcuts.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val codec = XmlCodec()

    @Test fun xmlRoundTripPreservesUnicodeWhitespaceAndUnknownActions() {
        val original = Shortcut(name = "测试 & <Shortcuts> 🪄", steps = listOf(
            ActionStep(type = "future.plugin", parameters = mapOf("a\n\t\r\"" to "你好\n\t\r<&\"'"), enabled = false),
        ))
        assertEquals(original, codec.decode(codec.encode(original)))
    }
    @Test fun xmlRejectsFutureVersions() {
        assertThrows(InvalidDocument::class.java) { codec.decode(codec.encode(DemoShortcut.create()).toString(Charsets.UTF_8).replace("version=\"1\"", "version=\"99\"").toByteArray()) }
    }
    @Test fun xmlRejectsDtdAndExternalEntities() {
        assertThrows(InvalidDocument::class.java) { codec.decode("<!DOCTYPE shortcut [<!ENTITY x SYSTEM 'file:///private'>]><shortcut version=\"1\"/>".toByteArray()) }
    }
    @Test fun xmlRejectsOversizedMalformedAndInvalidUtf8() {
        listOf(ByteArray(XmlCodec.MAX_BYTES + 1), "<shortcut>".toByteArray(), byteArrayOf(0xC3.toByte(), 0x28)).forEach {
            assertThrows(InvalidDocument::class.java) { codec.decode(it) }
        }
    }
    @Test fun duplicateParametersAreRejected() {
        val source = codec.encode(Shortcut(name = "A", steps = listOf(ActionStep(type = "text", parameters = mapOf("value" to "a"))))).toString(Charsets.UTF_8)
        assertThrows(InvalidDocument::class.java) { codec.decode(source.replace("</action>", "<parameter key=\"value\">b</parameter></action>").toByteArray()) }
    }
    @Test fun settingsRoundTripEveryMode() {
        ExecutionMode.entries.forEach { assertEquals(AppSettings(it), codec.decodeSettings(codec.encode(AppSettings(it)))) }
    }
    @Test fun repositoryPersistsReplacesAndDeletes() = runTest {
        val directory = temporary.newFolder()
        val repository = XmlShortcutRepository(directory)
        val item = DemoShortcut.create()
        repository.save(item)
        assertEquals(listOf(item), XmlShortcutRepository(directory).list())
        val edited = item.copy(name = "修改", updatedAt = item.updatedAt + 1)
        repository.save(edited)
        assertEquals(listOf(edited), repository.list())
        assertEquals(1, directory.listFiles()!!.size)
        repository.delete(item.id)
        assertTrue(repository.list().isEmpty())
    }
    @Test fun failedSaveLeavesPreviousDocumentIntact() = runTest {
        val directory = temporary.newFolder()
        val repository = XmlShortcutRepository(directory)
        val item = DemoShortcut.create()
        repository.save(item)
        try { repository.save(item.copy(name = "invalid\u0000")); fail("Expected failure") } catch (_: IllegalArgumentException) { }
        assertEquals(listOf(item), repository.list())
    }
    @Test fun repositoryRejectsPathTraversal() = runTest {
        try { XmlShortcutRepository(temporary.newFolder()).delete("../settings"); fail("Expected failure") } catch (_: IllegalArgumentException) { }
    }
    @Test fun corruptDataIsReportedAndNotOverwritten() = runTest {
        val file = File(temporary.newFolder(), "settings.xml").apply { writeText("broken") }
        try { XmlSettingsRepository(file).load(); fail("Expected failure") } catch (_: InvalidDocument) { }
        assertEquals("broken", file.readText())
    }
    @Test fun missingSettingsDefaultToStandardAndPersist() = runTest {
        val file = File(temporary.newFolder(), "settings.xml")
        val repository = XmlSettingsRepository(file)
        assertEquals(ExecutionMode.STANDARD, repository.load().mode)
        repository.save(AppSettings(ExecutionMode.SHIZUKU))
        assertEquals(ExecutionMode.SHIZUKU, XmlSettingsRepository(file).load().mode)
    }
    @Test fun enginePassesOutputInOrder() = runTest {
        val result = ShortcutEngine().run(DemoShortcut.create(), ExecutionMode.STANDARD)
        assertEquals(RunStatus.SUCCEEDED, result.status)
        assertEquals(4, result.steps.size)
        assertEquals("Hello, Shortcuts!\nThe workflow engine is ready.", result.output)
    }
    @Test fun validationHappensBeforeAnySideEffect() = runTest {
        var executed = false
        val registry = ActionRegistry(listOf(object : ActionHandler {
            override val type = "effect"
            override fun validate(parameters: Map<String, String>) = Unit
            override suspend fun execute(parameters: Map<String, String>, context: RunContext): Value { executed = true; return Value.Text("") }
        }))
        val result = ShortcutEngine(registry).run(Shortcut(name = "A", steps = listOf(ActionStep(type = "effect"), ActionStep(type = "unknown"))), ExecutionMode.STANDARD)
        assertEquals(RunStatus.FAILED, result.status)
        assertFalse(executed)
        assertTrue(result.steps.isEmpty())
    }
    @Test fun disabledUnknownActionsAreSkipped() = runTest {
        val result = ShortcutEngine().run(Shortcut(name = "A", steps = listOf(ActionStep(type = "unknown", enabled = false))), ExecutionMode.STANDARD)
        assertEquals(RunStatus.SUCCEEDED, result.status)
    }
    @Test fun invalidDelayFailsPreflight() = runTest {
        val result = ShortcutEngine().run(Shortcut(name = "A", steps = listOf(ActionStep(type = "delay", parameters = mapOf("milliseconds" to "-1")))), ExecutionMode.STANDARD)
        assertEquals(RunStatus.FAILED, result.status)
    }
    @Test fun cancellationStopsRunAndReleasesLock() = runTest {
        val engine = ShortcutEngine()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.run(DemoShortcut.create(), ExecutionMode.STANDARD) }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(RunStatus.SUCCEEDED, engine.run(Shortcut(name = "empty"), ExecutionMode.STANDARD).status)
    }
    @Test fun concurrentRunsAreRejected() = runTest {
        val engine = ShortcutEngine()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.run(DemoShortcut.create(), ExecutionMode.STANDARD) }
        try { engine.run(DemoShortcut.create(), ExecutionMode.STANDARD); fail("Expected failure") } catch (_: IllegalStateException) { }
        job.cancelAndJoin()
    }
}
