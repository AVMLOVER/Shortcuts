package com.germanium.shortcuts

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Shared rich result renderer; files retain their references rather than becoming path labels. */
@Composable internal fun QuickLookContent(app:ShortcutsApplication,source:Value,modifier:Modifier=Modifier,interactive:Boolean=true) {
    val entries=remember(source){DisplayContent.items(source)}
    LazyColumn(modifier.fillMaxWidth().heightIn(max=400.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(entries.isEmpty())item{Text(t("no_output"))}
        itemsIndexed(entries){index,entry->QuickLookItem(app,entry,index,interactive)}
    }
}
private data class PreviewState(val loaded:Boolean=false,val content:RichPreview?=null)

@Composable private fun QuickLookItem(app:ShortcutsApplication,entry:Value,index:Int,interactive:Boolean) {
    if(entry is Value.RichText) {
        RichTextPreview(entry,interactive,index)
        return
    }
    val text=entry.text()
    var page by rememberSaveable(text){mutableIntStateOf(0)}
    val loader=remember(app){AndroidPreviewContent(app)}
    val scope=rememberCoroutineScope()
    var error by remember(text){mutableStateOf<String?>(null)}
    val preview by produceState(PreviewState(),entry,page) {
        value=PreviewState()
        value=PreviewState(true,if(entry is Value.Text)loader.load(text,page) else null)
    }
    val content=preview.content
    when {
        !preview.loaded->Box(Modifier.fillMaxWidth().height(48.dp)){CircularProgressIndicator(Modifier.size(28.dp))}
        content?.document!=null->{
            val document=content.document
            OutlinedCard(Modifier.fillMaxWidth().testTag("document_preview_$index")) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        AppIcon(R.drawable.ic_file_read);Text(document.name,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                    }
                    Text("${document.mime} · ${document.size} B",style=MaterialTheme.typography.labelSmall)
                    content.image?.let{Image(it.asImageBitmap(),t("document_preview"),Modifier.fillMaxWidth().heightIn(max=300.dp).testTag("document_page_$index"))}
                    if(interactive&&document.pages>0)Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        IconButton(onClick={page=document.page-1},enabled=document.page>0){AppIcon(R.drawable.ic_back,description=t("previous_page"))}
                        Text(t("document_page",document.page+1,document.pages),Modifier.weight(1f))
                        IconButton(onClick={page=document.page+1},enabled=document.page+1<document.pages){AppIcon(R.drawable.ic_back,modifier=Modifier.rotate(180f),description=t("next_page"))}
                    }
                    document.text?.let{PreviewText(it,interactive)}
                    (content.typedContent as? Value.RichText)?.let{RichTextPreview(it,interactive,index)}
                    if(document.pages==0&&document.text==null&&content.typedContent==null)Text(t("document_external"),style=MaterialTheme.typography.bodySmall)
                    if(interactive)FilledTonalButton(onClick={scope.launch{try{loader.open(text,document.mime)}catch(e:CancellationException){throw e}catch(e:Exception){error=app.describe(e)}}}){Text(t("open_document"))}
                    error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                }
            }
        }
        content?.animation!=null->GifPreview(content.animation)
        content?.image!=null->Image(content.image.asImageBitmap(),t("image_preview"),Modifier.fillMaxWidth().heightIn(max=320.dp).testTag("quick_look_image_$index"))
        else->PreviewText(text,interactive)
    }
}

@Composable private fun RichTextPreview(entry:Value.RichText,interactive:Boolean,index:Int) {
    val textColor=MaterialTheme.colorScheme.onSurface.toArgb()
    val styled=remember(entry.html){android.text.Html.fromHtml(entry.html,android.text.Html.FROM_HTML_MODE_COMPACT)}
    androidx.compose.ui.viewinterop.AndroidView(factory={android.widget.TextView(it)},update={view->
        if(view.text!==styled)view.text=styled
        view.setTextColor(textColor);view.textSize=16f;view.setTextIsSelectable(interactive)
    },modifier=Modifier.fillMaxWidth().testTag("rich_text_preview_$index"))
}

@Composable private fun PreviewText(text:String,interactive:Boolean) {
    val content:@Composable ()->Unit={Column{Text(text.ifEmpty{t("no_output")}.take(65536));if(text.length>65536)Text(t("preview_limited"),style=MaterialTheme.typography.bodySmall)}}
    if(interactive)SelectionContainer(content=content) else content()
}
