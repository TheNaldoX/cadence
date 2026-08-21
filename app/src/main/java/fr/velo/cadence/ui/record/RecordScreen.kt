package fr.velo.cadence.ui.record

import android.Manifest
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import fr.velo.cadence.model.RecordingState
import fr.velo.cadence.ui.components.Chip
import fr.velo.cadence.ui.components.Format
import fr.velo.cadence.ui.components.MetricTile
import fr.velo.cadence.ui.map.CadenceMap
import fr.velo.cadence.ui.map.MapMarker
import fr.velo.cadence.ui.map.MapTrack
import fr.velo.cadence.ui.theme.MetricLarge
import fr.velo.cadence.ui.theme.MetricSmall
import fr.velo.cadence.ui.theme.TrackBlue
import fr.velo.cadence.ui.theme.TrackOrange

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun RecordScreen(
    onRideSaved: (Long) -> Unit,
    viewModel: RecordViewModel = viewModel(factory = RecordViewModel.Factory),
) {
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()
    val navigation by viewModel.navigation.collectAsStateWithLifecycle()
    val savedRideId by viewModel.savedRideId.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val sensors by viewModel.sensorsConnected.collectAsStateWithLifecycle()
    val plannedRoute by viewModel.plannedRoute.collectAsStateWithLifecycle()
    var confirmStop by remember { mutableStateOf(false) }

    val permissions = rememberMultiplePermissionsState(
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )

    // L'ecran reste allume pendant l'enregistrement : c'est ce qu'attend un
    // cycliste qui pose son telephone sur le guidon.
    val view = LocalView.current
    LaunchedEffect(keepScreenOn, metrics.state) {
        view.keepScreenOn = keepScreenOn && metrics.state != RecordingState.IDLE
    }

    LaunchedEffect(savedRideId) {
        savedRideId?.let {
            viewModel.consumeSavedRide()
            onRideSaved(it)
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Terminer la sortie ?") },
            text = { Text("La sortie sera enregistrée dans ton historique.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    viewModel.finish()
                }) { Text("Terminer et enregistrer") }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmStop = false
                    viewModel.discard()
                }) { Text("Abandonner") }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        if (!permissions.allPermissionsGranted) {
            PermissionCard(
                onGrant = { permissions.launchMultiplePermissionRequest() },
            )
        }

        if (navigation.active) {
            NavigationBanner(
                title = navigation.routeName,
                instruction = navigation.nextInstruction?.text ?: "Suivre le parcours",
                distanceToNext = navigation.distanceToNextM,
                remaining = navigation.distanceRemainingM,
                ascentRemaining = navigation.ascentRemainingM,
                offRoute = navigation.offRoute,
                progress = navigation.progress,
                onStop = viewModel::stopNavigation,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (metrics.state == RecordingState.IDLE) 260.dp else 190.dp)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(20.dp)),
        ) {
            CadenceMap(
                modifier = Modifier.fillMaxSize(),
                tracks = listOfNotNull(
                    // Le parcours a suivre en bleu, dessous ; la trace deja
                    // parcourue en orange, dessus.
                    plannedRoute?.takeIf { navigation.active && it.points.size > 1 }
                        ?.let { MapTrack(it.points, TrackBlue, 5f) },
                    metrics.trace.takeIf { it.size > 1 }?.let { MapTrack(it, TrackOrange, 6f) },
                ),
                markers = listOfNotNull(
                    metrics.lastPoint?.let { MapMarker(it, MaterialTheme.colorScheme.primary, 9f) },
                ),
                followPoint = metrics.lastPoint,
                interactive = metrics.state == RecordingState.IDLE,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                metrics.gpsAccuracyM?.let {
                    Chip("GPS ±${it.toInt()} m", icon = Icons.Outlined.GpsFixed)
                }
                sensors.forEach { label ->
                    Chip(label, icon = Icons.Outlined.Bluetooth)
                }
                if (metrics.state == RecordingState.AUTO_PAUSED) {
                    Chip(
                        text = "Pause automatique",
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                MetricTile(
                    label = "Vitesse",
                    value = Format.speed(metrics.speedKmh),
                    unit = "km/h",
                    valueStyle = MetricLarge,
                    accent = MaterialTheme.colorScheme.primary,
                )
                MetricTile(
                    label = "Temps",
                    value = Format.timer(metrics.movingTimeMs),
                    valueStyle = MetricSmall,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MetricTile(
                    "Distance",
                    Format.distanceKm(metrics.distanceM),
                    "km",
                    modifier = Modifier.weight(1f),
                    valueStyle = MetricSmall,
                )
                MetricTile(
                    "Moyenne",
                    Format.speed(metrics.avgSpeedKmh),
                    "km/h",
                    modifier = Modifier.weight(1f),
                    valueStyle = MetricSmall,
                )
                MetricTile(
                    "D+",
                    metrics.ascentM.toInt().toString(),
                    "m",
                    modifier = Modifier.weight(1f),
                    valueStyle = MetricSmall,
                )
                MetricTile(
                    "Pente",
                    Format.gradient(metrics.gradientPercent),
                    modifier = Modifier.weight(1f),
                    valueStyle = MetricSmall,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MetricTile(
                    label = "Cardio",
                    value = metrics.heartRate?.toString() ?: "--",
                    unit = "bpm",
                    modifier = Modifier.weight(1f),
                    valueStyle = MetricSmall,
                )
                MetricTile(
                    label = "Puissance",
                    value = metrics.power?.toString() ?: "--",
                    unit = "W",
                    modifier = Modifier.weight(1f),
                    valueStyle = MetricSmall,
                )
                MetricTile(
                    label = "Cadence",
                    value = metrics.cadence?.toString() ?: "--",
                    unit = "rpm",
                    modifier = Modifier.weight(1f),
                    valueStyle = MetricSmall,
                )
                MetricTile(
                    label = "Calories",
                    value = metrics.calories.toString(),
                    unit = "kcal",
                    modifier = Modifier.weight(1f),
                    valueStyle = MetricSmall,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (metrics.state) {
                RecordingState.IDLE -> Button(
                    onClick = {
                        if (permissions.allPermissionsGranted) viewModel.start()
                        else permissions.launchMultiplePermissionRequest()
                    },
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text("  Démarrer la sortie", style = MaterialTheme.typography.titleMedium)
                }

                RecordingState.RECORDING -> {
                    OutlinedButton(
                        onClick = viewModel::pause,
                        modifier = Modifier.weight(1f).height(60.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Icon(Icons.Outlined.Pause, contentDescription = null)
                        Text("  Pause")
                    }
                    Button(
                        onClick = { confirmStop = true },
                        modifier = Modifier.weight(1f).height(60.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Text("  Terminer")
                    }
                }

                RecordingState.PAUSED, RecordingState.AUTO_PAUSED -> {
                    Button(
                        onClick = viewModel::resume,
                        modifier = Modifier.weight(1f).height(60.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text("  Reprendre")
                    }
                    OutlinedButton(
                        onClick = { confirmStop = true },
                        modifier = Modifier.weight(1f).height(60.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Text("  Terminer")
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Autorisations nécessaires", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Cadence a besoin de la localisation précise pour enregistrer la trace, " +
                    "et de la notification pour continuer l'enregistrement écran éteint.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(onClick = onGrant, modifier = Modifier.padding(top = 10.dp)) {
                Text("Autoriser")
            }
        }
    }
}

@Composable
private fun NavigationBanner(
    title: String,
    instruction: String,
    distanceToNext: Double,
    remaining: Double,
    ascentRemaining: Double,
    offRoute: Boolean,
    progress: Float,
    onStop: () -> Unit,
) {
    val container = if (offRoute) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.primaryContainer

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(container)
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Navigation,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "  $title",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
            TextButton(onClick = onStop) { Text("Arrêter") }
        }
        Text(
            text = if (offRoute) "Hors parcours" else instruction,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = if (offRoute) {
                "Rejoins le tracé pour reprendre le guidage"
            } else {
                "dans ${Format.distance(distanceToNext)}"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(5.dp),
        )
        Text(
            text = "Reste ${Format.distance(remaining, true)} · " +
                "+${ascentRemaining.toInt()} m de dénivelé",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
