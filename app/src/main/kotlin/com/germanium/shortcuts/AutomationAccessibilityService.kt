package com.germanium.shortcuts

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Optional system binding for automation; no window content, gestures or event data is collected. */
class AutomationAccessibilityService:AccessibilityService() {
    override fun onAccessibilityEvent(event:AccessibilityEvent?)=Unit
    override fun onInterrupt()=Unit
    override fun onServiceConnected(){
        super.onServiceConnected()
        val app=application as ShortcutsApplication
        app.scope.launch {try{if(!app.getSharedPreferences("permissions",MODE_PRIVATE).getBoolean("monitorPaused",false)&&app.automations.list().any{it.enabled})startAutomationMonitor(app)}catch(e:CancellationException){throw e}catch(e:Exception){app.diagnostics.trace("accessibility_monitor_start_failed",e)}}
    }
    companion object {
        fun enabled(context:Context):Boolean {
            val component=ComponentName(context,AutomationAccessibilityService::class.java)
            return context.getSystemService(AccessibilityManager::class.java).getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any{ComponentName(it.resolveInfo.serviceInfo.packageName,it.resolveInfo.serviceInfo.name)==component}
        }
    }
}
