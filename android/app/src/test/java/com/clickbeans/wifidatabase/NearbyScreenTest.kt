package com.clickbeans.wifidatabase

import android.Manifest
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.rememberNavController
import com.clickbeans.wifidatabase.ui.nearby.NearbyScreen
import com.clickbeans.wifidatabase.ui.nearby.NearbyUiState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36])
class NearbyScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val nearbyLifecycle = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle = registry
    }
    private var refreshes = 0

    @Before
    fun grantLocation() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    private fun showScreen(state: NearbyUiState = NearbyUiState()) {
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides nearbyLifecycle) {
                MaterialTheme {
                    NearbyScreen(rememberNavController(), state) { refreshes++ }
                }
            }
        }
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test
    fun `empty results can be pulled down to refresh`() {
        showScreen()
        compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(2, refreshes) }
    }

    @Test
    fun `empty results after an error can be pulled down or retried with the button`() {
        showScreen(NearbyUiState(error = "Couldn't get a GPS fix — try again outside."))
        compose.onNodeWithText("Couldn't get a GPS fix — try again outside.").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(2, refreshes) }
        compose.onNodeWithText("Refresh").performClick()
        compose.runOnIdle { assertEquals(3, refreshes) }
    }

    @Test
    fun `empty state refresh button is disabled while loading`() {
        showScreen(NearbyUiState(loading = true))
        compose.onNodeWithText("Refresh").assertIsNotEnabled()
    }

    @Test
    fun `foregrounding Nearby refreshes again and rechecks permission`() {
        showScreen()
        compose.runOnIdle {
            nearbyLifecycle.registry.currentState = Lifecycle.State.CREATED
            nearbyLifecycle.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.runOnIdle { assertEquals(2, refreshes) }
        compose.runOnIdle {
            nearbyLifecycle.registry.currentState = Lifecycle.State.CREATED
            shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            nearbyLifecycle.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.onNodeWithText("Grant location permission").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, refreshes) }
        compose.runOnIdle {
            nearbyLifecycle.registry.currentState = Lifecycle.State.CREATED
            grantLocation()
            nearbyLifecycle.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.runOnIdle { assertEquals(3, refreshes) }
    }

    @Test
    fun `returning from the map refreshes a restored empty Nearby tab`() {
        val showNearby = mutableStateOf(true)
        compose.setContent {
            val savedTabs = rememberSaveableStateHolder()
            val navController = rememberNavController()
            MaterialTheme {
                if (showNearby.value) {
                    savedTabs.SaveableStateProvider("nearby") {
                        CompositionLocalProvider(LocalLifecycleOwner provides nearbyLifecycle) {
                            NearbyScreen(navController, NearbyUiState()) { refreshes++ }
                        }
                    }
                } else {
                    Text("Map")
                }
            }
        }
        compose.runOnIdle {
            assertEquals(1, refreshes)
            nearbyLifecycle.registry.currentState = Lifecycle.State.CREATED
            showNearby.value = false
        }
        compose.onNodeWithText("Map").assertIsDisplayed()
        // Nearby remains stopped while the app is backgrounded and reopened on Map.
        compose.runOnIdle {
            assertEquals(1, refreshes)
            showNearby.value = true
        }
        compose.runOnIdle { nearbyLifecycle.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(2, refreshes) }
    }
}
