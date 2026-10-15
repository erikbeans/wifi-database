package com.clickbeans.wifidatabase.data.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SuggestionResult {
    data object Saved : SuggestionResult
    data object AlreadySaved : SuggestionResult
    data object Disallowed : SuggestionResult
    data object Unsupported : SuggestionResult
    data class Failed(val statusCode: Int?, val detail: String?) : SuggestionResult
}

@Singleton
class WifiSuggester @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun suggest(ssid: String, password: String, security: String?): SuggestionResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return SuggestionResult.Unsupported
        }
        if (password.length !in 8..63) {
            return SuggestionResult.Failed(
                null,
                "password is ${password.length} characters; phones require 8–63",
            )
        }
        val manager = wifiManager ?: return SuggestionResult.Failed(null, "no WifiManager")
        val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
        when (security?.uppercase()) {
            "WPA3" -> builder.setWpa3Passphrase(password)
            "OPEN" -> Unit
            else -> builder.setWpa2Passphrase(password)
        }
        val status = try {
            manager.addNetworkSuggestions(listOf(builder.build()))
        } catch (e: Exception) {
            Log.e(TAG, "addNetworkSuggestions threw", e)
            return SuggestionResult.Failed(null, "${e.javaClass.simpleName}: ${e.message}")
        }
        Log.i(TAG, "addNetworkSuggestions status=$status ssid=$ssid security=$security")
        return when (status) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> SuggestionResult.Saved
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> SuggestionResult.AlreadySaved
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED -> SuggestionResult.Disallowed
            else -> SuggestionResult.Failed(status, null)
        }
    }

    private companion object {
        const val TAG = "WifiSuggester"
    }
}
