package com.erikbeans.wifidatabase.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface TokenStore {
    fun save(refreshToken: String, email: String?)
    fun refreshToken(): String?
    fun email(): String?
    fun clear()
}

@Singleton
class EncryptedTokenStore @Inject constructor(
    @ApplicationContext context: Context,
) : TokenStore {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "wifi_database_tokens",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun save(refreshToken: String, email: String?) {
        prefs.edit()
            .putString(KEY_REFRESH, refreshToken)
            .apply()
        if (email != null) {
            prefs.edit().putString(KEY_EMAIL, email).apply()
        }
    }

    override fun refreshToken(): String? = prefs.getString(KEY_REFRESH, null)

    override fun email(): String? = prefs.getString(KEY_EMAIL, null)

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EMAIL = "email"
    }
}
