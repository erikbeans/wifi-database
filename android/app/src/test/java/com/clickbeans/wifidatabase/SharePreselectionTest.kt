package com.clickbeans.wifidatabase

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.rememberNavController
import com.clickbeans.wifidatabase.data.api.ApiService
import com.clickbeans.wifidatabase.data.local.SubmissionMemory
import com.clickbeans.wifidatabase.data.location.LocationProvider
import com.clickbeans.wifidatabase.data.wifi.WifiScanner
import com.clickbeans.wifidatabase.domain.repository.AccessPointRepository
import com.clickbeans.wifidatabase.ui.share.ShareScreen
import com.clickbeans.wifidatabase.ui.share.ShareViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.Retrofit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36], application = Application::class)
class SharePreselectionTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private lateinit var context: Application
    private lateinit var wifi: WifiManager
    private lateinit var viewModel: ShareViewModel

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        shadowOf(wifi).setScanResults(emptyList())
        val api = Retrofit.Builder().baseUrl("https://example.invalid/").build().create(ApiService::class.java)
        viewModel = ShareViewModel(AccessPointRepository(api), LocationProvider(context),
            WifiScanner(context), SubmissionMemory(context))
        store.put("share", viewModel)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun scan(vararg names: String) {
        shadowOf(wifi).setScanResults(names.mapIndexed { index, name ->
            ScanResult().apply {
                SSID = name
                BSSID = "aa:bb:cc:dd:ee:%02x".format(index)
                capabilities = "[WPA2-PSK-CCMP][ESS]"
            }
        })
        context.sendBroadcast(Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            .putExtra(WifiManager.EXTRA_RESULTS_UPDATED, true))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `share screen shows requested scanned network and retains its metadata`() {
        val ssid = "Cafe & Tea / 50% + #?= WiFi"
        scan("Another network", ssid)
        compose.setContent {
            MaterialTheme { ShareScreen(rememberNavController(), ssid, viewModel) }
        }
        compose.onNodeWithText(ssid).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(ssid, viewModel.state.selected?.ssid)
            assertEquals("aa:bb:cc:dd:ee:01", viewModel.state.selected?.bssid)
            assertEquals("WPA2", viewModel.state.selected?.security)
        }
    }

    @Test
    fun `preselection waits for matching scan without inventing a network`() {
        viewModel.preselect("Requested cafe")
        assertNull(viewModel.state.selected)
        scan("Another network")
        assertNull(viewModel.state.selected)
        scan("Another network", "Requested cafe")
        assertEquals("Requested cafe", viewModel.state.selected?.ssid)
    }

    @Test
    fun `manual selection cancels pending preselection and survives later scans`() {
        viewModel.preselect("Requested cafe")
        scan("Another network")
        viewModel.selectNetwork(viewModel.state.networks.single())
        scan("Requested cafe", "Another network")
        assertEquals("Another network", viewModel.state.selected?.ssid)
        assertEquals("aa:bb:cc:dd:ee:01", viewModel.state.selected?.bssid)
        // Recreating Share's effect must not replace a user's existing choice.
        viewModel.preselect("Requested cafe")
        assertEquals("Another network", viewModel.state.selected?.ssid)
    }

    @Test
    fun `unavailable request stays unselected after rescan and generic add stays blank`() {
        scan("Another network")
        viewModel.preselect("")
        assertNull(viewModel.state.selected)
        viewModel.preselect("Unavailable cafe")
        viewModel.rescan()
        assertNull(viewModel.state.selected)
        assertEquals(listOf("Another network"), viewModel.state.networks.map { it.ssid })
    }
}
