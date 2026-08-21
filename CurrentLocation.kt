package fr.velo.cadence.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import fr.velo.cadence.model.GeoPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Position ponctuelle, utilisee pour centrer la carte et proposer un point de
 * depart. On demande d'abord la derniere position connue, instantanee, puis
 * une position fraiche si elle manque.
 */
object CurrentLocation {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun get(context: Context): GeoPoint? {
        if (!hasPermission(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)

        val last = suspendCancellableCoroutine<Location?> { continuation ->
            client.lastLocation
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resume(null) }
        }
        if (last != null) return GeoPoint(last.latitude, last.longitude, last.altitude)

        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setDurationMillis(10_000)
            .setMaxUpdateAgeMillis(60_000)
            .build()

        val fresh = suspendCancellableCoroutine<Location?> { continuation ->
            client.getCurrentLocation(request, null)
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resume(null) }
        }
        return fresh?.let { GeoPoint(it.latitude, it.longitude, it.altitude) }
    }
}
