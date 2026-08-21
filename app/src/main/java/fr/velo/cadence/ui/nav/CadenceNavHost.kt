package fr.velo.cadence.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import fr.velo.cadence.ui.history.HistoryScreen
import fr.velo.cadence.ui.history.RideDetailScreen
import fr.velo.cadence.ui.home.HomeScreen
import fr.velo.cadence.ui.plan.PlanScreen
import fr.velo.cadence.ui.plan.RouteDetailScreen
import fr.velo.cadence.ui.profile.ProfileScreen
import fr.velo.cadence.ui.profile.SensorsScreen
import fr.velo.cadence.ui.record.RecordScreen
import fr.velo.cadence.ui.stats.StatsScreen

@Composable
fun CadenceNavHost(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomBar = currentRoute in bottomDestinations.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(destination.icon, contentDescription = destination.label)
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                enterTransition = { fadeIn(tween(180)) },
                exitTransition = { fadeOut(tween(180)) },
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        onStartRide = { navController.navigate(Routes.RECORD) },
                        onPlanRoute = { navController.navigate(Routes.PLAN) },
                        onOpenStats = { navController.navigate(Routes.STATS) },
                        onOpenRide = { navController.navigate(Routes.rideDetail(it)) },
                        onOpenRoute = { navController.navigate(Routes.ROUTE_DETAIL) },
                    )
                }

                composable(Routes.PLAN) {
                    PlanScreen(
                        onOpenRoute = { navController.navigate(Routes.ROUTE_DETAIL) },
                    )
                }

                composable(Routes.ROUTE_DETAIL) {
                    RouteDetailScreen(
                        onBack = { navController.popBackStack() },
                        onStartGuidedRide = {
                            navController.navigate(Routes.RECORD) {
                                popUpTo(Routes.HOME)
                            }
                        },
                    )
                }

                composable(Routes.RECORD) {
                    RecordScreen(
                        onRideSaved = { rideId ->
                            navController.navigate(Routes.rideDetail(rideId))
                        },
                    )
                }

                composable(Routes.HISTORY) {
                    HistoryScreen(
                        onOpenRide = { navController.navigate(Routes.rideDetail(it)) },
                    )
                }

                composable(
                    route = "${Routes.RIDE_DETAIL}/{rideId}",
                    arguments = listOf(navArgument("rideId") { type = NavType.LongType }),
                ) { entry ->
                    val rideId = entry.arguments?.getLong("rideId") ?: 0L
                    RideDetailScreen(
                        rideId = rideId,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.STATS) {
                    StatsScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.PROFILE) {
                    ProfileScreen(
                        onOpenSensors = { navController.navigate(Routes.SENSORS) },
                        onOpenStats = { navController.navigate(Routes.STATS) },
                    )
                }

                composable(Routes.SENSORS) {
                    SensorsScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
