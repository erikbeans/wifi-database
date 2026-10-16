package com.clickbeans.wifidatabase

import android.Manifest
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.clickbeans.wifidatabase.domain.NearbyMatcher
import com.clickbeans.wifidatabase.domain.model.NearbyAccessPoint
import com.clickbeans.wifidatabase.domain.model.NearbyRow
import com.clickbeans.wifidatabase.domain.model.ScannedNetwork
import com.clickbeans.wifidatabase.ui.Routes
import com.clickbeans.wifidatabase.ui.nearby.NearbyScreen
import com.clickbeans.wifidatabase.ui.nearby.NearbyUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    private lateinit var nav: NavHostController

    private fun showRows(rows: List<NearbyRow>) {
        compose.setContent {
            nav = rememberNavController()
            MaterialTheme {
                NavHost(nav, startDestination = Routes.NEARBY) {
                    composable(Routes.NEARBY) {
                        CompositionLocalProvider(LocalLifecycleOwner provides nearbyLifecycle) {
                            NearbyScreen(nav, NearbyUiState(rows = rows)) { refreshes++ }
                        }
                    }
                    composable(Routes.SHARE, arguments = listOf("ssid", "bssid", "security").map {
                        navArgument(it) { type = NavType.StringType; defaultValue = "" }
                    }) { Text("Share destination") }
                    composable(Routes.DETAIL, arguments = listOf(
                        navArgument("apId") { type = NavType.StringType }
                    )) { Text("Password destination") }
                }
            }
        }
    }

    private fun assertDestination(route: String) {
        compose.runOnIdle {
            assertEquals(route, nav.currentDestination?.route)
            assertEquals(Routes.NEARBY, nav.previousBackStackEntry?.destination?.route)
        }
    }

    private fun backToNearby() {
        compose.runOnIdle { assertTrue(nav.popBackStack()) }
        compose.runOnIdle { assertEquals(Routes.NEARBY, nav.currentDestination?.route) }
    }

    private fun databaseNetwork(ssid: String) = NearbyAccessPoint(
        "ap", ssid, "aa:bb:cc:dd:ee:ff", "WPA2", 0.0, 0.0, 12.0, 1, 2,
    )

    @Test
    fun `scanned row title icon and padding open share with encoded network arguments`() {
        val network = ScannedNetwork("Cafe & Tea / 50% + #?= WiFi", "aa:bb:cc:dd:ee:ff", "WPA2")
        showRows(NearbyMatcher.merge(listOf(network), emptyList()))
        val addAction = SemanticsMatcher("Add password action") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == "Add password for ${network.ssid}"
        }
        repeat(3) { area ->
            if (area == 0) {
                compose.onNodeWithText(network.ssid, useUnmergedTree = true).performTouchInput { click() }
            } else {
                compose.onNode(addAction).performTouchInput {
                    click(if (area == 1) Offset(28.dp.toPx(), center.y) else Offset(2f, 2f))
                }
            }
            assertDestination(Routes.SHARE)
            compose.runOnIdle {
                val args = nav.currentBackStackEntry!!.arguments!!
                assertEquals(network.ssid, args.getString("ssid"))
                assertEquals(network.bssid, args.getString("bssid"))
                assertEquals(network.security, args.getString("security"))
            }
            backToNearby()
        }
    }

    @Test
    fun `password chip consumes touch while the scanned row opens share`() {
        val network = ScannedNetwork("Cafe", "aa:bb:cc:dd:ee:ff", "WPA2")
        showRows(NearbyMatcher.merge(listOf(network), listOf(databaseNetwork(network.ssid))))
        compose.onNodeWithText(network.ssid, useUnmergedTree = true).performTouchInput { click() }
        assertDestination(Routes.SHARE)
        backToNearby()
        compose.onNodeWithText("Password · score 2").performTouchInput { click() }
        assertDestination(Routes.DETAIL)
        compose.runOnIdle { assertEquals("ap", nav.currentBackStackEntry!!.arguments!!.getString("apId")) }
        backToNearby()
    }

    @Test
    fun `database only row is inert but its password chip opens detail`() {
        showRows(NearbyMatcher.merge(emptyList(), listOf(databaseNetwork("Database cafe"))))
        compose.onNodeWithText("Database cafe", useUnmergedTree = true).performTouchInput { click() }
        compose.runOnIdle { assertEquals(Routes.NEARBY, nav.currentDestination?.route) }
        compose.onNodeWithText("Password · score 2").performTouchInput { click() }
        assertDestination(Routes.DETAIL)
    }

    @Test
    fun `swiping clickable rows scrolls and pull to refresh does not navigate`() {
        showRows(NearbyMatcher.merge((1..30).map { ScannedNetwork("Network $it", null, "WPA2") }, emptyList()))
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        compose.onNode(hasScrollAction()).performScrollToIndex(20)
        compose.onNodeWithText("Network 21").assertIsDisplayed()
        compose.runOnIdle { assertEquals(Routes.NEARBY, nav.currentDestination?.route) }
        compose.onNode(hasScrollAction()).performScrollToIndex(0)
        val before = compose.runOnIdle { refreshes }
        compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertEquals(before + 1, refreshes)
            assertEquals(Routes.NEARBY, nav.currentDestination?.route)
        }
    }

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
