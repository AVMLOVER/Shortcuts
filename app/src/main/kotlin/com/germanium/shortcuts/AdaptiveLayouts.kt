package com.germanium.shortcuts

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.CancellationException

internal data class AdaptiveLayoutInfo(val policy:AdaptiveInterface,val hingeStart:Dp?=null,val hingeEnd:Dp?=null)
private fun Context.activity():Activity?=when(this){is Activity->this;is ContextWrapper->baseContext.takeUnless{it===this}?.activity();else->null}

@Composable internal fun rememberAdaptiveLayout():AdaptiveLayoutInfo {
    val context=LocalContext.current;val configuration=LocalConfiguration.current;val density=LocalDensity.current
    val activity=context.activity()
    var folds by remember(activity){mutableStateOf<List<FoldingFeature>>(emptyList())}
    LaunchedEffect(activity){
        val owner=activity as? androidx.lifecycle.LifecycleOwner
        if(activity!=null&&owner!=null)try{owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect{folds=it.displayFeatures.filterIsInstance<FoldingFeature>()}}}
        catch(e:CancellationException){throw e}catch(_:Exception){folds=emptyList()}
    }
    val smallest=remember(activity,configuration,density.density){
        val maximum=activity?.let{runCatching{WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(it).bounds}.getOrNull()}
        maxOf(configuration.smallestScreenWidthDp,maximum?.let{(minOf(it.width(),it.height())/density.density).toInt()}?:0)
    }
    val hingeHardware=remember(context){context.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle")}
    val policy=InterfacePolicies.adaptive(configuration.screenWidthDp,configuration.screenHeightDp,smallest,hingeHardware||folds.isNotEmpty())
    val hinge=folds.firstOrNull{it.orientation==FoldingFeature.Orientation.VERTICAL&&(it.isSeparating||it.occlusionType==FoldingFeature.OcclusionType.FULL)}
    return AdaptiveLayoutInfo(policy,hinge?.let{with(density){it.bounds.left.toDp()}},hinge?.let{with(density){it.bounds.right.toDp()}})
}

/** UI-only preference: workflow XML and the existing settings document remain unchanged. */
internal object EditorPanePreferences {
    fun load(context:Context)=runCatching{EditorPaneSide.valueOf(context.getSharedPreferences("layout",Context.MODE_PRIVATE).getString("editorPaneSide","LEFT")!!)}.getOrDefault(EditorPaneSide.LEFT)
    fun save(context:Context,side:EditorPaneSide){context.getSharedPreferences("layout",Context.MODE_PRIVATE).edit().putString("editorPaneSide",side.name).apply()}
}
