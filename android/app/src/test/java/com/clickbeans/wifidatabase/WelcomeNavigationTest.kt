package com.clickbeans.wifidatabase

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.clickbeans.wifidatabase.ui.AppNavHost
import com.clickbeans.wifidatabase.ui.Routes
import com.clickbeans.wifidatabase.ui.welcome.WelcomeScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36])
class WelcomeNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var nav: NavHostController
    private val completed: MutableState<Boolean> = mutableStateOf(false)
    private var completions = 0
    private val createdScreens = mutableListOf<String>()
    private lateinit var launchIntent: Intent

    @Before
    fun rememberLaunchIntent() {
        launchIntent = compose.activity.intent
    }

    @After
    fun restoreLaunchIntent() {
        // ActivityScenario uses the original intent to track activity destruction.
        compose.activity.intent = launchIntent
    }

    private fun show(start: String = Routes.NEARBY) {
        compose.setContent {
            nav = rememberNavController()
            MaterialTheme {
                AppNavHost(nav, start, completed.value, {
                    completions++
                    completed.value = true
                }) { entry ->
                    LaunchedEffect(entry.id) { createdScreens.add(entry.destination.route.orEmpty()) }
                    Text("Destination: ${entry.destination.route}")
                    if (entry.destination.route == Routes.SETTINGS) {
                        Button(onClick = { nav.navigate(Routes.WELCOME) }) { Text("Welcome & tips") }
                    }
                }
            }
        }
    }

    private fun assertContinueReveals(route: String) {
        compose.onNodeWithText("Welcome to WiFi Database").assertIsDisplayed()
        compose.runOnIdle { assertTrue(createdScreens.isEmpty()) }
        val originalEntry = compose.runOnIdle { nav.currentBackStackEntry!!.id }
        compose.onNodeWithText("Get started").assertIsDisplayed().performClick()
        compose.onNodeWithText("Destination: $route").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, completions)
            assertEquals(originalEntry, nav.currentBackStackEntry!!.id)
            assertEquals(listOf(route), createdScreens)
        }
    }

    @Test
    fun `signed in first use defers Nearby initialization until Get started`() {
        show()
        assertContinueReveals(Routes.NEARBY)
    }

    @Test
    fun `signed out first use continues to sign in`() {
        show(Routes.LOGIN)
        assertContinueReveals(Routes.LOGIN)
    }

    @Test
    fun `returning installation skips welcome`() {
        completed.value = true
        show()
        compose.onNodeWithText("Destination: ${Routes.NEARBY}").assertIsDisplayed()
        compose.onNodeWithText("Get started").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, completions) }
    }

    @Test
    fun `restored Map remains gated without losing its destination`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            nav = rememberNavController()
            MaterialTheme {
                AppNavHost(nav, Routes.NEARBY, completed.value, { completed.value = true }) { entry ->
                    Text("Destination: ${entry.destination.route}")
                }
            }
        }
        compose.runOnIdle { nav.navigate(Routes.MAP) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Welcome to WiFi Database").assertIsDisplayed()
        compose.runOnIdle { assertEquals(Routes.MAP, nav.currentDestination!!.route) }
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Destination: ${Routes.MAP}").assertIsDisplayed()
    }

    @Test
    fun `restored Detail keeps access point arguments while gated`() {
        show()
        compose.runOnIdle { nav.navigate("detail/existing-access-point") }
        compose.onNodeWithText("Get started").assertIsDisplayed().performClick()
        compose.onNodeWithText("Destination: ${Routes.DETAIL}").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("existing-access-point", nav.currentBackStackEntry!!.arguments!!.getString("apId"))
            assertEquals(listOf(Routes.DETAIL), createdScreens)
        }
    }

    @Test
    fun `warm verification and reset links bypass welcome without completing it`() {
        show()
        for ((host, route) in listOf("verify" to Routes.VERIFY, "password-reset" to Routes.RESET_PASSWORD)) {
            for (prefix in listOf("wifidatabase://", "https://wifidb.clickbeans.com/")) {
                compose.runOnIdle {
                    assertTrue(nav.handleDeepLink(Intent(Intent.ACTION_VIEW, Uri.parse("$prefix$host?token=keep-me"))))
                }
                compose.onNodeWithText("Destination: $route").assertIsDisplayed()
                compose.onNodeWithText("Get started").assertDoesNotExist()
                compose.runOnIdle {
                    assertEquals("keep-me", nav.currentBackStackEntry!!.arguments!!.getString("token"))
                    assertFalse(completed.value)
                }
            }
        }
        compose.runOnIdle { nav.navigate(Routes.NEARBY) }
        compose.onNodeWithText("Get started").assertIsDisplayed()
    }

    private fun coldLink(uri: String, route: String) {
        compose.activity.intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        show(Routes.LOGIN)
        compose.onNodeWithText("Destination: $route").assertIsDisplayed()
        compose.onNodeWithText("Get started").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("cold-token", nav.currentBackStackEntry!!.arguments!!.getString("token"))
            assertEquals(0, completions)
        }
    }

    @Test
    fun `cold https verification link bypasses welcome`() {
        coldLink("https://wifidb.clickbeans.com/verify?token=cold-token", Routes.VERIFY)
    }

    @Test
    fun `cold legacy reset link bypasses welcome`() {
        coldLink("wifidatabase://password-reset?token=cold-token", Routes.RESET_PASSWORD)
    }

    @Test
    fun `Settings replay returns with Done or Back and never resets completion`() {
        completed.value = true
        show(Routes.SETTINGS)
        compose.onNodeWithText("Welcome & tips").performClick()
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        compose.onNodeWithText("Destination: ${Routes.SETTINGS}").assertIsDisplayed()
        compose.onNodeWithText("Welcome & tips").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Destination: ${Routes.SETTINGS}").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(completed.value)
            assertEquals(0, completions)
        }
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `unfolded light layout shows all three suggestions and the button`() {
        compose.setContent {
            MaterialTheme {
                WelcomeScreen(onContinue = { completions++ })
            }
        }
        compose.onNodeWithText("Share a password").assertIsDisplayed()
        compose.onNodeWithText("Find WiFi while traveling").assertIsDisplayed()
        compose.onNodeWithText("Cast your vote").assertIsDisplayed()
        compose.onNodeWithText("Get started").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, completions) }
    }

    @Test
    fun `small dark screen with large text can scroll tips while button stays visible`() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    Box(Modifier.size(320.dp, 300.dp)) {
                        WelcomeScreen(onContinue = { completions++ })
                    }
                }
            }
        }
        compose.onNodeWithText("Get started").assertIsDisplayed()
        compose.onNodeWithText("Cast your vote").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Get started").performClick()
        compose.runOnIdle { assertEquals(1, completions) }
    }
}
