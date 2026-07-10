package com.dheyab.qiyas

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dheyab.qiyas.ui.dashboard.DashboardScreen
import com.dheyab.qiyas.ui.entry.AddBpScreen
import com.dheyab.qiyas.ui.entry.AddGlucoseScreen
import com.dheyab.qiyas.ui.navigation.Routes
import com.dheyab.qiyas.ui.theme.QiyasTheme
import dagger.hilt.android.AndroidEntryPoint

/** Key used to hand the saved reading's zone back to the previous screen for the snackbar. */
const val SAVED_ZONE_KEY = "saved_zone"

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QiyasTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    QiyasNavHost()
                }
            }
        }
    }
}

@Composable
private fun QiyasNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.DASHBOARD) {
        composable(Routes.DASHBOARD) { entry ->
            val savedZoneName by entry.savedStateHandle
                .getStateFlow<String?>(SAVED_ZONE_KEY, null)
                .collectAsStateWithLifecycle()
            DashboardScreen(
                savedZoneName = savedZoneName,
                onSavedZoneConsumed = { entry.savedStateHandle[SAVED_ZONE_KEY] = null },
                onAddGlucose = { navController.navigate(Routes.addGlucose()) },
                onAddBp = { navController.navigate(Routes.addBp()) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenReport = { navController.navigate(Routes.REPORT) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.ADD_GLUCOSE,
            arguments = listOf(navArgument("readingId") { type = NavType.LongType; defaultValue = -1L }),
        ) {
            AddGlucoseScreen(
                onDone = { zone -> navController.finishEntry(zone.name) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.ADD_BP,
            arguments = listOf(navArgument("readingId") { type = NavType.LongType; defaultValue = -1L }),
        ) {
            AddBpScreen(
                onDone = { zone -> navController.finishEntry(zone.name) },
                onBack = { navController.popBackStack() },
                onCrisisFollowUp = {
                    navController.popBackStack()
                    navController.navigate(Routes.addBp())
                },
            )
        }
        composable(Routes.HISTORY) { PlaceholderScreen("History") }
        composable(Routes.REPORT) { PlaceholderScreen("Report") }
        composable(Routes.SETTINGS) { PlaceholderScreen("Settings") }
        composable(Routes.ABOUT) { PlaceholderScreen("About") }
    }
}

private fun NavHostController.finishEntry(zoneName: String) {
    previousBackStackEntry?.savedStateHandle?.set(SAVED_ZONE_KEY, zoneName)
    popBackStack()
}

@Composable
private fun PlaceholderScreen(name: String) {
    Text(
        text = name,
        modifier = Modifier
            .fillMaxSize()
            .wrapContentSize(),
    )
}
