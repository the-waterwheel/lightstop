package com.lightmeter.rawmeter

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.SystemClock
import android.util.Log

/**
 * Owns the parameter-record location request lifecycle: permission level, a single active request
 * generation, cancellation, timeout and the cached validated fix. It holds only the application
 * context and never blocks a capture.
 *
 * All public methods must be called on the main thread.
 */
internal class ParameterLocationCoordinator(
    context: Context,
    private val mainHandler: Handler,
    private val onFixChanged: (LocationFix?) -> Unit,
    private val onProviderUnavailable: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var generation = 0
    private var listener: LocationListener? = null
    private var cancellationSignal: CancellationSignal? = null
    private var timeoutTask: Runnable? = null
    private var cached: CachedFix? = null

    private data class CachedFix(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float?,
        val provider: String?,
        val permissionQuality: LocationPermissionQuality,
        val elapsedRealtimeNs: Long,
    )

    fun permissionQuality(): LocationPermissionQuality {
        val fine = appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (fine) return LocationPermissionQuality.FINE
        val coarse = appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return if (coarse) LocationPermissionQuality.COARSE else LocationPermissionQuality.NONE
    }

    /** True when at least one usable provider is currently enabled. */
    fun systemLocationAvailable(): Boolean {
        val locationManager = manager ?: return false
        return PROVIDERS.any { name ->
            runCatching { locationManager.isProviderEnabled(name) }.getOrDefault(false)
        }
    }

    @SuppressLint("MissingPermission")
    fun requestFix(): Boolean {
        val quality = permissionQuality()
        if (quality == LocationPermissionQuality.NONE) return false
        val locationManager = manager ?: return false
        val provider = providerFor(quality, locationManager) ?: run {
            onProviderUnavailable()
            return false
        }
        val requestGeneration = ++generation
        clearActiveRequest()
        // A last-known fix is a candidate only if it passes the freshness/accuracy policy.
        locationManager.getLastKnownLocation(provider)?.let { location ->
            accept(location, provider, quality, requestGeneration)
        }
        requestFreshFix(locationManager, provider, quality, requestGeneration)
        return true
    }

    fun cancel() {
        generation += 1
        clearActiveRequest()
        if (cached != null) {
            cached = null
            onFixChanged(null)
        }
    }

    /** Freezes the currently valid fix for one capture, or null when nothing fresh is available. */
    fun snapshot(): LocationFix? {
        val fix = cached ?: return null
        val ageMs = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNs) / 1_000_000L
        val candidate = fix.toLocationFix(ageMs)
        if (!ParameterLocationPolicy.isAcceptable(candidate)) {
            cached = null
            onFixChanged(null)
            return null
        }
        return candidate
    }

    private fun providerFor(
        quality: LocationPermissionQuality,
        locationManager: LocationManager,
    ): String? {
        val candidates = if (quality == LocationPermissionQuality.FINE) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }
        return candidates.firstOrNull { name ->
            runCatching { locationManager.isProviderEnabled(name) }.getOrDefault(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshFix(
        locationManager: LocationManager,
        provider: String,
        quality: LocationPermissionQuality,
        requestGeneration: Int,
    ) {
        scheduleTimeout(requestGeneration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            cancellationSignal = signal
            try {
                locationManager.getCurrentLocation(provider, signal, appContext.mainExecutor) { location ->
                    mainHandler.post {
                        if (requestGeneration != generation) return@post
                        location?.let { accept(it, provider, quality, requestGeneration) }
                    }
                }
            } catch (error: SecurityException) {
                Log.w(TAG, "Location permission was revoked during a request", error)
                onProviderUnavailable()
                clearActiveRequest()
            } catch (error: Exception) {
                Log.w(TAG, "Unable to request a location fix", error)
                onProviderUnavailable()
                clearActiveRequest()
            }
            return
        }
        val singleListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (requestGeneration != generation) return
                accept(location, provider, quality, requestGeneration)
            }

            @Deprecated("Legacy LocationListener callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                if (requestGeneration != generation) return
                clearActiveRequest()
                onProviderUnavailable()
            }
        }
        listener = singleListener
        try {
            locationManager.requestSingleUpdate(provider, singleListener, mainHandler.looper)
        } catch (error: SecurityException) {
            Log.w(TAG, "Location permission was revoked during a request", error)
            clearActiveRequest()
            onProviderUnavailable()
        } catch (error: Exception) {
            Log.w(TAG, "Unable to request a single location update", error)
            clearActiveRequest()
            onProviderUnavailable()
        }
    }

    private fun accept(
        location: Location,
        provider: String,
        quality: LocationPermissionQuality,
        requestGeneration: Int,
    ) {
        if (requestGeneration != generation) return
        val fix = CachedFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            provider = provider,
            permissionQuality = quality,
            elapsedRealtimeNs = location.elapsedRealtimeNanos.takeIf { it > 0L }
                ?: SystemClock.elapsedRealtimeNanos(),
        )
        val ageMs = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNs) / 1_000_000L
        val candidate = fix.toLocationFix(ageMs)
        if (!ParameterLocationPolicy.isAcceptable(candidate)) return
        cached = fix
        clearActiveRequest()
        onFixChanged(candidate)
    }

    private fun CachedFix.toLocationFix(ageMs: Long): LocationFix = LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
        provider = provider,
        ageMs = ageMs,
        permissionQuality = permissionQuality,
    )

    private fun scheduleTimeout(requestGeneration: Int) {
        val task = Runnable {
            if (requestGeneration != generation) return@Runnable
            clearActiveRequest()
        }
        timeoutTask = task
        mainHandler.postDelayed(task, ParameterLocationPolicy.REQUEST_TIMEOUT_MS)
    }

    private fun clearActiveRequest() {
        listener?.let { active ->
            runCatching { manager?.removeUpdates(active) }
        }
        listener = null
        cancellationSignal?.cancel()
        cancellationSignal = null
        timeoutTask?.let(mainHandler::removeCallbacks)
        timeoutTask = null
    }

    private companion object {
        private const val TAG = "ParameterLocation"
        private val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
    }
}
