package com.clickbeans.wifidatabase

import android.content.Context
import com.clickbeans.wifidatabase.data.api.AuthApi
import com.clickbeans.wifidatabase.data.auth.SessionManager
import com.clickbeans.wifidatabase.data.auth.TokenManager
import com.clickbeans.wifidatabase.data.auth.TokenStore
import com.clickbeans.wifidatabase.data.local.WelcomePrefs
import com.clickbeans.wifidatabase.ui.AppViewModel
import java.lang.reflect.Proxy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36])
class WelcomePrefsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearWelcomePreference() {
        context.getSharedPreferences("wifi_database_welcome", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun model(savedRefreshToken: String? = null, session: SessionManager = SessionManager()): AppViewModel {
        val api = Proxy.newProxyInstance(AuthApi::class.java.classLoader, arrayOf(AuthApi::class.java)) { _, _, _ ->
            error("Showing the welcome screen must not call the network")
        } as AuthApi
        val store = object : TokenStore {
            override fun refreshToken() = savedRefreshToken
            override fun email(): String? = null
            override fun save(refreshToken: String, email: String?) = Unit
            override fun clear() = Unit
        }
        return AppViewModel(session, TokenManager(api, store, session), WelcomePrefs(context))
    }

    @Test
    fun `new and existing signed in installations see welcome until completion`() {
        assertFalse(model().welcomeCompleted)
        val signedIn = model(savedRefreshToken = "saved-session")
        assertTrue(signedIn.loggedIn.value)
        assertFalse(signedIn.welcomeCompleted)
        assertFalse(model().welcomeCompleted)
    }

    @Test
    fun `completion survives new preference and ViewModel instances`() {
        val initial = model()
        initial.completeWelcome()
        assertTrue(initial.welcomeCompleted)
        assertTrue(WelcomePrefs(context).completed)
        assertTrue(model().welcomeCompleted)
        assertTrue(model(savedRefreshToken = "another-account").welcomeCompleted)
    }

    @Test
    fun `sign out does not reset completion`() {
        val session = SessionManager()
        val initial = model(savedRefreshToken = "saved-session", session = session)
        initial.completeWelcome()
        session.markLoggedOut()
        assertFalse(initial.loggedIn.value)
        assertTrue(initial.welcomeCompleted)
        assertTrue(model().welcomeCompleted)
    }

    @Test
    fun `clearing installation data restores first use behavior`() {
        model().completeWelcome()
        clearWelcomePreference()
        assertFalse(model().welcomeCompleted)
    }
}
