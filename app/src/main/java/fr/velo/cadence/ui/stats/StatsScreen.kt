package fr.velo.cadence.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.velo.cadence.stats.PersonalRecords
import fr.velo.cadence.ui.components.BarChart
import fr.velo.cadence.ui.components.Format
import fr.velo.cadence.ui.components.MetricTile
import fr.velo.cadence.ui.components.SectionCard
import fr.velo.cadence.ui.theme.MetricSmall

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Statistiques") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SectionCard(title = "Depuis le début") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        MetricTile(
                            "Distance",
                            "%.0f".format(state.allTime.distanceKm),
                            "km",
                            modifier = Modifier.weight(1f),
                            valueStyle = MetricSmall,
                        )
                        MetricTile(
                            "Dénivelé",
                            "%.0f".format(state.allTime.ascentM),
                            "m",
                            modifier = Modifier.weight(1f),
                            valueStyle = MetricSmall,
                        )
                        MetricTile(
                            "Temps",
                            Format.duration(state.allTime.movingTimeMs),
                            modifier = Modifier.weight(1f),
                            valueStyle = MetricSmall,
                        )
                        MetricTile(
                            "Sorties",
                            state.allTime.rideCount.toString(),
                            modifier = Modifier.weight(1f),
                            valueStyle = MetricSmall,
                        )
                    }
                }
            }

            item {
                SectionCard(title = "Cette année") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        MetricTile(
                            "Distance",
                            "%.0f".format(state.year.distanceKm),
                            "km",
                            modifier = Modifier.weight(1f),
                            valueStyle = MetricSmall,
                        )
                        MetricTile(
                            "Dénivelé",
                            "%.0f".format(state.year.ascentM),
                            "m",
                            modifier = Modifier.weight(1f),
                            valueStyle = MetricSmall,
                        )
                        MetricTile(
                            "Vitesse moy.",
                            Format.speed(state.year.avgSpeedKmh),
                            "km/h",
                            modifier = Modifier.weight(1f),
                            valueStyle = MetricSmall,
                        )
                    }
                }
            }

            item {
                SectionCard(title = "Volume hebdomadaire (12 semaines)") {
                    BarChart(
                        values = state.weekly.map { it.label to it.distanceKm },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .padding(top = 12.dp),
                        valueLabel = { "%.0f km".format(it) },
                    )
                }
            }

            item {
                SectionCard(title = "Volume mensuel (12 mois)") {
                    BarChart(
                        values = state.monthly.map { it.label to it.distanceKm },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .padding(top = 12.dp),
                        barColor = MaterialTheme.colorScheme.secondary,
                        valueLabel = { "%.0f km".format(it) },
                    )
                }
            }

            if (state.records.isNotEmpty()) {
                item {
                    SectionCard(title = "Records personnels") {
                        Column(Modifier.padding(top = 10.dp)) {
                            state.records.forEach { record ->
                                val hours = record.durationMs / 3_600_000.0
                                val speed = if (hours > 0) {
                                    (record.distanceM / 1000.0) / hours
                                } else {
                                    0.0
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = PersonalRecords.label(record.distanceM),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = "${Format.timer(record.durationMs)}  " +
                                            "(${Format.speed(speed)} km/h)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                SectionCard(title = "Charge d'entraînement") {
                    Column(Modifier.padding(top = 10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            MetricTile(
                                "Forme (7 j)",
                                "%.0f".format(state.load.acute),
                                modifier = Modifier.weight(1f),
                                valueStyle = MetricSmall,
                            )
                            MetricTile(
                                "Fond (42 j)",
                                "%.0f".format(state.load.chronic),
                                modifier = Modifier.weight(1f),
                                valueStyle = MetricSmall,
                            )
                            MetricTile(
                                "Ratio",
                                "%.2f".format(state.load.ratio),
                                modifier = Modifier.weight(1f),
                                valueStyle = MetricSmall,
                            )
                        }
                        Text(
                            text = state.load.advice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            state.estimatedLevel?.let { level ->
                item {
                    SectionCard(title = "Niveau estimé") {
                        Text(
                            text = level.label,
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            text = "Estimé à partir de ta vitesse moyenne, de ton volume " +
                                "hebdomadaire, de ta plus longue sortie et du dénivelé avalé. " +
                                "C'est ce niveau qui calibre les parcours proposés.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            item {
                SectionCard(title = "Sorties marquantes") {
                    Column(Modifier.padding(top = 10.dp)) {
                        state.longestRide?.let {
                            Text(
                                text = "La plus longue : ${it.title} — " +
                                    "${Format.distanceKm(it.distanceM)} km",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        state.biggestClimb?.let {
                            Text(
                                text = "La plus montagneuse : ${it.title} — " +
                                    "+${it.ascentM.toInt()} m",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (state.longestRide == null) {
                            Text(
                                text = "Rien à afficher pour l'instant.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
