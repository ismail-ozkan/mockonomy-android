package com.mockonomy.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.mockonomy.ui.screens.AutomationSettingsScreen
import com.mockonomy.ui.screens.EnvironmentDetailScreen
import com.mockonomy.ui.screens.EnvironmentListScreen
import com.mockonomy.ui.screens.ImportScreen
import com.mockonomy.ui.screens.LicenseTextScreen
import com.mockonomy.ui.screens.LogDetailScreen
import com.mockonomy.ui.screens.OpenSourceLicensesScreen
import com.mockonomy.ui.screens.ResponseEditScreen
import com.mockonomy.ui.screens.RouteEditScreen
import com.mockonomy.ui.screens.SettingsScreen
import com.mockonomy.ui.screens.SetupGuideScreen

/**
 * The app's only `NavHost` — see the [MockonomyDestination] KDoc for the full tree.
 *
 * Back navigation and returning to the right screen after rotation or process death come
 * from the back stack Navigation Compose builds with [rememberNavController]; nothing is
 * managed by hand.
 */
@Composable
fun MockonomyNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(
        navController = navController,
        startDestination = MockonomyDestination.EnvironmentList,
    ) {
        composable<MockonomyDestination.EnvironmentList> {
            EnvironmentListScreen(
                onEnvironmentClick = { environmentId ->
                    navController.navigate(MockonomyDestination.EnvironmentDetail(environmentId))
                },
                onSetupGuideClick = { navController.navigate(MockonomyDestination.SetupGuide) },
                onImportClick = { navController.navigate(MockonomyDestination.Import) },
                onAutomationClick = { navController.navigate(MockonomyDestination.AutomationSettings) },
                onSettingsClick = { navController.navigate(MockonomyDestination.Settings) },
            )
        }

        composable<MockonomyDestination.AutomationSettings> {
            AutomationSettingsScreen(onBack = { navController.popBackStack() })
        }

        composable<MockonomyDestination.SetupGuide> {
            SetupGuideScreen(onBack = { navController.popBackStack() })
        }

        composable<MockonomyDestination.Settings> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onLicensesClick = { navController.navigate(MockonomyDestination.OpenSourceLicenses) },
            )
        }

        composable<MockonomyDestination.OpenSourceLicenses> {
            OpenSourceLicensesScreen(
                onBack = { navController.popBackStack() },
                onLicenseClick = { entry ->
                    navController.navigate(MockonomyDestination.LicenseText(entry.name, entry.assetPath))
                },
            )
        }

        composable<MockonomyDestination.LicenseText> { backStackEntry ->
            val destination = backStackEntry.toRoute<MockonomyDestination.LicenseText>()
            LicenseTextScreen(
                title = destination.title,
                assetPath = destination.assetPath,
                onBack = { navController.popBackStack() },
            )
        }

        composable<MockonomyDestination.Import> {
            ImportScreen(
                onBack = { navController.popBackStack() },
                onImported = { environmentId ->
                    // The import screen does not stay on the back stack: the back button
                    // must not take the user back to the preview they just confirmed.
                    navController.popBackStack()
                    navController.navigate(MockonomyDestination.EnvironmentDetail(environmentId))
                },
            )
        }

        composable<MockonomyDestination.EnvironmentDetail> { backStackEntry ->
            val destination = backStackEntry.toRoute<MockonomyDestination.EnvironmentDetail>()
            EnvironmentDetailScreen(
                environmentId = destination.environmentId,
                onBack = { navController.popBackStack() },
                onRouteClick = { routeId ->
                    navController.navigate(
                        MockonomyDestination.RouteEdit(destination.environmentId, routeId),
                    )
                },
                onLogClick = { logId ->
                    navController.navigate(
                        MockonomyDestination.LogDetail(destination.environmentId, logId),
                    )
                },
            )
        }

        composable<MockonomyDestination.LogDetail> {
            LogDetailScreen(
                onBack = { navController.popBackStack() },
                onCreateMock = { environmentId, routeId ->
                    navController.navigate(MockonomyDestination.RouteEdit(environmentId, routeId))
                },
            )
        }

        composable<MockonomyDestination.RouteEdit> { backStackEntry ->
            val destination = backStackEntry.toRoute<MockonomyDestination.RouteEdit>()
            RouteEditScreen(
                environmentId = destination.environmentId,
                routeId = destination.routeId,
                onBack = { navController.popBackStack() },
                onResponseClick = { responseId ->
                    navController.navigate(
                        MockonomyDestination.ResponseEdit(
                            environmentId = destination.environmentId,
                            routeId = destination.routeId,
                            responseId = responseId,
                        ),
                    )
                },
            )
        }

        composable<MockonomyDestination.ResponseEdit> { backStackEntry ->
            val destination = backStackEntry.toRoute<MockonomyDestination.ResponseEdit>()
            ResponseEditScreen(
                environmentId = destination.environmentId,
                routeId = destination.routeId,
                responseId = destination.responseId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
