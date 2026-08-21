package fr.velo.cadence.ui.profile

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import fr.velo.cadence.sensors.SensorType
import fr.velo.cadence.ui.components.EmptyState
import fr.velo.cadence.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun SensorsScreen(
    onBack: () -> Unit,
    viewModel: SensorsViewModel = viewModel(factory = SensorsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val permissions = rememberMultiplePermissionsState(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Capteurs") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SectionCard(title = "Capteurs appairés") {
                    Column(Modifier.padding(top = 8.dp)) {
                        PairedRow(
                            "Cardiofréquencemètre",
                            state.pairedHeartRate,
                            state.readings.heartRate?.let { "$it bpm" },
                        ) { viewModel.unpair(SensorType.HEART_RATE, state.pairedHeartRate) }
                        PairedRow(
                            "Capteur de puissance",
                            state.pairedPower,
                            state.readings.power?.let { "$it W" },
                        ) { viewModel.unpair(SensorType.POWER, state.pairedPower) }
                        PairedRow(
                            "Vitesse / cadence",
                            state.pairedCsc,
                            state.readings.cadence?.let { "$it rpm" },
                        ) { viewModel.unpair(SensorType.SPEED_CADENCE, state.pairedCsc) }
                    }
                }
            }

            item {
                if (!permissions.allPermissionsGranted) {
                    Button(
                        onClick = { permissions.launchMultiplePermissionRequest() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Autoriser le Bluetooth")
                    }
                } else if (state.scanning) {
                    OutlinedButton(
                        onClick = viewModel::stopScan,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text("  Recherche en cours… (arrêter)")
                    }
                } else {
                    Button(onClick = viewModel::startScan, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Bluetooth, contentDescription = null)
                        Text("  Rechercher des capteurs")
                    }
                }
            }

            if (state.discovered.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Bluetooth,
                        title = "Aucun capteur détecté",
                        description = "Réveille tes capteurs (fais tourner la roue, mets la " +
                            "ceinture cardio) puis relance la recherche. Cadence reconnaît " +
                            "les profils Bluetooth standard : cardio, puissance, vitesse et " +
                            "cadence.",
                    )
                }
            } else {
                items(state.discovered, key = { it.address }) { sensor ->
                    ListItem(
                        headlineContent = { Text(sensor.name) },
                        supportingContent = {
                            Text("${sensor.kind.label} · ${sensor.rssi} dBm")
                        },
                        trailingContent = {
                            TextButton(onClick = { viewModel.pair(sensor) }) { Text("Appairer") }
                        },
                    )
                }
            }

            item {
                Text(
                    text = "Les capteurs restent connectés pendant toute la sortie et se " +
                        "reconnectent seuls s'ils se mettent en veille.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PairedRow(
    label: String,
    address: String?,
    liveValue: String?,
    onUnpair: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = when {
                    address == null -> "Aucun"
                    liveValue != null -> "$address · $liveValue"
                    else -> address
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (address != null) {
            TextButton(onClick = onUnpair) { Text("Oublier") }
        }
    }
}
