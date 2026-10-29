package com.germanium.shortcuts

import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class RunState(val runningId: String? = null, val shortcutId: String? = runningId, val name: String = "", val currentStep: String? = null, val result: RunResult? = null, val error: String? = null, val notice: String? = null, val shellStdout: String = "", val shellStderr: String = "", val fromWidget: Boolean = false, val previewOutput: String? = null, val previewValue: Value? = null)
class RunCoordinator(private val app: ShortcutsApplication) {
    private val mutable = MutableStateFlow(RunState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun start(shortcut: Shortcut, mode: ExecutionMode, preview: Boolean = false, fromWidget: Boolean = false, input: Value? = null, inputOrigin: InputOrigin = InputOrigin.NONE): Job? {
        if (job?.isActive == true) return null
        job = app.scope.launch {
            mutable.value = RunState(runningId = shortcut.id, name = shortcut.name, fromWidget = fromWidget)
            val token = java.util.UUID.randomUUID().toString()
            try {
                val result = withContext(Dispatchers.Default) { app.engine.run(shortcut, mode, typedInput = input, inputOrigin = inputOrigin, angleUnit = app.settings.load().angleUnit, onStep = { id -> mutable.update { it.copy(currentStep = id) }; app.diagnostics.activeStep = "$token/${shortcut.id}/$id" }) }
                mutable.update { it.copy(result = result, error = result.failure?.let(app::describe)) }
                if (preview && result.status == RunStatus.SUCCEEDED && result.steps.none { it.type == "quick_look" }) app.actions.quickLook(result.outputValue)
            } catch (e: CancellationException) { mutable.update { it.copy(notice = app.tr("run_stopped")) }; throw e
            } catch (e: Exception) { app.diagnostics.trace("run_failed", e); mutable.update { it.copy(error = app.describe(e)) }
            } finally { mutable.update { it.copy(runningId = null, currentStep = null) }; app.diagnostics.activeStep = "none" }
        }; return job
    }
    fun cancel() { job?.cancel() }
    fun shellOutput(stdout: String, stderr: String) { mutable.update { if (it.runningId != null) it.copy(shellStdout = stdout.takeLast(32768), shellStderr = stderr.takeLast(32768)) else it } }
    fun widgetPreview(value: Value) { mutable.update { it.copy(previewOutput = value.text(), previewValue = value) } }
    fun invalidate(shortcutId: String) { mutable.update { if (it.shortcutId == shortcutId && it.runningId == null) RunState() else it } }
    suspend fun cancelAndJoin() { job?.cancelAndJoin() }
    suspend fun forget(shortcutId: String) {
        if (mutable.value.shortcutId != shortcutId) return
        if (mutable.value.runningId == shortcutId) job?.cancelAndJoin()
        mutable.update { if (it.shortcutId == shortcutId) RunState() else it }
    }
    fun clear() { mutable.update { it.copy(error = null, notice = null) } }
}
