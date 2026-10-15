package com.clickbeans.wifidatabase.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubmissionMemory @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences("wifi_database_submissions", Context.MODE_PRIVATE)

    fun markMyPassword(passwordId: String) {
        prefs.edit().putBoolean("pw_$passwordId", true).apply()
    }

    fun markMyAccessPoint(apId: String) {
        prefs.edit().putBoolean("ap_$apId", true).apply()
    }

    fun isMyPassword(passwordId: String): Boolean = prefs.getBoolean("pw_$passwordId", false)

    fun isMyAccessPoint(apId: String): Boolean = prefs.getBoolean("ap_$apId", false)

    fun forgetPassword(passwordId: String) {
        prefs.edit().remove("pw_$passwordId").apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
