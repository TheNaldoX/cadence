package fr.velo.cadence.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.graphics.vector.ImageVector

object Routes {
    const val HOME = "home"
    const val PLAN = "plan"
    const val ROUTE_DETAIL = "route_detail"
    const val RECORD = "record"
    const val HISTORY = "history"
    const val RIDE_DETAIL = "ride_detail"
    const val STATS = "stats"
    const val PROFILE = "profile"
    const val SENSORS = "sensors"

    fun rideDetail(id: Long) = "$RIDE_DETAIL/$id"
}

data class BottomDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

val bottomDestinations = listOf(
    BottomDestination(Routes.HOME, "Accueil", Icons.Outlined.Home),
    BottomDestination(Routes.PLAN, "Parcours", Icons.Outlined.Explore),
    BottomDestination(Routes.RECORD, "Rouler", Icons.Filled.DirectionsBike),
    BottomDestination(Routes.HISTORY, "Historique", Icons.Outlined.History),
    BottomDestination(Routes.PROFILE, "Profil", Icons.Outlined.Person),
)
