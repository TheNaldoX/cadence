package fr.velo.cadence.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.velo.cadence.model.RiderLevel
import fr.velo.cadence.model.RoadStyle
import fr.velo.cadence.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onOpenSensors: () -> Unit,
    onOpenStats: () -> Unit,
    viewModel: ProfileViewModel = viewModel(factory = ProfileViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profile = state.profile
    var name by remember(profile.displayName) { mutableStateOf(profile.displayName) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard(title = "Cycliste") {
                Column(Modifier.padding(top = 12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Prénom") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { viewModel.setName(name) },
                        modifier = Modifier.padding(top = 8.dp),
                        enabled = name != profile.displayName,
                    ) { Text("Enregistrer") }
                }
            }
        }

        item {
            SectionCard(title = "Niveau") {
                Column(Modifier.padding(top = 12.dp)) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RiderLevel.entries.forEach { level ->
                            FilterChip(
                                selected = profile.level == level,
                                onClick = { viewModel.setLevel(level) },
                                label = { Text(level.label) },
                            )
                        }
                    }
                    Text(
                        text = "Environ ${profile.level.enduranceWattsPerKg} W/kg en endurance, " +
                            "${profile.level.typicalWeeklyKm} km par semaine, " +
                            "jusqu'à ${profile.level.maxAscentPerKm.toInt()} m de dénivelé par km.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    state.estimatedLevel?.let { estimated ->
                        if (estimated != profile.level) {
                            Text(
                                text = "D'après ton historique, tu roules plutôt au niveau " +
                                    "${estimated.label}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    ToggleRow(
                        label = "Ajuster automatiquement d'après mes sorties",
                        checked = profile.autoLevelFromHistory,
                        onCheckedChange = viewModel::setAutoLevel,
                    )
                }
            }
        }

        item {
            SectionCard(title = "Données physiques") {
                Column(Modifier.padding(top = 12.dp)) {
                    NumberField(
                        label = "Poids du cycliste (kg)",
                        value = profile.riderWeightKg.toString(),
                        onCommit = { it.toDoubleOrNull()?.let(viewModel::setRiderWeight) },
                    )
                    NumberField(
                        label = "Poids du vélo (kg)",
                        value = profile.bikeWeightKg.toString(),
                        onCommit = { it.toDoubleOrNull()?.let(viewModel::setBikeWeight) },
                    )
                    NumberField(
                        label = "FTP (W, 0 si inconnu)",
                        value = profile.ftpWatts.toString(),
                        onCommit = { it.toIntOrNull()?.let(viewModel::setFtp) },
                    )
                    NumberField(
                        label = "Fréquence cardiaque maximale",
                        value = profile.maxHeartRate.toString(),
                        onCommit = { it.toIntOrNull()?.let(viewModel::setMaxHeartRate) },
                    )
                    Text(
                        text = "Ces valeurs alimentent le modèle qui estime la durée d'un " +
                            "parcours et la puissance développée quand aucun capteur n'est " +
                            "connecté.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        item {
            SectionCard(title = "Type de routes préféré") {
                Row(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RoadStyle.entries.forEach { style ->
                        FilterChip(
                            selected = profile.preferredRoadStyle == style,
                            onClick = { viewModel.setRoadStyle(style) },
                            label = { Text(style.label) },
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = "Enregistrement") {
                Column(Modifier.padding(top = 8.dp)) {
                    ToggleRow(
                        label = "Pause automatique à l'arrêt",
                        checked = state.settings.autoPause,
                        onCheckedChange = viewModel::setAutoPause,
                    )
                    ToggleRow(
                        label = "Annonces vocales de navigation",
                        checked = state.settings.voiceGuidance,
                        onCheckedChange = viewModel::setVoiceGuidance,
                    )
                    ToggleRow(
                        label = "Garder l'écran allumé",
                        checked = state.settings.keepScreenOn,
                        onCheckedChange = viewModel::setKeepScreenOn,
                    )
                    ToggleRow(
                        label = "Utiliser le baromètre pour le dénivelé",
                        checked = state.settings.useBarometer,
                        onCheckedChange = viewModel::setUseBarometer,
                    )
                }
            }
        }

        item {
            ListItem(
                headlineContent = { Text("Capteurs Bluetooth") },
                supportingContent = {
                    Text("Cardio, puissance, cadence et vitesse")
                },
                leadingContent = { Icon(Icons.Outlined.Bluetooth, contentDescription = null) },
                trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenSensors),
            )
        }

        item {
            ListItem(
                headlineContent = { Text("Statistiques détaillées") },
                supportingContent = { Text("Volumes, records, charge d'entraînement") },
                leadingContent = { Icon(Icons.Outlined.Insights, contentDescription = null) },
                trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenStats),
            )
        }

        item {
            SectionCard(title = "À propos") {
                Text(
                    text = "Cadence fonctionne sans compte et sans serveur : tes sorties " +
                        "restent sur ton téléphone. Le calcul d'itinéraire utilise BRouter et " +
                        "les fonds de carte viennent d'OpenStreetMap via OpenFreeMap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NumberField(label: String, value: String, onCommit: (String) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = draft,
        onValueChange = {
            draft = it
            onCommit(it)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
