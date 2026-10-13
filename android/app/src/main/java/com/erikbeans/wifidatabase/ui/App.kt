package com.erikbeans.wifidatabase.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.util.Consumer
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import androidx.navigation.compose.rememberNavController
import com.erikbeans.wifidatabase.data.auth.SessionManager
import com.erikbeans.wifidatabase.data.auth.TokenManager
import com.erikbeans.wifidatabase.ui.auth.CheckEmailScreen
import com.erikbeans.wifidatabase.ui.auth.ForgotPasswordScreen
import com.erikbeans.wifidatabase.ui.auth.LoginScreen
import com.erikbeans.wifidatabase.ui.auth.RegisterScreen
import com.erikbeans.wifidatabase.ui.auth.ResetPasswordScreen
import com.erikbeans.wifidatabase.ui.auth.VerifyScreen
import com.erikbeans.wifidatabase.ui.detail.DetailScreen
import com.erikbeans.wifidatabase.ui.nearby.NearbyScreen
import com.erikbeans.wifidatabase.ui.search.SearchScreen
import com.erikbeans.wifidatabase.ui.settings.SettingsScreen
import com.erikbeans.wifidatabase.ui.share.ShareScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val CHECK_EMAIL = "check-email?email={email}"
    const val FORGOT_PASSWORD = "forgot-password"
    const val VERIFY = "verify?token={token}"
    const val RESET_PASSWORD = "reset-password?token={token}"
    const val NEARBY = "nearby"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val SHARE = "share?ssid={ssid}&bssid={bssid}&security={security}"
    const val DETAIL = "detail/{apId}"

    val topLevel = listOf(NEARBY, SEARCH, SETTINGS)
}

fun shareRoute(ssid: String?, bssid: String?, security: String?): String =
    "share?ssid=${Uri.encode(ssid.orEmpty())}" +
        "&bssid=${Uri.encode(bssid.orEmpty())}" +
        "&security=${Uri.encode(security.orEmpty())}"

@HiltViewModel
class AppViewModel @Inject constructor(
    sessionManager: SessionManager,
    tokenManager: TokenManager,
) : ViewModel() {

    val loggedIn = sessionManager.loggedIn

    init {
        tokenManager.loadSessionFromDisk()
    }
}

@Composable
fun App() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val appViewModel: AppViewModel = hiltViewModel()
    val loggedIn by appViewModel.loggedIn.collectAsStateWithLifecycle()

    DisposableEffect(activity) {
        val listener = Consumer<Intent> { navController.handleDeepLink(it) }
        if (activity != null) {
            activity.addOnNewIntentListener(listener)
        }
        onDispose {
            if (activity != null) {
                activity.removeOnNewIntentListener(listener)
            }
        }
    }

    var wasLoggedIn by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(loggedIn) {
        if (wasLoggedIn && !loggedIn) {
            navController.navigate(Routes.LOGIN) {
                popUpTo(0) { inclusive = true }
            }
        }
        wasLoggedIn = loggedIn
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute in Routes.topLevel) {
                BottomBar(navController, currentRoute)
            }
        },
    ) { padding ->
        AppNavHost(
            navController = navController,
            startDestination = if (loggedIn) Routes.NEARBY else Routes.LOGIN,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun BottomBar(navController: NavHostController, currentRoute: String?) {
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == Routes.NEARBY,
            onClick = { navController.navigateToTopLevel(Routes.NEARBY) },
            icon = { Icon(Icons.Outlined.NearMe, contentDescription = null) },
            label = { Text("Nearby") },
        )
        NavigationBarItem(
            selected = currentRoute == Routes.SEARCH,
            onClick = { navController.navigateToTopLevel(Routes.SEARCH) },
            icon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            label = { Text("Search") },
        )
        NavigationBarItem(
            selected = currentRoute == Routes.SETTINGS,
            onClick = { navController.navigateToTopLevel(Routes.SETTINGS) },
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            label = { Text("Settings") },
        )
    }
}

private fun NavHostController.navigateToTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(Routes.LOGIN) { LoginScreen(navController) }
        composable(Routes.REGISTER) { RegisterScreen(navController) }
        composable(
            route = Routes.CHECK_EMAIL,
            arguments = listOf(navArgument("email") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            CheckEmailScreen(
                navController = navController,
                email = entry.arguments?.getString("email").orEmpty(),
            )
        }
        composable(Routes.FORGOT_PASSWORD) { ForgotPasswordScreen(navController) }
        composable(
            route = Routes.VERIFY,
            arguments = listOf(navArgument("token") { type = NavType.StringType; defaultValue = "" }),
            deepLinks = listOf(
                navDeepLink { uriPattern = "wifidatabase://verify?token={token}" },
                navDeepLink { uriPattern = "https://wifidb.clickbeans.com/verify?token={token}" },
            ),
        ) { entry ->
            VerifyScreen(navController, entry.arguments?.getString("token").orEmpty())
        }
        composable(
            route = Routes.RESET_PASSWORD,
            arguments = listOf(navArgument("token") { type = NavType.StringType; defaultValue = "" }),
            deepLinks = listOf(
                navDeepLink { uriPattern = "wifidatabase://password-reset?token={token}" },
                navDeepLink { uriPattern = "https://wifidb.clickbeans.com/password-reset?token={token}" },
            ),
        ) { entry ->
            ResetPasswordScreen(navController, entry.arguments?.getString("token").orEmpty())
        }
        composable(Routes.NEARBY) { NearbyScreen(navController) }
        composable(Routes.SEARCH) { SearchScreen(navController) }
        composable(Routes.SETTINGS) { SettingsScreen(navController) }
        composable(
            route = Routes.SHARE,
            arguments = listOf(
                navArgument("ssid") { type = NavType.StringType; defaultValue = "" },
                navArgument("bssid") { type = NavType.StringType; defaultValue = "" },
                navArgument("security") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            ShareScreen(
                navController = navController,
                prefilledSsid = entry.arguments?.getString("ssid").orEmpty(),
            )
        }
        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("apId") { type = NavType.StringType }),
        ) { entry ->
            DetailScreen(navController)
        }
    }
}
