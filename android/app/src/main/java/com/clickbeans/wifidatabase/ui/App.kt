package com.clickbeans.wifidatabase.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.util.Consumer
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavDeepLink
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import androidx.navigation.compose.rememberNavController
import com.clickbeans.wifidatabase.data.auth.SessionManager
import com.clickbeans.wifidatabase.data.auth.TokenManager
import com.clickbeans.wifidatabase.data.local.WelcomePrefs
import com.clickbeans.wifidatabase.ui.auth.CheckEmailScreen
import com.clickbeans.wifidatabase.ui.auth.ForgotPasswordScreen
import com.clickbeans.wifidatabase.ui.auth.LoginScreen
import com.clickbeans.wifidatabase.ui.auth.RegisterScreen
import com.clickbeans.wifidatabase.ui.auth.ResetPasswordScreen
import com.clickbeans.wifidatabase.ui.auth.VerifyScreen
import com.clickbeans.wifidatabase.ui.detail.DetailScreen
import com.clickbeans.wifidatabase.ui.map.MapScreen
import com.clickbeans.wifidatabase.ui.nearby.NearbyScreen
import com.clickbeans.wifidatabase.ui.search.SearchScreen
import com.clickbeans.wifidatabase.ui.settings.SettingsScreen
import com.clickbeans.wifidatabase.ui.share.ShareScreen
import com.clickbeans.wifidatabase.ui.welcome.WelcomeGate
import com.clickbeans.wifidatabase.ui.welcome.WelcomeScreen
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
    const val MAP = "map"
    const val SETTINGS = "settings"
    const val WELCOME = "welcome"
    const val SHARE = "share?ssid={ssid}&bssid={bssid}&security={security}"
    const val DETAIL = "detail/{apId}"

    val topLevel = listOf(NEARBY, SEARCH, MAP, SETTINGS)
}

fun shareRoute(ssid: String?, bssid: String?, security: String?): String =
    "share?ssid=${Uri.encode(ssid.orEmpty())}" +
        "&bssid=${Uri.encode(bssid.orEmpty())}" +
        "&security=${Uri.encode(security.orEmpty())}"

@HiltViewModel
class AppViewModel @Inject constructor(
    sessionManager: SessionManager,
    tokenManager: TokenManager,
    private val welcomePrefs: WelcomePrefs,
) : ViewModel() {

    val loggedIn = sessionManager.loggedIn
    var welcomeCompleted by mutableStateOf(welcomePrefs.completed)
        private set

    fun completeWelcome() {
        welcomePrefs.complete()
        welcomeCompleted = true
    }

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
            if (appViewModel.welcomeCompleted && currentRoute in Routes.topLevel) {
                BottomBar(navController, currentRoute)
            }
        },
    ) { padding ->
        AppNavHost(
            navController = navController,
            startDestination = if (loggedIn) Routes.NEARBY else Routes.LOGIN,
            welcomeCompleted = appViewModel.welcomeCompleted,
            onCompleteWelcome = appViewModel::completeWelcome,
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
            selected = currentRoute == Routes.MAP,
            onClick = { navController.navigateToTopLevel(Routes.MAP) },
            icon = { Icon(Icons.Outlined.Map, contentDescription = null) },
            label = { Text("Map") },
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
internal fun AppNavHost(
    navController: NavHostController,
    startDestination: String,
    welcomeCompleted: Boolean,
    onCompleteWelcome: () -> Unit,
    modifier: Modifier = Modifier,
    destinationContent: @Composable (NavBackStackEntry) -> Unit = { entry ->
        AppDestination(navController, entry)
    },
) {
    // Keep the graph stable when completion changes, including restored back stacks.
    val completed = rememberUpdatedState(welcomeCompleted)
    val completeWelcome = rememberUpdatedState(onCompleteWelcome)
    val screen = rememberUpdatedState(destinationContent)

    NavHost(navController, startDestination = startDestination, modifier = modifier) {
        fun NavGraphBuilder.destination(
            route: String,
            arguments: List<NamedNavArgument> = emptyList(),
            deepLinks: List<NavDeepLink> = emptyList(),
        ) {
            composable(route, arguments = arguments, deepLinks = deepLinks) { entry ->
                // Email links must remain usable before the introduction is completed.
                val bypassWelcome = route == Routes.VERIFY || route == Routes.RESET_PASSWORD
                WelcomeGate(
                    completed = completed.value || bypassWelcome,
                    onComplete = { completeWelcome.value() },
                ) {
                    screen.value(entry)
                }
            }
        }

        destination(Routes.LOGIN)
        destination(Routes.REGISTER)
        destination(
            Routes.CHECK_EMAIL,
            arguments = listOf(navArgument("email") { type = NavType.StringType; defaultValue = "" }),
        )
        destination(Routes.FORGOT_PASSWORD)
        destination(
            Routes.VERIFY,
            arguments = listOf(navArgument("token") { type = NavType.StringType; defaultValue = "" }),
            deepLinks = listOf(
                navDeepLink { uriPattern = "https://wifidb.clickbeans.com/verify?token={token}" },
            ),
        )
        destination(
            Routes.RESET_PASSWORD,
            arguments = listOf(navArgument("token") { type = NavType.StringType; defaultValue = "" }),
            deepLinks = listOf(
                navDeepLink { uriPattern = "https://wifidb.clickbeans.com/password-reset?token={token}" },
            ),
        )
        destination(Routes.NEARBY)
        destination(Routes.SEARCH)
        destination(Routes.MAP)
        destination(Routes.SETTINGS)
        composable(Routes.WELCOME) {
            WelcomeScreen(onContinue = { navController.popBackStack() }, isReplay = true)
        }
        destination(
            Routes.SHARE,
            arguments = listOf(
                navArgument("ssid") { type = NavType.StringType; defaultValue = "" },
                navArgument("bssid") { type = NavType.StringType; defaultValue = "" },
                navArgument("security") { type = NavType.StringType; defaultValue = "" },
            ),
        )
        destination(
            Routes.DETAIL,
            arguments = listOf(navArgument("apId") { type = NavType.StringType }),
        )
    }
}

@Composable
private fun AppDestination(navController: NavHostController, entry: NavBackStackEntry) {
    when (entry.destination.route) {
        Routes.LOGIN -> LoginScreen(navController)
        Routes.REGISTER -> RegisterScreen(navController)
        Routes.CHECK_EMAIL -> CheckEmailScreen(navController, entry.arguments?.getString("email").orEmpty())
        Routes.FORGOT_PASSWORD -> ForgotPasswordScreen(navController)
        Routes.VERIFY -> VerifyScreen(navController, entry.arguments?.getString("token").orEmpty())
        Routes.RESET_PASSWORD -> ResetPasswordScreen(navController, entry.arguments?.getString("token").orEmpty())
        Routes.NEARBY -> NearbyScreen(navController)
        Routes.SEARCH -> SearchScreen(navController)
        Routes.MAP -> MapScreen(navController)
        Routes.SETTINGS -> SettingsScreen(navController)
        Routes.SHARE -> ShareScreen(navController, entry.arguments?.getString("ssid").orEmpty())
        Routes.DETAIL -> DetailScreen(navController)
    }
}
