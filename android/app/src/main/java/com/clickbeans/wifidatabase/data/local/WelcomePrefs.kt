package com.clickbeans.wifidatabase.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WelcomePrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("wifi_database_welcome", Context.MODE_PRIVATE)

    val completed: Boolean
        get() = prefs.getBoolean("completed", false)

    fun complete() {
        prefs.edit().putBoolean("completed", true).apply()
    }
}
