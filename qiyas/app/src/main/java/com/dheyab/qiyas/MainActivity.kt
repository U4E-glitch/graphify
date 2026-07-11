package com.dheyab.qiyas

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dheyab.qiyas.domain.model.ReadingType
import com.dheyab.qiyas.domain.report.ReportModel
import com.dheyab.qiyas.ui.about.AboutScreen
import com.dheyab.qiyas.ui.dashboard.DashboardScreen
import com.dheyab.qiyas.ui.entry.AddBpScreen
import com.dheyab.qiyas.ui.entry.AddGlucoseScreen
import com.dheyab.qiyas.ui.export.ExportViewModel
import com.dheyab.qiyas.ui.history.HistoryScreen
import com.dheyab.qiyas.ui.navigation.Routes
import com.dheyab.qiyas.ui.onboarding.OnboardingScreen
import com.dheyab.qiyas.ui.report.ReportScreen
import com.dheyab.qiyas.ui.settings.SettingsScreen
import com.dheyab.qiyas.ui.theme.QiyasTheme
import com.dheyab.qiyas.work.OPEN_REPORT_EXTRA
import dagger.hilt.android.AndroidEntryPoint

/** Key used to hand the saved reading's zone back to the previous screen for the snackbar. */
const val SAVED_ZONE_KEY = "saved_zone"

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openReport = intent?.getBooleanExtra(OPEN_REPORT_EXTRA, false) == true
        setContent {
            QiyasTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val disclaimerAccepted by viewModel.disclaimerAccepted.collectAsStateWithLifecycle()
                    when (disclaimerAccepted) {
                        null -> Unit // settings still loading — keep the splash background
                        false -> OnboardingScreen()
                        true -> QiyasNavHost(openReportOnLaunch = openReport)
                    }
                }
            }
        }
    }
}

@Composable
private fun QiyasNavHost(openReportOnLaunch: Boolean) {
    val navController = rememberNavController()

    LaunchedEffect(openReportOnLaunch) {
        if (openReportOnLaunch) navController.navigate(Routes.REPORT)
    }

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
        composable(Routes.HISTORY) { entry ->
            val savedZoneName by entry.savedStateHandle
                .getStateFlow<String?>(SAVED_ZONE_KEY, null)
                .collectAsStateWithLifecycle()
            HistoryScreen(
                savedZoneName = savedZoneName,
                onSavedZoneConsumed = { entry.savedStateHandle[SAVED_ZONE_KEY] = null },
                onEdit = { reading ->
                    when (reading.type) {
                        ReadingType.GLUCOSE -> navController.navigate(Routes.addGlucose(reading.id))
                        ReadingType.BP -> navController.navigate(Routes.addBp(reading.id))
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.REPORT) {
            val exportViewModel: ExportViewModel = hiltViewModel()
            val context = LocalContext.current
            var pendingPdfReport by remember { mutableStateOf<ReportModel?>(null) }
            val pdfLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/pdf")
            ) { uri ->
                val report = pendingPdfReport
                if (uri != null && report != null) exportViewModel.writePdf(context, uri, report)
                pendingPdfReport = null
            }
            val csvLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("text/csv")
            ) { uri -> uri?.let { exportViewModel.writeCsv(context, it) } }

            ReportScreen(
                onBack = { navController.popBackStack() },
                onExportPdf = { report ->
                    pendingPdfReport = report
                    pdfLauncher.launch(exportViewModel.pdfFileName(report))
                },
                onExportCsv = { csvLauncher.launch(exportViewModel.csvFileName()) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}

private fun NavHostController.finishEntry(zoneName: String) {
    previousBackStackEntry?.savedStateHandle?.set(SAVED_ZONE_KEY, zoneName)
    popBackStack()
}
