package com.germanium.shortcuts

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

@Composable internal fun GifPreview(bytes:ByteArray) {
    if(Build.VERSION.SDK_INT<28)return
    var loaded by remember(bytes){mutableStateOf(false)}
    val drawable by produceState<android.graphics.drawable.Drawable?>(null,bytes) {
        value=withContext(Dispatchers.IO){runCatching{ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))){decoder,info,_->
            val ratio=minOf(1f,1200f/maxOf(info.size.width,info.size.height));decoder.setTargetSize((info.size.width*ratio).toInt().coerceAtLeast(1),(info.size.height*ratio).toInt().coerceAtLeast(1))
        }}.getOrNull()}
        loaded=true
    }
    if(drawable==null&&!loaded)CircularProgressIndicator(Modifier.size(28.dp))
    else if(drawable==null)Text(t("err_gif"))
    else AndroidView(factory={context->ImageView(context).apply{adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription=context.tr("image_preview")}},update={view->
        if(view.drawable!==drawable){(view.drawable as? AnimatedImageDrawable)?.stop();view.setImageDrawable(drawable);(drawable as? AnimatedImageDrawable)?.start()}
    },onRelease={(it.drawable as? AnimatedImageDrawable)?.stop();it.setImageDrawable(null)},modifier=Modifier.fillMaxWidth().heightIn(min=64.dp,max=320.dp))
}
