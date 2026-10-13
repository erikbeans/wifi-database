package com.erikbeans.wifidatabase.data.crash

import android.util.Log
import com.erikbeans.wifidatabase.AppConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class CrashPayload(
    val stack_trace: String,
    val device_model: String,
    val android_version: String,
    val app_version: String,
)

@Singleton
class CrashReporter @Inject constructor() {

    private val json = Json { encodeDefaults = false }
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                report(throwable)
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun report(throwable: Throwable) {
        val payload = CrashPayload(
            stack_trace = Log.getStackTraceString(throwable).take(8000),
            device_model = android.os.Build.MODEL ?: "",
            android_version = android.os.Build.VERSION.RELEASE ?: "",
            app_version = com.erikbeans.wifidatabase.BuildConfig.VERSION_NAME,
        )
        val body = json.encodeToString(CrashPayload.serializer(), payload)
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(AppConfig.CRASH_REPORT_URL)
            .post(body)
            .build()
        client.newCall(request).execute().close()
    }
}
