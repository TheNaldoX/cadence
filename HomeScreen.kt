package fr.velo.cadence.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.velo.cadence.model.PlannedRoute
import fr.velo.cadence.model.Ride
import fr.velo.cadence.ui.components.Chip
import fr.velo.cadence.ui.components.Format
import fr.velo.cadence.ui.components.SectionCard
import fr.velo.cadence.ui.components.StatRow

@Composable
fun HomeScreen(
    onStartRide: () -> Unit,
    onPlanRoute: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenRide: (Long) -> Unit,
    onOpenRoute: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Text(
                    text = if (state.profile.displayName.isBlank()) "Bonjour"
                    else "Bonjour ${state.profile.displayName}",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = "Niveau ${state.profile.level.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.suggestedLevel?.let { suggested ->
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "Ton niveau a évolué",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "D'après tes dernières sorties, tu roules plutôt au niveau " +
                                "${suggested.label}. Mettre le profil à jour affinera les " +
                                "parcours proposés.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = { viewModel.acceptSuggestedLevel(suggested) }) {
                                Text("Passer en ${suggested.label}")
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onStartRide,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.DirectionsBike, contentDescription = null)
                    Text(
                        text = if (state.isRecording) "  Reprendre" else "  Rouler",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                OutlinedButton(
                    onClick = onPlanRoute,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Outlined.Route, contentDescription = null)
                    Text("  Parcours", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        item {
            SectionCard(
                title = "Cette semaine",
                trailing = {
                    TextButton(onClick = onOpenStats) {
                        Text("Statistiques")
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                    }
                },
            ) {
                Column(Modifier.padding(top = 12.dp)) {
                    StatRow(
                        items = listOf(
                            "Distance" to "${Format.distanceKm(state.week.distanceM)} km",
                            "Dénivelé" to Format.elevation(state.week.ascentM),
                            "Temps" to Format.duration(state.week.movingTimeMs),
                            "Sorties" to state.week.rideCount.toString(),
                        ),
                    )
                    if (state.load.chronic >= 1.0) {
                        Column(Modifier.padding(top = 16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Charge d'entraînement",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Text(
                                    text = "%.2f".format(state.load.ratio),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            LinearProgressIndicator(
                                progress = { (state.load.ratio / 2.0).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                                    .height(6.dp),
                            )
                            Text(
                                text = state.load.advice,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        if (state.favoriteRoutes.isNotEmpty()) {
            item {
                Text("Parcours favoris", style = MaterialTheme.typography.titleMedium)
            }
            items(state.favoriteRoutes, key = { "route-${it.id}" }) { route ->
                FavoriteRouteRow(route) {
                    viewModel.openRoute(route)
                    onOpenRoute()
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Dernières sorties", style = MaterialTheme.typography.titleMedium)
                Icon(Icons.Outlined.Insights, contentDescription = null)
            }
        }

        if (state.recentRides.isEmpty()) {
            item {
                Text(
                    text = "Aucune sortie enregistrée pour l'instant. Appuie sur Rouler " +
                        "pour lancer ton premier enregistrement.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.recentRides, key = { "ride-${it.id}" }) { ride ->
                RideRow(ride) { onOpenRide(ride.id) }
            }
        }
    }
}

@Composable
private fun FavoriteRouteRow(route: PlannedRoute, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(route.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("${Format.distanceKm(route.distanceM)} km")
                Chip("+${Format.elevation(route.ascentM)}")
                if (route.estimatedDurationMs > 0) {
                    Chip(Format.duration(route.estimatedDurationMs))
                }
            }
        }
    }
}

@Composable
internal fun RideRow(ride: Ride, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(ride.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    text = Format.relative(ride.startedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatRow(
                modifier = Modifier.padding(top = 10.dp),
                items = listOf(
                    "km" to Format.distanceKm(ride.distanceM),
                    "D+" to Format.elevation(ride.ascentM),
                    "temps" to Format.duration(ride.movingTimeMs),
                    "moy." to "${Format.speed(ride.avgSpeedKmh)} km/h",
                ),
            )
        }
    }
}
