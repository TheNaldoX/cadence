package fr.velo.cadence.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.velo.cadence.ui.components.ElevationChart
import fr.velo.cadence.ui.components.Format
import fr.velo.cadence.ui.components.MetricTile
import fr.velo.cadence.ui.components.SectionCard
import fr.velo.cadence.ui.map.CadenceMap
import fr.velo.cadence.ui.map.MapMarker
import fr.velo.cadence.ui.map.MapTrack
import fr.velo.cadence.ui.theme.MetricSmall
import fr.velo.cadence.ui.theme.TrackOrange
import fr.velo.cadence.model.BoundingBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideDetailScreen(
    rideId: Long,
    onBack: () -> Unit,
    viewModel: RideDetailViewModel = viewModel(
        key = "ride-$rideId",
        factory = RideDetailViewModel.factory(rideId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf(false) }
    var noteDraft by remember { mutableStateOf("") }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer cette sortie ?") },
            text = { Text("Cette action est définitive.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onBack)
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Annuler") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.ride?.title ?: "Sortie",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Supprimer")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val ride = state.ride
        if (state.loading || ride == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = Format.dayAndTime(ride.startedAt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.trace.size > 1) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(20.dp)),
                    ) {
                        CadenceMap(
                            modifier = Modifier.fillMaxSize(),
                            tracks = listOf(MapTrack(state.trace, TrackOrange, 6f)),
                            markers = listOfNotNull(
                                state.trace.firstOrNull()?.let {
                                    MapMarker(it, MaterialTheme.colorScheme.primary, 8f)
                                },
                            ),
                            fitBounds = BoundingBox.of(state.trace),
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MetricTile(
                        "Distance",
                        Format.distanceKm(ride.distanceM),
                        "km",
                        modifier = Modifier.weight(1f),
                        valueStyle = MetricSmall,
                    )
                    MetricTile(
                        "Temps",
                        Format.duration(ride.movingTimeMs),
                        modifier = Modifier.weight(1f),
                        valueStyle = MetricSmall,
                    )
                    MetricTile(
                        "D+",
                        ride.ascentM.toInt().toString(),
                        "m",
                        modifier = Modifier.weight(1f),
                        valueStyle = MetricSmall,
                    )
                    MetricTile(
                        "Moy.",
                        Format.speed(ride.avgSpeedKmh),
                        "km/h",
                        modifier = Modifier.weight(1f),
                        valueStyle = MetricSmall,
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MetricTile(
                        "Max",
                        Format.speed(ride.maxSpeedKmh),
                        "km/h",
                        modifier = Modifier.weight(1f),
                        valueStyle = MetricSmall,
                    )
                    MetricTile(
                        "Cardio moy.",
                        ride.avgHeartRate?.toString() ?: "--",
                        "bpm",
                        modifier = Modifier.weight(1f),
                        valueStyle = MetricSmall,
                    )
                    MetricTile(
                        "Puissance",
                        ride.avgPower?.toString() ?: "--",
                        "W",
                        modifier = Modifier.weight(1f),
                        valueStyle = MetricSmall,
                    )
                    MetricTile(
                        "Calories",
                        ride.calories?.toString() ?: "--",
                        "kcal",
                        modifier = Modifier.weight(1f),
                        valueStyle = MetricSmall,
                    )
                }
            }

            if (ride.normalizedPower != null) {
                item {
                    SectionCard(title = "Effort") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            MetricTile(
                                "Puiss. normalisée",
                                ride.normalizedPower.toString(),
                                "W",
                                modifier = Modifier.weight(1f),
                                valueStyle = MetricSmall,
                            )
                            MetricTile(
                                "Puiss. max",
                                ride.maxPower?.toString() ?: "--",
                                "W",
                                modifier = Modifier.weight(1f),
                                valueStyle = MetricSmall,
                            )
                            MetricTile(
                                "Cadence moy.",
                                ride.avgCadence?.toString() ?: "--",
                                "rpm",
                                modifier = Modifier.weight(1f),
                                valueStyle = MetricSmall,
                            )
                        }
                    }
                }
            }

            if (state.trace.any { it.ele != null }) {
                item {
                    SectionCard(title = "Profil altimétrique") {
                        ElevationChart(
                            points = state.trace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .padding(top = 12.dp),
                        )
                    }
                }
            }

            item {
                SectionCard(
                    title = "Notes",
                    trailing = {
                        TextButton(onClick = {
                            if (editingNote) {
                                viewModel.updateNote(noteDraft)
                                editingNote = false
                            } else {
                                noteDraft = ride.note
                                editingNote = true
                            }
                        }) { Text(if (editingNote) "Enregistrer" else "Modifier") }
                    },
                ) {
                    if (editingNote) {
                        OutlinedTextField(
                            value = noteDraft,
                            onValueChange = { noteDraft = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            placeholder = { Text("Sensations, météo, matériel…") },
                        )
                    } else {
                        Text(
                            text = ride.note.ifBlank { "Aucune note" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = viewModel::exportGpx,
                        modifier = Modifier.weight(1f),
                        enabled = !state.exporting,
                    ) {
                        if (state.exporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Outlined.Share, contentDescription = null)
                        }
                        Text("  Exporter en GPX")
                    }
                    if (state.lastExport != null) {
                        OutlinedButton(
                            onClick = {
                                viewModel.shareIntent()?.let { context.startActivity(it) }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Partager")
                        }
                    }
                }
            }
        }
    }
}
