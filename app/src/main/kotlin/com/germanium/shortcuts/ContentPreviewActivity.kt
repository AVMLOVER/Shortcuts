package com.germanium.shortcuts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.CancellationException

/** Notifications open an explicit Activity directly, including after the process has restarted. */
class ContentPreviewActivity:ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableShortcutsEdgeToEdge()
        val app=application as ShortcutsApplication
        val id=intent.getIntExtra("contentId",-1)
        val title=intent.getStringExtra("title").orEmpty().take(200).ifBlank{app.tr("result")}
        setContent {
            var content by remember{mutableStateOf<Value?>(null)}
            var error by remember{mutableStateOf<String?>(null)}
            var theme by remember{mutableStateOf(ThemeMode.SYSTEM)}
            LaunchedEffect(id){try{theme=app.settings.load().theme;content=NotificationContents.load(app,id)}catch(e:CancellationException){throw e}catch(e:Exception){error=app.describe(e)}}
            ShortcutsTheme(theme) {
                Scaffold(contentWindowInsets=shortcutsContentInsets(),topBar={TopAppBar(title={Text(title)},navigationIcon={IconButton(onClick={finish()}){AppIcon(R.drawable.ic_back,description=t("back"))}},actions={content?.let{ShareValueButton(it)}})}){padding->
                    Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
                        val failure=error;val value=content
                        if(failure!=null)ErrorCard(failure){finish()} else if(value!=null)QuickLookContent(app,value) else CircularProgressIndicator()
                    }
                }
            }
        }
    }
}
