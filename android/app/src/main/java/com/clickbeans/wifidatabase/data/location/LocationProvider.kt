package com.clickbeans.wifidatabase.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class LocationProvider @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)

    @SuppressLint("MissingPermission")
    suspend fun awaitFix(timeoutMillis: Long = 15_000): Location? {
        val last = client.lastLocation.awaitOrNull()
        if (last != null && System.currentTimeMillis() - last.time < RECENT_WINDOW_MILLIS) {
            return last
        }
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(timeoutMillis)
            .build()
        return client.getCurrentLocation(request, null).awaitOrNull()
    }

    private companion object {
        const val RECENT_WINDOW_MILLIS = 60_000L
    }
}

private suspend fun <T : Any> Task<T>.awaitOrNull(): T? =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) {
                continuation.resume(task.result)
            } else {
                continuation.resume(null)
            }
        }
    }
