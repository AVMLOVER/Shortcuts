package com.germanium.shortcuts

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.*

/** A cancellable, foreground one-shot fix. Never substitutes an old cached coordinate. */
internal class AndroidLocation(private val context: Context, private val interactions: Interactions) {
    @SuppressLint("MissingPermission")
    suspend fun current(): Value {
        if (!hasPermission(context)) interactions.ask(Interaction.Permissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)))
        if (!hasPermission(context)) throw Problem("err_permission")
        val manager = context.getSystemService(LocationManager::class.java)
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = listOfNotNull(if (fine) LocationManager.GPS_PROVIDER else null, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) throw Problem("err_location")
        val fix = withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine<Location> { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (continuation.isActive && location.latitude.isFinite() && location.longitude.isFinite()) {
                            manager.removeUpdates(this)
                            continuation.resumeWith(Result.success(location))
                        }
                    }
                    override fun onProviderDisabled(provider: String) {}
                    override fun onProviderEnabled(provider: String) {}
                    @Deprecated("Legacy Android callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                try {
                    providers.forEach { manager.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) }
                } catch (error: Exception) {
                    manager.removeUpdates(listener)
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }
            }
        } ?: throw Problem("err_location")
        return value(fix)
    }
    companion object {
        fun hasPermission(context: Context): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        internal fun value(location: Location): Value.Dictionary = Value.Dictionary(linkedMapOf(
            "latitude" to Value.Number(location.latitude.toBigDecimal()), "longitude" to Value.Number(location.longitude.toBigDecimal()),
            "accuracy" to if (location.hasAccuracy()) Value.Number(location.accuracy.toBigDecimal()) else Value.Text(""),
            "altitude" to if (location.hasAltitude()) Value.Number(location.altitude.toBigDecimal()) else Value.Text(""),
            "timestamp" to Value.Number(location.time.toBigDecimal()), "provider" to Value.Text(location.provider.orEmpty()),
        ))
    }
}
