package com.germanium.shortcuts

import android.content.Context
import android.net.wifi.WifiManager
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

internal object AndroidWifi {
    fun clean(ssid: String?) = ssid.orEmpty().removeSurrounding("\"").takeUnless { it == WifiManager.UNKNOWN_SSID }.orEmpty()
    @Suppress("DEPRECATION") fun current(context: Context): String = runCatching {
        clean((context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.ssid)
    }.getOrDefault("")
    @Suppress("DEPRECATION") fun configured(context: Context): List<String> = runCatching {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return@runCatching emptyList()
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).configuredNetworks.orEmpty().map { clean(it.SSID) }.filter { it.isNotBlank() }.distinct()
    }.getOrDefault(emptyList())
}
