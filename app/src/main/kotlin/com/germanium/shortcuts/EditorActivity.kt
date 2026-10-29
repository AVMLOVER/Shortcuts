package com.germanium.shortcuts

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.UUID

fun openEditor(activity: Activity, id: String? = null, template: String? = null) {
    val intent = Intent(activity, EditorActivity::class.java).putExtra("shortcutId", id ?: UUID.randomUUID().toString()).putExtra("template", template)
    activity.startActivity(intent, ActivityOptions.makeCustomAnimation(activity, R.anim.editor_enter, R.anim.home_exit).toBundle())
}
class EditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableShortcutsEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 34) { overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.editor_enter, R.anim.home_exit); overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.home_enter, R.anim.editor_exit) }
        val id = intent.getStringExtra("shortcutId") ?: savedInstanceState?.getString("shortcutId") ?: UUID.randomUUID().toString().also { intent.putExtra("shortcutId", it) }
        setContent {
            val model: ShortcutsViewModel = viewModel()
            model.onCloseEditor = { finish(); if (Build.VERSION.SDK_INT < 34) { @Suppress("DEPRECATION") overridePendingTransition(R.anim.home_enter, R.anim.editor_exit) } }
            val state by model.state.collectAsStateWithLifecycle(); val editor by model.editor.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(model) { model.openEditor(id, intent.getStringExtra("template")) }
            ShortcutsTheme(state.settings.theme) {
                ObserveLifecycle(model)
                if (editor != null) ShortcutEditorScreen(editor!!, state, model)
                else Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (state.error != null) ErrorCard(state.error!!, { finish() }) else CircularProgressIndicator(Modifier.testTag("editor_loading"))
                } }
                InteractionHost(application as ShortcutsApplication)
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("shortcutId", intent.getStringExtra("shortcutId")); super.onSaveInstanceState(outState) }
}
