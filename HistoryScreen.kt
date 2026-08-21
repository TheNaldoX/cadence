package fr.velo.cadence.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsBike
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.velo.cadence.ui.components.EmptyState
import fr.velo.cadence.ui.components.Format
import fr.velo.cadence.ui.home.RideRow

@Composable
fun HistoryScreen(
    onOpenRide: (Long) -> Unit,
    viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()

    if (groups.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.DirectionsBike,
            title = "Pas encore de sortie",
            description = "Tes sorties enregistrées apparaîtront ici, groupées par mois, " +
                "avec leur distance, leur dénivelé et tes records.",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        groups.forEach { group ->
            item(key = "header-${group.label}") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(group.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "%.0f km · +%.0f m".format(group.distanceKm, group.ascentM),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(group.rides, key = { it.id }) { ride ->
                RideRow(ride) { onOpenRide(ride.id) }
            }
        }
        item {
            Text(
                text = "Total : ${Format.distanceKm(groups.sumOf { g -> g.rides.sumOf { it.distanceM } })} km",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
