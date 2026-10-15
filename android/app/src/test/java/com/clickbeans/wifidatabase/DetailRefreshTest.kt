package com.clickbeans.wifidatabase

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.clickbeans.wifidatabase.ui.REFRESH_AFTER_DETAIL
import com.clickbeans.wifidatabase.ui.RefreshAfterDetail
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DetailRefreshTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `returning after deletion refreshes the previous destination exactly once`() {
        var refreshes = 0
        compose.setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "list") {
                composable("list") {
                    RefreshAfterDetail { refreshes++ }
                    Button(onClick = { nav.navigate("detail") }) { Text("Open detail") }
                }
                composable("detail") {
                    Button(onClick = {
                        nav.previousBackStackEntry!!.savedStateHandle[REFRESH_AFTER_DETAIL] = true
                        nav.popBackStack()
                    }) { Text("Delete network") }
                }
            }
        }
        compose.onNodeWithText("Open detail").performClick()
        compose.onNodeWithText("Delete network").performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
        compose.onNodeWithText("Open detail").performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }
}
