package com.germanium.shortcuts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID

data class HomeState(val loading: Boolean = true, val items: List<Shortcut> = emptyList(), val settings: AppSettings = AppSettings(),
    val privilege: PrivilegeStatus = PrivilegeStatus(ExecutionMode.STANDARD, true, "standard_ready"), val runningId: String? = null,
    val result: RunResult? = null, val error: String? = null, val notice: String? = null, val storageReady: Boolean = false,
    val authorizing: Boolean = false, val saving: Boolean = false, val currentStepId: String? = null,
    val locationEnabled: Boolean = false, val cameraEnabled: Boolean = false, val calendarReadEnabled: Boolean = false, val calendarWriteEnabled: Boolean = false, val albumStatus: String = "not_allowed", val overlayEnabled: Boolean = false, val notificationsEnabled: Boolean = false, val batteryExempt: Boolean = false, val resultShortcutId: String? = null, val shellStdout: String = "", val shellStderr: String = "", val folders: List<ShortcutFolder> = emptyList())
data class EditorState(val original: Shortcut, val name: String = original.name, val steps: List<ActionStep> = original.steps, val icon: String = original.icon, val color: String = original.color, val layoutVersion: Int = 0, val referenceVersion: Int = 0, val canUndo: Boolean = false, val canRedo: Boolean = false) {
    val dirty get() = name != original.name || steps != original.steps || icon != original.icon || color != original.color
    fun shortcut() = original.copy(name = name.trim(), steps = steps, icon = icon, color = color, updatedAt = maxOf(original.updatedAt, System.currentTimeMillis()))
}
private data class EditorSnapshot(val name: String, val steps: List<ActionStep>, val icon: String, val color: String)
private fun EditorState.snapshot() = EditorSnapshot(name, steps, icon, color)

class ShortcutsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ShortcutsApplication
    private val mutable = MutableStateFlow(HomeState())
    val state = mutable.asStateFlow()
    private val mutableEditor = MutableStateFlow<EditorState?>(null)
    val editor = mutableEditor.asStateFlow()
    var onCloseEditor: (() -> Unit)? = null
    private var mutationJob: Job? = null
    private var draftJob: Job? = null
    private val history = EditHistory<EditorSnapshot>()
    init {
        reload()
        viewModelScope.launch { app.runs.state.collect { run -> mutable.update { old ->
            val relevant = editor.value == null || editor.value?.original?.id == run.shortcutId
            val removed = old.resultShortcutId != null && run.shortcutId == null
            old.copy(runningId = run.runningId, currentStepId = run.currentStep, shellStdout = run.shellStdout, shellStderr = run.shellStderr,
                result = run.result.takeIf { relevant }, resultShortcutId = run.shortcutId.takeIf { relevant },
                error = if (removed) null else run.error ?: if (old.runningId == null && run.runningId != null) null else old.error,
                notice = if (removed) null else run.notice ?: old.notice)
        } } }
    }
    fun clearError() { app.runs.clear(); mutable.update { it.copy(error = null, notice = null) } }
    fun report(error: Throwable) { app.diagnostics.trace("operation_failed", error); mutable.update { it.copy(error = app.describe(error)) } }
    fun notice(key: String) { mutable.update { it.copy(notice = app.tr(key)) } }
    fun reload() {
        if (mutationJob?.isActive == true) return
        mutationJob = viewModelScope.launch {
            try { val items = app.shortcuts.list(); val folders = app.folders.list(); val settings = app.settings.load(); mutable.update { it.copy(items = items, folders = folders, settings = settings, storageReady = true,
                locationEnabled = AndroidLocation.hasPermission(app), cameraEnabled = MediaPermissions.camera(app), calendarReadEnabled = MediaPermissions.granted(app, android.Manifest.permission.READ_CALENDAR), calendarWriteEnabled = MediaPermissions.granted(app, android.Manifest.permission.WRITE_CALENDAR), albumStatus = MediaPermissions.albumStatus(app), overlayEnabled = android.provider.Settings.canDrawOverlays(app), notificationsEnabled = androidx.core.app.NotificationManagerCompat.from(app).areNotificationsEnabled(), batteryExempt = app.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)) }; refreshPrivileges()
            } catch (e: Exception) { if (e is CancellationException) throw e; report(e); mutable.update { it.copy(storageReady = false) }
            } finally { mutable.update { it.copy(loading = false) } }
        }
    }
    fun openEditor(id: String, template: String?) {
        if (editor.value != null) return
        viewModelScope.launch {
            try {
                val existing = app.shortcuts.list().find { it.id == id }
                val sample = WorkflowExamples.dictionaryCalculation()
                val original = existing ?: if (template == "dictionary") sample.copy(id = id, uuid = stableShortcutUuid(id), name = app.tr("examples"), steps = sample.steps.map { if (it.type == "text") it.copy(parameters = it.parameters + ("value" to app.tr("example_total"))) else it }) else Shortcut(id = id, name = app.tr("new_shortcut"))
                val draftFile = draftFile(id)
                val restored = withContext(Dispatchers.IO) { if (draftFile.isFile) XmlCodec().decode(draftFile.inputStream().use { it.readBytesBounded(XmlCodec.MAX_BYTES) }) else null }
                history.clear()
                mutableEditor.value = EditorState(original, restored?.name ?: original.name, restored?.steps ?: original.steps, restored?.icon ?: original.icon, restored?.color ?: original.color)
                mutable.update { it.copy(result = app.runs.state.value.result.takeIf { app.runs.state.value.shortcutId == id }) }; app.diagnostics.trace("editor_open id=$id")
            } catch (e: Exception) { if (e is CancellationException) throw e; report(e) }
        }
    }
    private fun draftFile(id: String): File { require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))); return File(app.filesDir, "drafts/$id.xml") }
    private fun persistDraft() {
        draftJob?.cancel(); val draft = editor.value ?: return
        draftJob = viewModelScope.launch {
            delay(400)
            runCatching { val snapshot = withContext(Dispatchers.Default) { draft.shortcut() }; XmlShortcutRepository(File(app.filesDir, "drafts")).save(snapshot) }.onFailure { app.diagnostics.trace("draft_save_failed", it) }
        }
    }
    private fun changeEditor(previous: EditorState, next: EditorState, group: String? = null) {
        if (previous.snapshot() == next.snapshot()) return
        history.record(previous.snapshot(), group)
        invalidateResult()
        mutableEditor.value = next.copy(canUndo = history.canUndo, canRedo = history.canRedo)
        persistDraft()
    }
    private fun restoreHistory(redo: Boolean) {
        if (state.value.runningId != null || state.value.saving) return
        val draft = editor.value ?: return
        val snapshot = (if (redo) history.redo(draft.snapshot()) else history.undo(draft.snapshot())) ?: return
        invalidateResult()
        mutableEditor.value = draft.copy(name = snapshot.name, steps = snapshot.steps, icon = snapshot.icon, color = snapshot.color,
            layoutVersion = draft.layoutVersion + 1, referenceVersion = draft.referenceVersion + 1, canUndo = history.canUndo, canRedo = history.canRedo)
        persistDraft()
    }
    fun undo() = restoreHistory(false)
    fun redo() = restoreHistory(true)
    fun editName(value: String) { if (value.length <= 200) editor.value?.let { changeEditor(it, it.copy(name = value), "name") } }
    fun editAppearance(icon: String, color: String) { editor.value?.let { changeEditor(it, it.copy(icon = icon, color = color)) } }
    fun editSteps(steps: List<ActionStep>) {
        try {
            require(allSteps(steps).size <= MAX_WORKFLOW_ACTIONS) { throw Problem("err_size") }
            val draft = editor.value ?: return
            changeEditor(draft, draft.copy(steps = steps, layoutVersion = draft.layoutVersion + 1, referenceVersion = draft.referenceVersion + 1))
        } catch (e: Exception) { report(if (e is Problem) e else Problem("err_size")) }
    }
    fun update(step: ActionStep) {
        invalidateResult()
        val draft = editor.value ?: return
        val previous = TreeEdits.pathTo(draft.steps, step.id)?.lastOrNull() ?: return
        val updated = TreeEdits.update(draft.steps, step.id) { step }
        if (previous.branches === step.branches && previous.enabled == step.enabled && previous.type == step.type) {
            val renamed = previous.parameters["name"] != step.parameters["name"] || previous.parameters["resultVariable"] != step.parameters["resultVariable"]
            val changedFields = (previous.parameters.keys + step.parameters.keys).filter { previous.parameters[it] != step.parameters[it] }
            changeEditor(draft, draft.copy(steps = updated, referenceVersion = draft.referenceVersion + if (renamed) 1 else 0),
                changedFields.singleOrNull()?.let { "${step.id}:$it" })
        } else editSteps(updated)
    }
    private fun invalidateResult() {
        val id = editor.value?.original?.id ?: return
        app.runs.invalidate(id)
        mutable.update { if (it.resultShortcutId == id && it.runningId == null) it.copy(result = null, resultShortcutId = null, currentStepId = null, error = null) else it }
    }
    fun addVariableAfter(id: String) {
        val draft = editor.value ?: return
        val names = allSteps(draft.steps).mapNotNull { it.parameters["name"] ?: it.parameters["resultVariable"] }.toSet()
        val name = generateSequence(1) { it + 1 }.map { "variable$it" }.first { it !in names }
        val step = ActionStep(type = "create_variable", parameters = mapOf("name" to name, "valueType" to "auto", "value" to "{{@$id}}"))
        editSteps(TreeEdits.insertAfter(draft.steps, id, step))
    }
    fun selectApp(selected: LaunchableApp, owner: String?, branch: String?, reselect: String?, anchor: String? = null, before: Boolean = false) {
        val parameters = mapOf("packageName" to selected.packageName, "className" to selected.className, "displayName" to selected.label)
        if (reselect == null) { editor.value?.let { val step = ActionStep(type = "open_app", parameters = parameters); editSteps(if (anchor == null) TreeEdits.add(it.steps, owner, branch, step) else if (before) TreeEdits.insertBefore(it.steps, anchor, step) else TreeEdits.insertAfter(it.steps, anchor, step)) } }
        else editor.value?.let { draft -> allSteps(draft.steps).find { it.id == reselect }?.let { update(it.copy(parameters = parameters)) } }
    }
    fun remove(id: String) { editor.value?.let { editSteps(TreeEdits.remove(it.steps, id)) } }
    fun move(id: String, delta: Int) { editor.value?.let { editSteps(TreeEdits.move(it.steps, id, delta)) } }
    fun place(id: String, targetId: String, before: Boolean, branch: String? = null) { editor.value?.let { editSteps(if (branch == null) TreeEdits.place(it.steps, id, targetId, before) else TreeEdits.placeInBranch(it.steps, id, targetId, branch)) } }
    fun add(type: String, owner: String?, branch: String?, uri: String? = null, displayName: String? = null, anchor: String? = null, before: Boolean = false) {
        val definition = ActionCatalog.find(type) ?: return
        var step = definition.create(app)
        if (type == "create_variable") {
            val names = editor.value?.let { allSteps(it.steps).mapNotNull { it.parameters["name"] ?: it.parameters["resultVariable"] }.toSet() }.orEmpty()
            val name = generateSequence(1) { it + 1 }.map { "variable$it" }.first { it !in names }
            step = step.copy(parameters = step.parameters + ("name" to name))
        }
        if (type == "ask_input") {
            val names = editor.value?.let { allSteps(it.steps).mapNotNull { step -> step.parameters["resultVariable"] ?: step.parameters["name"] }.toSet() }.orEmpty()
            val name = generateSequence(1) { it + 1 }.map { "input_$it" }.first { it !in names }
            step = step.copy(parameters = step.parameters + mapOf("title" to app.tr("input_title"), "prompt" to app.tr("input_placeholder"), "resultVariable" to name))
        }
        if (type == "menu") step = step.copy(parameters = step.parameters + ("title" to app.tr("menu_title")))
        if (type == "install_apk") step = step.copy(parameters = mapOf("uri" to uri.orEmpty(), "displayName" to displayName.orEmpty()))
        editor.value?.let { editSteps(if (anchor == null) TreeEdits.add(it.steps, owner, branch, step) else if (before) TreeEdits.insertBefore(it.steps, anchor, step) else TreeEdits.insertAfter(it.steps, anchor, step)) }
    }
    fun selectApk(uri: android.net.Uri, owner: String?, branch: String?, reselect: String?, anchor: String? = null, before: Boolean = false) {
        viewModelScope.launch {
            try {
                val filename = withContext(Dispatchers.IO) {
                    app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "APK"
                }
                if (!filename.endsWith(".apk", true)) throw Problem("err_apk")
                if (reselect == null) add("install_apk", owner, branch, uri.toString(), filename, anchor, before)
                else editor.value?.let { draft -> allSteps(draft.steps).find { it.id == reselect }?.let { update(it.copy(parameters = it.parameters + mapOf("uri" to uri.toString(), "displayName" to filename))) } }
            } catch (e: Exception) { if (e is CancellationException) throw e; report(e) }
        }
    }
    fun closeEditor() {
        val draft = editor.value
        viewModelScope.launch {
            if (app.runs.state.value.runningId == draft?.original?.id) app.runs.cancelAndJoin(); draftJob?.cancelAndJoin()
            if (draft != null) withContext(Dispatchers.IO) { draftFile(draft.original.id).delete() }
            // Keep the final editor frame during the Activity's exit transition.
            val close = onCloseEditor
            if (close != null) close() else mutableEditor.value = null
        }
    }
    fun saveEditor(close: Boolean = true) {
        val draft = editor.value ?: return
        if (draft.steps.isEmpty()) {
            mutate {
                val id = draft.original.id
                app.shortcuts.delete(id); app.runs.forget(id); ShortcutTiles.remove(app, id); ShortcutWidgets.updateAll(app)
                val items = app.shortcuts.list()
                mutable.update { it.copy(items = items, result = null, resultShortcutId = null, currentStepId = null, error = null, notice = null) }
                closeEditor()
            }
            return
        }
        try { val shortcut = draft.shortcut(); app.engine.validate(shortcut); save(shortcut) { if (close) closeEditor() else { history.clear(); mutableEditor.value = EditorState(shortcut) } }
        } catch (e: Exception) { report(e) }
    }
    fun runEditor() { try { editor.value?.shortcut()?.let { run(it) } } catch (e: Exception) { report(e) } }
    fun run(shortcut: Shortcut, preview: Boolean = false) {
        if (state.value.saving) return
        clearError()
        if (app.runs.start(shortcut, state.value.settings.mode, preview) == null) report(Problem("err_busy"))
    }
    fun cancelRun() { app.runs.cancel() }
    fun save(shortcut: Shortcut, onSaved: () -> Unit = {}) = mutate { require(shortcut.steps.isNotEmpty()) { throw Problem("err_empty_shortcut") }; app.shortcuts.save(shortcut); val items = app.shortcuts.list(); mutable.update { it.copy(items = items) }; ShortcutWidgets.updateAll(app); onSaved() }
    fun importShortcuts(shortcuts: List<Shortcut>) = mutate {
        val now = System.currentTimeMillis()
        val copies = ShortcutCopies.importCopies(shortcuts.filter { it.steps.isNotEmpty() }, now)
        require(copies.isNotEmpty()) { throw Problem("err_empty_shortcut") }
        app.shortcuts.importNew(copies)
        val items = app.shortcuts.list()
        mutable.update { it.copy(items = items, notice = app.tr("imported")) }
    }
    fun delete(id: String) = mutate {
        app.shortcuts.delete(id)
        ShortcutTiles.remove(app, id)
        ShortcutWidgets.updateAll(app)
        app.runs.forget(id)
        val items = app.shortcuts.list()
        mutable.update { if (it.resultShortcutId == id) it.copy(items = items, result = null, resultShortcutId = null, currentStepId = null, error = null, notice = null) else it.copy(items = items) }
    }
    fun duplicate(shortcut: Shortcut) = mutate {
        val original = app.shortcuts.list().firstOrNull { it.id == shortcut.id } ?: return@mutate
        app.shortcuts.save(ShortcutCopies.duplicate(original))
        val items = app.shortcuts.list()
        mutable.update { it.copy(items = items) }
        ShortcutWidgets.updateAll(app)
    }
    fun selectMode(mode: ExecutionMode) = mutate { val settings = state.value.settings.copy(mode = mode); app.settings.save(settings); mutable.update { it.copy(settings = settings) }; refreshPrivileges() }
    fun saveFolder(folder: ShortcutFolder) = mutate { app.folders.save(folder); val folders = app.folders.list(); mutable.update { it.copy(folders = folders) } }
    fun moveToFolder(shortcut: Shortcut, folderId: String) = mutate {
        require(folderId.isBlank() || app.folders.list().any { it.id == folderId }) { throw Problem("err_invalid_data") }
        app.shortcuts.save(shortcut.copy(folderId = folderId, updatedAt = maxOf(shortcut.updatedAt, System.currentTimeMillis())))
        val items = app.shortcuts.list(); mutable.update { it.copy(items = items) }
    }
    fun selectAngleUnit(unit: AngleUnit) = mutate { val settings = state.value.settings.copy(angleUnit = unit); app.settings.save(settings); mutable.update { it.copy(settings = settings) } }
    fun selectTheme(theme: ThemeMode) = mutate { val settings = state.value.settings.copy(theme = theme); app.settings.save(settings); mutable.update { it.copy(settings = settings) } }
    fun refreshPrivileges() {
        val status = when (state.value.settings.mode) {
            ExecutionMode.STANDARD -> PrivilegeStatus(ExecutionMode.STANDARD, true, "standard_ready")
            ExecutionMode.ROOT -> PrivilegeStatus(ExecutionMode.ROOT, false, "root_check")
            ExecutionMode.SHIZUKU -> app.privileges.shizukuStatus()
        }; mutable.update { it.copy(privilege = status) }
    }
    fun authorize() {
        if (state.value.authorizing) return
        viewModelScope.launch {
            mutable.update { it.copy(authorizing = true) }
            try { when (state.value.settings.mode) {
                ExecutionMode.ROOT -> { val status = app.privileges.requestRoot(); mutable.update { it.copy(privilege = status) } }
                ExecutionMode.SHIZUKU -> app.privileges.requestShizukuPermission()
                else -> Unit
            } } catch (e: Exception) { if (e is CancellationException) throw e; report(e) }
            finally { mutable.update { it.copy(authorizing = false) } }
        }
    }
    private fun mutate(block: suspend () -> Unit) {
        if (!state.value.storageReady || mutationJob?.isActive == true) return
        mutationJob = viewModelScope.launch {
            mutable.update { it.copy(saving = true, error = null) }
            try { block() } catch (e: Exception) { if (e is CancellationException) throw e; report(e) }
            finally { mutable.update { it.copy(saving = false) }; app.updateSharingTargets() }
        }
    }
}
