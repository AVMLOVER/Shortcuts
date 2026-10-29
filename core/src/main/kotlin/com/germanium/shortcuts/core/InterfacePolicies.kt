package com.germanium.shortcuts.core

enum class EditorPaneSide { LEFT, RIGHT }
data class AdaptiveInterface(val largeDevice:Boolean,val sidePane:Boolean,val paneFraction:Float,val navigationRail:Boolean)
object InterfacePolicies {
    fun adaptive(widthDp:Int,heightDp:Int,deviceSmallestDp:Int,foldable:Boolean):AdaptiveInterface {
        val large=foldable||deviceSmallestDp>=600
        return AdaptiveInterface(large,large&&widthDp>=600,if(foldable)0.5f else 1f/3f,large&&widthDp>=600&&widthDp>heightDp)
    }
    fun variableOptions(type:String)=ActionOutputs.produces(type)&&!ActionOutputs.isVariableAction(type)
    fun hasMore(type:String)=variableOptions(type)||type=="show_result"
    fun widgetResult(explicitPresentation:Boolean,quickLook:Value?,result:RunResult?,error:String?,notice:String?):Value? = when {
        error!=null -> Value.Text(error)
        explicitPresentation -> null
        notice!=null -> Value.Text(notice)
        quickLook!=null -> quickLook
        else -> result?.outputValue ?: Value.Text("")
    }
}
