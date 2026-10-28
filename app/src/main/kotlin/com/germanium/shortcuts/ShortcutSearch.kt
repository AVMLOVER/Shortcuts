package com.germanium.shortcuts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.germanium.shortcuts.core.Shortcut

@Composable internal fun ShortcutSearchField(text: TextFieldState, close: () -> Unit, modifier: Modifier = Modifier) {
    val state = rememberSearchBarState()
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { withFrameNanos { }; requester.requestFocus() }
    SearchBar(state = state, modifier = modifier.height(56.dp).testTag("shortcut_search"), inputField = {
        SearchBarDefaults.InputField(textFieldState = text, searchBarState = state, onSearch = { keyboard?.hide() },
            placeholder = { Text(t("search_shortcuts"), maxLines = 1) }, modifier = Modifier.focusRequester(requester).testTag("shortcut_search_input"),
            leadingIcon = { IconButton(onClick = close, modifier = Modifier.testTag("close_shortcut_search")) { AppIcon(R.drawable.ic_back, description = t("back")) } },
            trailingIcon = { if (text.text.isNotEmpty()) IconButton(onClick = { text.setTextAndPlaceCursorAtEnd("") }, modifier = Modifier.testTag("clear_shortcut_search")) { AppIcon(R.drawable.ic_close, description = t("clear_search")) } })
    })
}

@Composable internal fun ShortcutSearchResult(shortcut: Shortcut, enabled: Boolean, edit: () -> Unit, run: () -> Unit) {
    Card(onClick = edit, enabled = enabled, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().testTag("search_result_${shortcut.id}")) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ShortcutBadge(shortcut.icon, shortcut.color, 40.dp)
            Text(shortcut.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = run, enabled = enabled, modifier = Modifier.testTag("search_run_${shortcut.id}")) { AppIcon(R.drawable.ic_play, description = t("run")) }
        }
    }
}
