package com.germanium.shortcuts

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*

private sealed class EditorRow(val key: String, val depth: Int) {
    class Action(val step: ActionStep, val index: Int, val size: Int, val scope: DragScope, depth: Int) : EditorRow("action:${step.id}", depth)
    class Branch(val owner: ActionStep, val branch: ActionBranch, depth: Int) : EditorRow("branch:${owner.id}:${branch.id}", depth)
    class Footer(val owner: ActionStep, val branch: ActionBranch, depth: Int) : EditorRow("add:${owner.id}:${branch.id}", depth)
    class End(val step: ActionStep, depth: Int) : EditorRow("end:${step.id}", depth)
}
private fun editorRows(steps: List<ActionStep>, depth: Int = 0, owner: String? = null, branchId: String? = null): List<EditorRow> = steps.flatMapIndexed { index, step ->
    listOf(EditorRow.Action(step, index, steps.size, DragScope(owner, branchId), depth)) + step.branches.flatMap { branch ->
        listOf(EditorRow.Branch(step, branch, depth + 1)) + editorRows(branch.steps, depth + 1, step.id, branch.id) + listOf(EditorRow.Footer(step, branch, depth + 1))
    } + if (step.branches.isNotEmpty()) listOf(EditorRow.End(step, depth)) else emptyList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ShortcutEditorScreen(editor: EditorState, state: HomeState, model: ShortcutsViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transfers = rememberTransfers(model)
    var library by rememberSaveable { mutableStateOf(false) }
    var insertionAnchor by rememberSaveable { mutableStateOf<String?>(null) }
    var insertionBefore by rememberSaveable { mutableStateOf(false) }
    var libraryDragging by remember { mutableStateOf(false) }
    var libraryPointer by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var libraryDropTarget by remember { mutableStateOf<String?>(null) }
    var libraryDestination by remember { mutableStateOf<DragDestination?>(null) }
    var libraryFooterTarget by remember { mutableStateOf<String?>(null) }
    val libraryFooters = remember { mutableMapOf<String, Pair<DragScope, Rect>>() }
    val editorView = androidx.compose.ui.platform.LocalView.current
    var targetOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var targetBranch by rememberSaveable { mutableStateOf<String?>(null) }
    var reselect by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf<Pair<String, String>?>(null) }
    var appearancePicker by rememberSaveable { mutableStateOf(false) }
    var choosingOutput by rememberSaveable { mutableStateOf(false) }
    var originIndex by remember { mutableIntStateOf(0) }
    var originOffset by remember { mutableIntStateOf(0) }
    var revealedId by remember { mutableStateOf<String?>(null) }
    val variables = remember { VariableSession() }
    var apps by rememberSaveable { mutableStateOf(false) }
    val flatSteps = remember(editor.steps) { allSteps(editor.steps) }
    val indices = remember(flatSteps) { flatSteps.withIndex().associate { it.value.id to it.index } }
    val byId = remember(flatSteps) { flatSteps.associateBy { it.id } }
    val rows = remember(editor.original.id, editor.layoutVersion) { editorRows(editor.steps) }
    val listState = rememberLazyListState()
    val drag = remember { EditorDragState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val availableIds = remember(editor.layoutVersion, focused?.first) { TreeEdits.availableBefore(editor.steps, focused?.first.orEmpty()).map { it.id }.toSet() }
    val available = availableIds.mapNotNull(byId::get)
    val loopNames = remember(editor.layoutVersion, focused?.first) { TreeEdits.pathTo(editor.steps, focused?.first.orEmpty()).orEmpty().dropLast(1).flatMap { when (it.type) { "repeat_each" -> listOf("repeat_item", "repeat_index"); "repeat" -> listOf("repeat_index"); else -> emptyList() } }.distinct() }
    val configuration = LocalConfiguration.current
    val dragTitles = remember(editor.layoutVersion, configuration) { rows.filterIsInstance<EditorRow.Action>().associate { row -> row.step.id to DragItem(row.step.id, row.scope, "${(indices[row.step.id] ?: 0) + 1}. " + (ActionCatalog.forStep(row.step)?.let { context.tr(it.title) } ?: row.step.type)) } }
    val referenceNames = remember(editor.layoutVersion, editor.referenceVersion, configuration) { buildMap {
        put("input", context.tr("previous_result"))
        flatSteps.forEachIndexed { index, step ->
            val label = step.parameters["resultVariable"]?.takeIf(String::isNotBlank) ?: "${index + 1}. " + (ActionCatalog.forStep(step)?.let { context.tr(it.title) } ?: step.type)
            put("@${step.id}", label)
            ActionOutputs.properties(step.type).forEach { property -> put("@${step.id}.$property", "$label · ${context.tr("${if (step.type == "current_location") "location" else "image"}_prop_${property.lowercase(java.util.Locale.ROOT)}")}") }
        }
    } }
    val observed = remember(state.result) { state.result?.steps.orEmpty().associateBy { it.stepId } }
    LaunchedEffect(revealedId) { if (revealedId != null) { delay(1800); revealedId = null } }
    SideEffect { drag.items = dragTitles }
    DragAutoScroll(drag, listState)
    val busy = state.runningId != null || state.saving
    LaunchedEffect(busy) { if (busy) { variables.inspected = null; choosingOutput = false } }
    fun restoreInput() {
        choosingOutput = false
        scope.launch { withFrameNanos { }; listState.scrollToItem(originIndex.coerceAtMost((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)), originOffset); variables.pendingFocus = variables.active?.id }
    }
    fun beginChoosing() { if (focused == null) return; originIndex = listState.firstVisibleItemIndex; originOffset = listState.firstVisibleItemScrollOffset; keyboard?.hide(); focusManager.clearFocus(); variables.inspected = null; choosingOutput = true }
    fun leave() { if (choosingOutput) { restoreInput(); return }; if (variables.inspected != null) { variables.inspected = null; return }; if (busy) { model.cancelRun(); return }; if (editor.dirty) confirmLeave = true else model.closeEditor() }
    fun parameter(step: ActionStep, key: String, value: String) = model.update(step.copy(parameters = step.parameters + (key to value)))
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val selectedId = reselect; val owner = targetOwner; val branch = targetBranch
        if (uri != null) model.selectApk(uri, owner, branch, selectedId, insertionAnchor, insertionBefore)
        insertionAnchor = null
        reselect = null
    }
    fun chooseApk(id: String? = null) { reselect = id; runCatching { apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }.onFailure(model::report) }
    fun insertReference(name: String) { variables.insert("{{$name}}") }
    fun chooseOutput(id: String) { insertReference("@$id"); restoreInput() }
    fun openLibrary(owner: String? = null, branch: String? = null) { insertionAnchor = null; targetOwner = owner; targetBranch = branch; library = true }
    fun addFromLibrary(definition: ActionDefinition) {
        library = false
        if (definition.type == "install_apk") chooseApk()
        else if (definition.type == "open_app") { reselect = null; apps = true }
        else { model.add(definition.type, targetOwner, targetBranch, anchor = insertionAnchor, before = insertionBefore); insertionAnchor = null }
    }
    val dropAction by rememberUpdatedState<(String, androidx.compose.ui.geometry.Offset) -> Unit> { type, point ->
        val footer = libraryFooters[libraryFooterTarget] ?: libraryFooters.values.firstOrNull { it.second.contains(point) }
        val nearest = drag.handles.entries.minByOrNull { kotlin.math.abs(it.value.center.y - point.y) }
        if (footer != null) { targetOwner = footer.first.owner; targetBranch = footer.first.branch; insertionAnchor = null }
        else { insertionAnchor = libraryDestination?.target ?: nearest?.key; insertionBefore = libraryDestination?.before ?: (nearest?.let { point.y < it.value.center.y } ?: false); targetOwner = null; targetBranch = null }
        ActionCatalog.find(type)?.let(::addFromLibrary)
    }
    DisposableEffect(editorView, editor.original.id) {
        editorView.setOnDragListener { _, event ->
            val token = LibraryDragSession.active
            if (token == null) false else when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> { val accepted = event.clipDescription?.hasMimeType("application/vnd.germanium.shortcuts.action") == true; if (accepted) { libraryPointer = androidx.compose.ui.geometry.Offset(0f, drag.height / 2); libraryDestination = null; libraryFooterTarget = null; drag.gapBounds = null; libraryDragging = true }; accepted }
                android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                    libraryPointer = androidx.compose.ui.geometry.Offset(event.x, event.y) - drag.origin
                    if (drag.overDelete(libraryPointer)) { libraryDestination = null; libraryFooterTarget = null; libraryDropTarget = null }
                    else if (drag.gapBounds?.contains(libraryPointer) != true) {
                        libraryFooterTarget = libraryFooters.entries.firstOrNull { it.value.second.contains(libraryPointer) }?.key
                        libraryDestination = if (libraryFooterTarget != null) null else drag.handles.entries.minByOrNull { kotlin.math.abs(it.value.center.y - libraryPointer.y) }?.let { DragDestination("library", it.key, libraryPointer.y < it.value.center.y) }
                        libraryDropTarget = libraryDestination?.target
                    }
                    true
                }
                android.view.DragEvent.ACTION_DROP -> { if (LibraryDragSession.matches(event.clipData)) { val point = androidx.compose.ui.geometry.Offset(event.x, event.y) - drag.origin; if (drag.overDelete(point)) library = false else dropAction(token.type, point); true } else false }
                android.view.DragEvent.ACTION_DRAG_ENDED -> { libraryDragging = false; libraryDropTarget = null; libraryDestination = null; libraryFooterTarget = null; drag.gapBounds = null; drag.deleteBounds = null; LibraryDragSession.clear(); true }
                else -> true
            }
        }
        onDispose { editorView.setOnDragListener(null) }
    }
    LaunchedEffect(libraryDragging) {
        var previous = 0L
        while (libraryDragging) {
            withFrameNanos { now ->
                val elapsed = if (previous == 0L) 0f else ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f)
                previous = now
                val speed = if (drag.overDelete(libraryPointer)) 0f else when { libraryPointer.y < 160f -> -1000f; libraryPointer.y > drag.height - 160f -> 1000f; else -> 0f }
                speed * elapsed
            }.let { if (it != 0f) listState.scrollBy(it) }
        }
    }
    BackHandler { leave() }
    val draggingActions = drag.source != null || libraryDragging
    Box(Modifier.fillMaxSize()) {
    Scaffold(modifier = Modifier.fillMaxSize().testTag("shortcut_editor_fullscreen"), topBar = {
        TopAppBar(title = {
            if (draggingActions) DragDeleteTarget(drag.overDelete(if (libraryDragging) libraryPointer else drag.pointer), Modifier.onGloballyPositioned { drag.deleteBounds = Rect(it.positionInRoot(), it.size.toSize()) })
            else Text(t(if (choosingOutput) "select_output" else "edit_shortcut"))
        }, navigationIcon = { if (!draggingActions) IconButton(onClick = ::leave) { AppIcon(R.drawable.ic_back, description = t("back")) } }, actions = {
            if (!draggingActions) {
            if (choosingOutput) TextButton(onClick = ::restoreInput) { Text(t("cancel")) } else {
            IconButton(onClick = { try { transfers.export(editor.shortcut()) } catch (e: Exception) { model.report(e) } }, enabled = !busy) { AppIcon(R.drawable.ic_export, description = t("export")) }
            TextButton(onClick = { model.saveEditor() }, enabled = !busy && state.storageReady && editor.name.isNotBlank(), modifier = Modifier.testTag("save_shortcut")) { Text(t(if (state.saving) "saving" else "done")) }
            }
            }
        })
    }, bottomBar = {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 6.dp, shadowElevation = 6.dp) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val inspected = variables.inspected
            if (inspected != null && !choosingOutput) {
                val reference = inspected.token.name
                val source = if (reference.startsWith("@")) byId[reference.drop(1).substringBefore('.')] else if (reference == "input") available.lastOrNull() else available.lastOrNull { it.parameters["resultVariable"] == reference || it.type in listOf("create_variable", "set_variable") && it.parameters["name"] == reference }
                VariableInspector(referenceNames[reference] ?: reference, source?.let { observed[it.id]?.output }, replace = ::beginChoosing,
                    remove = { variables.removeInspected() }, close = { variables.inspected = null }, reveal = source?.let { step -> { originIndex = listState.firstVisibleItemIndex; originOffset = listState.firstVisibleItemScrollOffset; scope.launch { val index = rows.indexOfFirst { it is EditorRow.Action && it.step.id == step.id }; if (index >= 0) { listState.animateScrollToItem(index + 1); revealedId = step.id } } } })
            }
            if (choosingOutput) Text(t("tap_output_inline"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            if (focused != null && !busy && !choosingOutput && variables.inspected == null) {
                val names = (listOf("input") + loopNames + available.flatMap { step ->
                    listOfNotNull(step.parameters["resultVariable"]?.takeIf(String::isNotBlank), if (step.type in listOf("set_variable", "create_variable")) step.parameters["name"] else null) +
                        (if (step.type == "shell") listOf("shell_stdout", "shell_stderr", "shell_exit_code") else emptyList())
                }).distinct()
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    item { InputChip(false, ::beginChoosing, label = { Text(t("select_output")) }, leadingIcon = { VariableGlyph() }, modifier = Modifier.testTag("select_action_output")) }
                    items(names, key = { it }) { name -> InputChip(false, { insertReference(name) }, label = { Text(if (name == "input") t("previous_result") else name) }, leadingIcon = { VariableGlyph() }) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { openLibrary() }, enabled = !busy && !choosingOutput && flatSteps.size < 1000, modifier = Modifier.weight(1f).height(52.dp).testTag("open_action_library"), shape = RoundedCornerShape(20.dp)) { AppIcon(R.drawable.ic_add); Spacer(Modifier.width(8.dp)); Text(t("add_action")) }
                FilledIconButton(onClick = { if (state.runningId != null) model.cancelRun() else model.runEditor() }, enabled = !state.saving && state.storageReady && !choosingOutput,
                    modifier = Modifier.size(52.dp).testTag("run_shortcut"), shape = RoundedCornerShape(20.dp)) { AppIcon(if (state.runningId == null) R.drawable.ic_play else R.drawable.ic_stop, description = t(if (state.runningId == null) "run" else "stop")) }
            }
        } } }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).onGloballyPositioned { drag.origin = it.positionInRoot(); drag.height = it.size.height.toFloat() }
            .editorDragGestures(drag, !busy && !choosingOutput, started = { keyboard?.hide(); focusManager.clearFocus(); haptics.performHapticFeedback(HapticFeedbackType.LongPress) }, deleted = { id -> focused = null; variables.inspected = null; model.remove(id) }, finished = { model.place(it.source, it.target, it.before) })
            .testTag("editor_viewport")) {
        LazyColumn(Modifier.fillMaxSize().testTag("editor_actions"), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(onClick = { keyboard?.hide(); appearancePicker = true }, enabled = !busy && !choosingOutput, shape = RoundedCornerShape(22.dp), modifier = Modifier.testTag("edit_shortcut_icon")) { ShortcutBadge(editor.icon, editor.color, 64.dp, t("change_icon")) }
                OutlinedTextField(editor.name, model::editName, label = { Text(t("name")) }, enabled = !busy && !choosingOutput, singleLine = true, textStyle = MaterialTheme.typography.headlineSmall, shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f).testTag("shortcut_name").onFocusChanged { if (it.isFocused) focused = null }) }; Text(t("actions_count", flatSteps.size) + " · " + t(modeKey(state.settings.mode)), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge) }
            if (state.error != null) item { ErrorCard(state.error, model::clearError) }
            if (state.notice != null) item { Text(state.notice) }
            if (editor.steps.isEmpty()) item { Card { Column(Modifier.padding(24.dp)) { Text(t("empty_title"), style = MaterialTheme.typography.titleLarge); Text(t("empty_body")); TextButton(onClick = { openLibrary() }) { Text(t("add_action")) } } } }
            items(rows, key = { it.key }) { row ->
                val indent = Modifier.padding(start = (minOf(row.depth, 3) * 12).dp)
                val insertion = if (libraryDragging) libraryDestination else drag.destination
                val gapBefore = row is EditorRow.Action && insertion?.target == row.step.id && insertion.before || row is EditorRow.Footer && libraryDragging && libraryFooterTarget == row.key
                val gapAfter = when (row) { is EditorRow.Action -> row.step.branches.isEmpty() && insertion?.target == row.step.id && !insertion.before; is EditorRow.End -> insertion?.target == row.step.id && !insertion.before; else -> false }
                Column(Modifier.animateItem()) {
                DragInsertionGap(gapBefore, indent.onGloballyPositioned { if (gapBefore) drag.gapBounds = Rect(it.positionInRoot() - drag.origin, it.size.toSize()) })
                when (row) {
                    is EditorRow.Action -> {
                        val step = byId.getValue(row.step.id); val definition = ActionCatalog.forStep(step)
                        DisposableEffect(step.id) { onDispose { drag.handles.remove(step.id) } }
                        Card(indent.fillMaxWidth().graphicsLayer { alpha = if (drag.source == step.id) 0.45f else 1f }.testTag("action_${step.id}"), border = if (drag.destination?.target == step.id || revealedId == step.id || libraryDropTarget == step.id) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = when { state.result?.failedStepId == step.id -> MaterialTheme.colorScheme.errorContainer; state.currentStepId == step.id -> MaterialTheme.colorScheme.primaryContainer; else -> MaterialTheme.colorScheme.surfaceContainerHigh })) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val dragDescription = t("drag_action")
                                    Box(Modifier.size(44.dp).testTag("drag_${step.id}").semantics { contentDescription = dragDescription }
                                        .onGloballyPositioned { val position = it.positionInRoot() - drag.origin; drag.handles[step.id] = Rect(position, it.size.toSize()) }, contentAlignment = Alignment.Center) { AppIcon(R.drawable.ic_drag) }
                                    ActionIcon(definition)
                                    Column(Modifier.weight(1f)) { Text(if (definition == null) step.type else t(definition.title), style = MaterialTheme.typography.titleMedium); Text(t(if (step.enabled) "enabled" else "disabled"), style = MaterialTheme.typography.labelSmall) }
                                    if (!choosingOutput) ExpressiveSwitch(step.enabled, { model.update(step.copy(enabled = it)) }, enabled = !busy)
                                }
                                if (choosingOutput) {
                                    if (step.id in availableIds) InlineOutputChip(referenceNames["@${step.id}"] ?: step.type, observed[step.id]?.output ?: step.parameters["value"]?.takeUnless { "{{" in it }, step.id) { chooseOutput(step.id) }
                                    if (step.id in availableIds) ActionOutputs.properties(step.type).forEach { property ->
                                        InlineOutputChip(t("${if (step.type == "current_location") "location" else "image"}_prop_${property.lowercase(java.util.Locale.ROOT)}"), (observed[step.id]?.value as? Value.Dictionary)?.entries?.get(property)?.text(), "${step.id}.$property") { chooseOutput("${step.id}.$property") }
                                    }
                                } else {
                                definition?.fields?.forEach { field ->
                                    if (field.key in listOf("rounding", "digits") && step.type == "calculate" && step.parameters["operation"] != "round") return@forEach
                                    val fieldLabel = if (step.type == "calculate" && field.key == "right" && step.parameters["operation"] == "root") "root_degree" else field.label
                                    if (step.type == "calculate" && field.key == "right" && step.parameters["operation"] in listOf("sin", "cos", "tan", "asin", "acos", "atan", "abs", "sqrt", "cbrt", "round")) return@forEach
                                    if (field.choices.map { it.first } == listOf("false", "true")) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(t(fieldLabel), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                        ExpressiveSwitch(step.parameters[field.key] == "true", { parameter(step, field.key, it.toString()) }, enabled = !busy)
                                    }
                                    else if (field.key == "zone") TimeZoneParameter(step.parameters[field.key].orEmpty(), !busy) { parameter(step, field.key, it) }
                                    else if (field.choices.isNotEmpty()) {
                                        Text(t(fieldLabel), style = MaterialTheme.typography.labelLarge)
                                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { field.choices.forEach { (key, label) -> FilterChip((step.parameters[field.key] ?: field.initial) == key, {
                                            if (field.key == "unit" && key != (step.parameters["unit"] ?: "milliseconds")) {
                                                val amountKey = if (step.type == "delay") "milliseconds" else "duration"
                                                val converted = step.parameters[amountKey]?.toBigDecimalOrNull()?.let { if (key == "seconds") it.divide(1000.toBigDecimal()) else it.multiply(1000.toBigDecimal()) }?.stripTrailingZeros()?.toPlainString()
                                                model.update(step.copy(parameters = step.parameters + ("unit" to key) + if (converted == null) emptyMap() else mapOf(amountKey to converted)))
                                            } else parameter(step, field.key, key)
                                        }, enabled = !busy, label = { Text(if (label in Texts.ids) t(label) else label) }) } }
                                    } else {
                                        if (field.key in listOf("name", "timeoutSeconds")) OutlinedTextField(step.parameters[field.key] ?: field.initial, { parameter(step, field.key, it) }, label = { Text(t(fieldLabel)) }, enabled = !busy, singleLine = true, shape = RoundedCornerShape(16.dp), leadingIcon = if (field.key == "name") ({ VariableGlyph() }) else null, modifier = Modifier.fillMaxWidth().testTag("field_${step.id}_${field.key}").onFocusChanged { if (it.isFocused) focused = null })
                                        else VariableTextField("${step.id}:${field.key}", step.parameters[field.key] ?: field.initial, { parameter(step, field.key, it) }, t(fieldLabel), variables, referenceNames, modifier = Modifier.testTag("field_${step.id}_${field.key}"), enabled = !busy, multiline = field.multiline, focused = { focused = step.id to field.key }, keyboardType = if (step.type == "number" || step.type == "calculate" && field.key in listOf("left", "right", "digits") || step.type == "round_number" && field.key in listOf("source", "digits")) androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text)
                                        if ((step.type == "delay" && field.key == "milliseconds") || (step.type == "vibrate" && field.key in listOf("duration", "amplitude"))) NumericParameterControls(step.parameters[field.key] ?: field.initial, step.parameters["unit"] ?: "milliseconds", field.key == "amplitude", !busy) { parameter(step, field.key, it) }
                                        if ((step.type == "read_calendar" && field.key == "date") || (step.type == "format_date" && field.key == "source") || (step.type == "calendar" && field.key in listOf("start", "end"))) DateParameterPresets(step.parameters[field.key].orEmpty(), !busy, step.type == "calendar", field.key == "end") { parameter(step, field.key, it) }
                                        if (step.type in listOf("format_date", "current_date") && field.key == "format") DateFormatPresets(!busy) { parameter(step, field.key, it) }
                                        if (field.key == "name" && step.type in listOf("get_variable", "append_variable")) {
                                            val before = indices[step.id] ?: 0
                                            val known = flatSteps.take(before).flatMap { previous -> listOfNotNull(previous.parameters["resultVariable"]?.takeIf(String::isNotBlank), previous.parameters["name"].takeIf { previous.type in listOf("set_variable", "create_variable") }) }.distinct()
                                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(known, key = { it }) { name -> AssistChip(onClick = { parameter(step, "name", name) }, enabled = !busy, label = { Text(name) }) } }
                                        }
                                        if (field.key == "expression") Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            listOf("+", "-", "*", "/", "%", "^", "(", ")", ",", "sin(", "cos(", "tan(", "asin(", "acos(", "atan(", "abs(", "sqrt(", "cbrt(", "root(", "round(", "ceil(", "floor(", "min(", "max(", "gcd(", "lcm(", "mod(").forEach { symbol -> AssistChip(onClick = { if (variables.active?.id == "${step.id}:${field.key}") variables.insert(symbol) else parameter(step, field.key, (step.parameters[field.key] ?: field.initial) + symbol) }, enabled = !busy, label = { Text(symbol) }) }
                                        }
                                        if (field.key in listOf("start", "end")) Text(t("date_hint"), style = MaterialTheme.typography.bodySmall)
                                        if (field.key == "pattern") Text(t("regex_hint"), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (step.type in listOf("dictionary", "intent", "get_url", "get_html")) TypedEntriesEditor(step, step.type == "intent", !busy, model::update, variables, referenceNames) { key -> focused = key?.let { step.id to it } }
                                if (step.type == "list") ListEntriesEditor(step, !busy, model::update, variables, referenceNames) { key -> focused = key?.let { step.id to it } }
                                if (step.type == "open_app") {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { AppPackageIcon(step.parameters["packageName"].orEmpty()); Column { Text(step.parameters["displayName"].orEmpty()); Text(step.parameters["packageName"].orEmpty(), style = MaterialTheme.typography.bodySmall) } }
                                    OutlinedButton(onClick = { reselect = step.id; apps = true }, enabled = !busy) { Text(t("reselect_app")) }
                                }
                                if (step.type == "intent") FilledTonalButton(onClick = { reselect = step.id; apps = true }, enabled = !busy) { Text(t("choose_app")) }
                                if (step.type == "install_apk") {
                                    Text(step.parameters["displayName"].orEmpty())
                                    Text(t("apk_description"), style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(onClick = { chooseApk(step.id) }, enabled = !busy) { Text(t("reselect_apk")) }
                                }
                                if (step.type == "screenshot") Text(t("capture_detail"), style = MaterialTheme.typography.bodySmall)
                                if (step.type == "generate_uuid") Text(t("seed_hint"), style = MaterialTheme.typography.bodySmall)
                                if (step.type == "encode_base64" && step.parameters["inputType"] in listOf("image", "file")) FilledTonalButton(onClick = { scope.launch {
                                    try { val uri = (context.applicationContext as ShortcutsApplication).interactions.ask(Interaction.PickFile(step.parameters["inputType"] == "image")); if (uri != null) parameter(step, "source", uri) }
                                    catch (e: Exception) { if (e is CancellationException) throw e; model.report(e) }
                                } }, enabled = !busy) { Text(t("action_choose_file")) }
                                if (step.type == "shell" && state.currentStepId == step.id && state.runningId != null) LiveShell(state.shellStdout, state.shellStderr)
                                if (ActionOutputs.produces(step.type) && step.type !in listOf("create_variable", "append_variable", "get_variable")) OutlinedTextField(step.parameters["resultVariable"].orEmpty(), { parameter(step, "resultVariable", it) }, label = { Text(t("save_result")) }, enabled = !busy, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) focused = null })
                                if (ActionOutputs.produces(step.type)) TextButton(onClick = { model.addVariableAfter(step.id) }, enabled = !busy && flatSteps.size < 1000) { AppIcon(R.drawable.ic_add); Spacer(Modifier.width(8.dp)); Text(t("add_variable")) }
                                if (step.type == "if" && step.branches.none { it.id == "else" }) OutlinedButton(onClick = { model.update(step.copy(branches = step.branches + ActionBranch("else"))) }, enabled = !busy) { Text(t("add_else")) }
                                if (step.type == "menu") OutlinedButton(onClick = { model.update(step.copy(branches = step.branches + ActionBranch(label = context.tr("menu_item") + " ${step.branches.size + 1}"))) }, enabled = !busy && step.branches.size < 30) { Text(t("add_menu_item")) }
                                observed[step.id]?.let { result -> Text(t("result") + ": " + result.output.take(512), style = MaterialTheme.typography.bodySmall) }
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { model.move(step.id, -1) }, enabled = !busy && row.index > 0) { Text(t("move_up")) }
                                    TextButton(onClick = { model.move(step.id, 1) }, enabled = !busy && row.index < row.size - 1) { Text(t("move_down")) }
                                    TextButton(onClick = { model.editSteps(TreeEdits.copyAfter(editor.steps, step.id)) }, enabled = !busy && flatSteps.size * 2 < 1000) { Text(t("duplicate")) }
                                    TextButton(onClick = { focused = null; model.remove(step.id) }, enabled = !busy) { Text(t("remove")) }
                                }
                                }
                            }
                        }
                    }
                    is EditorRow.Branch -> {
                        val owner = byId.getValue(row.owner.id); val branch = owner.branches.first { it.id == row.branch.id }
                        Row(indent.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (owner.type == "menu") OutlinedTextField(branch.label, { if (it.length <= 200) model.update(owner.copy(branches = owner.branches.map { branch -> if (branch.id == row.branch.id) branch.copy(label = it) else branch })) }, enabled = !busy, label = { Text(t("menu_item")) }, singleLine = true, modifier = Modifier.weight(1f))
                        else Text(t(if (row.branch.id == "body") "repeat_body" else row.branch.id), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        if (owner.type == "menu" || row.branch.id == "else") TextButton(onClick = { model.update(owner.copy(branches = owner.branches.filter { it.id != row.branch.id })) }, enabled = !busy) { Text(t("remove")) }
                    }
                    }
                    is EditorRow.Footer -> {
                        DisposableEffect(row.key) { onDispose { libraryFooters.remove(row.key) } }
                        OutlinedButton(onClick = { openLibrary(row.owner.id, row.branch.id) }, enabled = !busy && !choosingOutput && row.depth < 16, modifier = indent.fillMaxWidth().onGloballyPositioned { libraryFooters[row.key] = DragScope(row.owner.id, row.branch.id) to Rect(it.positionInRoot() - drag.origin, it.size.toSize()) }) { AppIcon(R.drawable.ic_add); Spacer(Modifier.width(8.dp)); Text(t("add_action")) }
                    }
                    is EditorRow.End -> Surface(modifier = indent.fillMaxWidth().testTag("end_${row.step.id}"), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(t(when (row.step.type) { "if" -> "end_if"; "menu" -> "end_menu"; else -> "end_repeat" }), Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
                    }
                }
                DragInsertionGap(gapAfter, indent.onGloballyPositioned { if (gapAfter) drag.gapBounds = Rect(it.positionInRoot() - drag.origin, it.size.toSize()) })
                }
            }
            state.result?.let { result -> item { ResultCard(result); TextButton(onClick = { scope.launch { (context.applicationContext as ShortcutsApplication).actions.quickLook(result.steps.lastOrNull()?.value ?: Value.Text("")) } }) { Text(t("quick_look")) } } }
        }
        DragPreview(drag)
        }
    }
    if (appearancePicker) ShortcutAppearancePicker(editor.icon, editor.color, { appearancePicker = false }, model::editAppearance)
    if (library) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)).clickable { library = false })
        ActionLibrary(onDismiss = { library = false }, onAdd = ::addFromLibrary, modifier = Modifier.align(Alignment.BottomCenter))
    }
    if (apps) AppsPicker(onDismiss = { apps = false; reselect = null }, onError = model::report, allPackages = byId[reselect]?.type == "intent") { selected ->
        val target = byId[reselect]
        if (target?.type == "intent") model.update(target.copy(parameters = target.parameters + mapOf("packageName" to selected.packageName, "className" to if (target.parameters["action"] == "android.intent.action.MAIN") selected.className else ""))) else model.selectApp(selected, targetOwner, targetBranch, reselect, insertionAnchor, insertionBefore)
        apps = false; reselect = null; insertionAnchor = null
    }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text(t("discard_title")) }, text = { Text(t("discard_body")) }, confirmButton = { TextButton(onClick = { confirmLeave = false; model.saveEditor() }) { Text(t("save")) } }, dismissButton = { Row { TextButton(onClick = { confirmLeave = false }) { Text(t("stay")) }; TextButton(onClick = { confirmLeave = false; model.closeEditor() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(t("discard")) } } })
    }
}

@Composable private fun TypedEntriesEditor(step: ActionStep, extras: Boolean, enabled: Boolean, onChange: (ActionStep) -> Unit, variables: VariableSession, referenceNames: Map<String, String>, onFocus: (String?) -> Unit) {
    val query = step.type in listOf("get_url", "get_html")
    val prefix = if (query) "query" else if (extras) "extra" else "entry"; val legacy = if (query) "query" else if (extras) "extras" else "entries"
    val parsed = runCatching { readEntrySpecs(step.parameters, prefix, legacy) }
    if (parsed.isFailure) { Text(t("err_invalid_data")); TextButton(onClick = { onChange(step.copy(parameters = writeEntrySpecs(step.parameters, prefix, legacy, emptyList()))) }) { Text(t("reset")) }; return }
    val entries = parsed.getOrThrow(); val choices = if (extras) ActionCatalog.extraTypes else ActionCatalog.valueTypes
    val blank = EntrySpec(if (extras) "string" else "auto", "", "")
    val rows = entries.ifEmpty { listOf(blank) }
    var pendingFocus by remember(step.id) { mutableStateOf<Int?>(null) }
    fun commit(updated: List<EntrySpec>) { onChange(step.copy(parameters = writeEntrySpecs(step.parameters, prefix, legacy, updated))) }
    fun change(index: Int, entry: EntrySpec) { commit(if (index < entries.size) entries.mapIndexed { i, old -> if (i == index) entry else old } else entries + entry) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(t(if (query) "query_parameters" else if (extras) "extras" else "action_dictionary"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { pendingFocus = rows.size; commit(rows + blank) }, enabled = enabled && rows.size < (if (extras) 50 else 256), modifier = Modifier.testTag("add_entry_${step.id}")) { AppIcon(R.drawable.ic_add); Text(t(if (extras) "add_extra" else "add_key_value")) }
    }
    rows.forEachIndexed { index, entry ->
        val requester = remember(step.id, index) { FocusRequester() }
        val bringIntoView = remember(step.id, index) { BringIntoViewRequester() }
        LaunchedEffect(pendingFocus, index) { if (pendingFocus == index) { requester.requestFocus(); bringIntoView.bringIntoView(); pendingFocus = null } }
        Row(Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(entry.key, { change(index, entry.copy(key = it)) }, label = { Text(t("key")) }, enabled = enabled, singleLine = true, modifier = Modifier.weight(0.9f).focusRequester(requester).testTag("${prefix}_key_${step.id}_$index").onFocusChanged { if (it.isFocused) onFocus(null) })
            VariableTextField("${step.id}:$prefix.$index.value", entry.value, { change(index, entry.copy(value = it)) }, t("value"), variables, referenceNames, enabled = enabled, multiline = true, minLines = 1, modifier = Modifier.weight(1.1f).testTag("${prefix}_value_${step.id}_$index"), focused = { onFocus("$prefix.$index.value") })
            if (index < entries.size) IconButton(onClick = { onFocus(null); commit(entries.filterIndexed { other, _ -> index != other }) }, enabled = enabled) { AppIcon(R.drawable.ic_delete, description = t("remove")) }
        }
        if (index < entries.size && !query) {
            var expanded by remember { mutableStateOf(false) }
            Box { TextButton(onClick = { expanded = true }, enabled = enabled) { Text(t(choices.find { it.first == entry.type }?.second ?: "auto_type")) }
                DropdownMenu(expanded, { expanded = false }) { choices.forEach { (key, label) -> DropdownMenuItem(text = { Text(t(label)) }, onClick = { change(index, entry.copy(type = key)); expanded = false }) } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ActionLibrary(onDismiss: () -> Unit, onAdd: (ActionDefinition) -> Unit, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val localized = ActionCatalog.all.map { Triple(it, t(it.title), t("group_${it.group}")) }
    val dragScope = rememberCoroutineScope()
    BackHandler(onBack = onDismiss)
    Surface(modifier.fillMaxWidth().fillMaxHeight(0.74f).imePadding().navigationBarsPadding().testTag("action_library_panel"), shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp, shadowElevation = 16.dp) {
        val localView = androidx.compose.ui.platform.LocalView.current
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(t("add_action"), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium); IconButton(onClick = onDismiss) { AppIcon(R.drawable.ic_close, description = t("close")) } }
            Text(t("drag_library_hint"), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(query, { query = it }, label = { Text(t("search_actions")) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("library_search"))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(category == null, { category = null }, label = { Text(t("all_categories")) })
                ActionCatalog.all.map { it.group }.distinct().forEach { group -> FilterChip(category == group, { category = group }, label = { Text(t("group_$group")) }) }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                val filtered = localized.filter { (category == null || it.first.group == category) && (query.isBlank() || it.second.contains(query, true) || it.third.contains(query, true) || it.first.type.contains(query, true)) }
                if (filtered.isEmpty()) item { Text(t("no_actions")) }
                filtered.groupBy { it.first.group }.forEach { (groupKey, actions) ->
                item(key = "group:$groupKey") { Text(t("group_$groupKey"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)) }
                items(actions, key = { it.first.type }) { (definition, title, group) -> Card(modifier = Modifier.testTag("library_action_${definition.type}").combinedClickable(onClick = { onAdd(definition) }, onLongClick = { if (startLibraryDrag(localView, definition.type, title)) dragScope.launch { delay(100); onDismiss() } }), shape = RoundedCornerShape(22.dp)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { ActionIcon(definition) }; Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(group, style = MaterialTheme.typography.bodySmall) }; AppIcon(R.drawable.ic_add) }
                } }
                }
            }
        }
    }
}

@Composable private fun ListEntriesEditor(step: ActionStep, enabled: Boolean, change: (ActionStep) -> Unit, variables: VariableSession, referenceNames: Map<String, String>, focus: (String?) -> Unit) {
    val entries = runCatching { if (step.parameters.keys.any { it.startsWith("item.") }) readEntrySpecs(step.parameters, "item", "items") else step.parameters["items"].orEmpty().lines().filter { it.isNotEmpty() }.map { EntrySpec("auto", "", it) } }.getOrElse { emptyList() }
    fun commit(values: List<EntrySpec>) { change(step.copy(parameters = writeEntrySpecs(step.parameters, "item", "items", values))) }
    val rows = entries + if (entries.size < 256) listOf(EntrySpec("auto", "", "")) else emptyList()
    rows.forEachIndexed { index, item -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${index + 1}.", Modifier.width(28.dp))
        VariableTextField("${step.id}:item.$index.value", item.value, { value -> commit(if (index < entries.size) entries.mapIndexed { i, old -> if (i == index) old.copy(value = value) else old } else entries + item.copy(value = value)) }, t("value"), variables, referenceNames, enabled = enabled, multiline = true, minLines = 1, modifier = Modifier.weight(1f).testTag("item_value_${step.id}_$index"), focused = { focus("item.$index.value") })
        if (index < entries.size) IconButton(onClick = { focus(null); commit(entries.filterIndexed { i, _ -> i != index }) }, enabled = enabled) { AppIcon(R.drawable.ic_delete, description = t("remove")) }
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AppsPicker(onDismiss: () -> Unit, onError: (Throwable) -> Unit, allPackages: Boolean = false, selected: (LaunchableApp) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<LaunchableApp>?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(allPackages) { try { apps = AppsRepository.load(context, allPackages) } catch (e: Exception) { if (e is CancellationException) throw e; onError(e); onDismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.85f).padding(horizontal = 24.dp)) {
            Text(t("choose_app"), style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(search, { search = it }, label = { Text(t("search_apps")) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(t("show_system_apps"), Modifier.weight(1f)); ExpressiveSwitch(showSystem, { showSystem = it }) }
            if (apps == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(apps.orEmpty().filter { (showSystem || !it.system) && (search.isBlank() || it.label.contains(search, true) || it.packageName.contains(search, true)) }, key = { it.packageName + "/" + it.className }) { app ->
                    Card(onClick = { selected(app) }) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { AppPackageIcon(app.packageName); Column { Text(app.label, style = MaterialTheme.typography.titleMedium); Text(app.packageName, style = MaterialTheme.typography.bodySmall) } } }
                }
            }
        }
    }
}
