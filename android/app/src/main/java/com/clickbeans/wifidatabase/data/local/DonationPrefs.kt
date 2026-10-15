package com.clickbeans.wifidatabase.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DonationPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences("wifi_database_donations", Context.MODE_PRIVATE)

    var neverAsk: Boolean
        get() = prefs.getBoolean(KEY_NEVER, false)
        private set(value) {
            prefs.edit().putBoolean(KEY_NEVER, value).apply()
        }

    var askCount: Int
        get() = prefs.getInt(KEY_ASKS, 0)
        private set(value) {
            prefs.edit().putInt(KEY_ASKS, value).apply()
        }

    var successfulConfirms: Int
        get() = prefs.getInt(KEY_CONFIRMS, 0)
        private set(value) {
            prefs.edit().putInt(KEY_CONFIRMS, value).apply()
        }

    var confirmsSinceLastAsk: Int
        get() = prefs.getInt(KEY_SINCE_ASK, 0)
        private set(value) {
            prefs.edit().putInt(KEY_SINCE_ASK, value).apply()
        }

    fun onWorkedConfirm() {
        successfulConfirms += 1
        confirmsSinceLastAsk += 1
    }

    fun recordAsk(never: Boolean) {
        askCount += 1
        confirmsSinceLastAsk = 0
        if (never) {
            neverAsk = true
        }
    }

    private companion object {
        const val KEY_NEVER = "never_ask"
        const val KEY_ASKS = "ask_count"
        const val KEY_CONFIRMS = "successful_confirms"
        const val KEY_SINCE_ASK = "confirms_since_ask"
    }
}
