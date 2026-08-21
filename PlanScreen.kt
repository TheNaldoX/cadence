package fr.velo.cadence.ui.plan

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.velo.cadence.model.BoundingBox
import fr.velo.cadence.model.PlannedRoute
import fr.velo.cadence.model.RoadStyle
import fr.velo.cadence.model.RouteCandidate
import fr.velo.cadence.model.TerrainPreference
import fr.velo.cadence.ui.components.Chip
import fr.velo.cadence.ui.components.EmptyState
import fr.velo.cadence.ui.components.Format
import fr.velo.cadence.ui.components.ScoreBar
import fr.velo.cadence.ui.map.CadenceMap
import fr.velo.cadence.ui.map.MapMarker
import fr.velo.cadence.ui.map.MapTrack
import fr.velo.cadence.ui.theme.TrackBlue
import fr.velo.cadence.ui.theme.TrackGreen
import fr.velo.cadence.ui.theme.TrackOrange

@Composable
fun PlanScreen(
    onOpenRoute: () -> Unit,
    viewModel: PlanViewModel = viewModel(factory = PlanViewModel.Factory),
) {
    var tab by remember { mutableIntStateOf(0) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saved by viewModel.savedRoutes.collectAsStateWithLifecycle()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.import(uri) { route -> if (route != null) onOpenRoute() }
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Proposer") })
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("Ma bibliothèque") },
            )
        }
        when (tab) {
            0 -> GeneratorTab(state, viewModel, onOpenRoute)
            else -> LibraryTab(
                routes = saved,
                onOpen = { viewModel.open(it); onOpenRoute() },
                onToggleFavorite = viewModel::toggleFavorite,
                onDelete = viewModel::delete,
                onImport = {
                    importLauncher.launch(
                        arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*"),
                    )
                },
            )
        }
    }
}

@Composable
private fun GeneratorTab(
    state: PlanUiState,
    viewModel: PlanViewModel,
    onOpenRoute: () -> Unit,
) {
    var pickOnMap by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableIntStateOf(0) }

    val candidateColors = listOf(TrackBlue, TrackGreen, TrackOrange, MaterialTheme.colorScheme.primary)
    val tracks = state.candidates.take(4).mapIndexed { index, candidate ->
        MapTrack(
            points = candidate.route.points,
            color = candidateColors[index % candidateColors.size],
            widthDp = if (index == selectedIndex) 7f else 3.5f,
        )
    }
    val bounds = state.candidates.getOrNull(selectedIndex)?.route?.bounds
        ?: state.start?.let { BoundingBox(it.lat - 0.05, it.lon - 0.07, it.lat + 0.05, it.lon + 0.07) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(20.dp)),
            ) {
                CadenceMap(
                    modifier = Modifier.fillMaxSize(),
                    tracks = tracks,
                    markers = listOfNotNull(
                        state.start?.let { MapMarker(it, MaterialTheme.colorScheme.primary, 9f) },
                    ),
                    fitBounds = bounds,
                    onLongPress = if (pickOnMap) {
                        { point -> viewModel.setStart(point); pickOnMap = false }
                    } else {
                        null
                    },
                )
                if (pickOnMap) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "Appui long sur la carte pour choisir le départ",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }

        item {
            Column {
                Text("Départ", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = state.startLabel.ifBlank { "Non défini" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { viewModel.locate() }) {
                        if (state.locating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Outlined.MyLocation, contentDescription = null)
                        }
                        Text("  Ma position")
                    }
                    OutlinedButton(onClick = { pickOnMap = !pickOnMap }) {
                        Icon(Icons.Outlined.TouchApp, contentDescription = null)
                        Text("  Sur la carte")
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; viewModel.search(it) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Chercher une ville, un lieu") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                )
                state.searchResults.forEach { place ->
                    Text(
                        text = place.label,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.setStart(place.point, place.label)
                                viewModel.clearSearch()
                                query = ""
                            }
                            .padding(vertical = 8.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        item {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Distance visée", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${state.targetDistanceKm.toInt()} km",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = state.targetDistanceKm,
                    onValueChange = viewModel::setDistance,
                    valueRange = 10f..250f,
                    steps = 47,
                )
            }
        }

        item {
            Column {
                Text("Relief", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TerrainPreference.entries.forEach { terrain ->
                        FilterChip(
                            selected = state.terrain == terrain,
                            onClick = { viewModel.setTerrain(terrain) },
                            label = { Text(terrain.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        }

        item {
            Column {
                Text("Type de routes", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RoadStyle.entries.forEach { style ->
                        FilterChip(
                            selected = state.roadStyle == style,
                            onClick = { viewModel.setRoadStyle(style) },
                            label = { Text(style.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Text(
                    text = state.roadStyle.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        item {
            if (state.loading) {
                Column {
                    LinearProgressIndicator(
                        progress = {
                            val progress = state.progress
                            if (progress == null || progress.total == 0) 0f
                            else progress.done.toFloat() / progress.total
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = state.progress?.message ?: "Calcul des boucles…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = viewModel::cancelGeneration) { Text("Annuler") }
                }
            } else {
                Button(
                    onClick = viewModel::generate,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = state.start != null,
                ) {
                    Icon(Icons.Outlined.Route, contentDescription = null)
                    Text("  Proposer des parcours", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        state.error?.let { message ->
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = viewModel::dismissError) { Text("Fermer") }
                    }
                }
            }
        }

        if (state.candidates.isNotEmpty()) {
            item {
                Text(
                    text = "${state.candidates.size} propositions, classées selon ton profil",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(state.candidates.size) { index ->
                val candidate = state.candidates[index]
                CandidateCard(
                    candidate = candidate,
                    color = candidateColors[index % candidateColors.size],
                    selected = index == selectedIndex,
                    onSelect = { selectedIndex = index },
                    onOpen = { viewModel.open(candidate.route); onOpenRoute() },
                )
            }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: RouteCandidate,
    color: androidx.compose.ui.graphics.Color,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    val route = candidate.route
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(color),
                )
                Text(
                    text = "${candidate.scorePercent} % de correspondance",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = route.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("${Format.distanceKm(route.distanceM)} km")
                Chip("+${Format.elevation(route.ascentM)}")
                Chip(Format.duration(route.estimatedDurationMs))
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("${(route.surface.pavedRatio * 100).toInt()} % revêtu")
                Chip("${route.ascentPerKm.toInt()} m/km")
                Chip(trafficLabel(route.surface.trafficIndex))
            }

            if (selected) {
                Column(Modifier.padding(top = 14.dp)) {
                    candidate.breakdown.asList().forEach { (label, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(0.32f),
                            )
                            ScoreBar(value, modifier = Modifier.weight(0.68f))
                        }
                    }
                    Button(
                        onClick = onOpen,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        Text("Voir le parcours en détail")
                    }
                }
            }
        }
    }
}

internal fun trafficLabel(index: Double): String = when {
    index < 0.15 -> "Très tranquille"
    index < 0.3 -> "Tranquille"
    index < 0.5 -> "Passage modéré"
    else -> "Routes passantes"
}

@Composable
private fun LibraryTab(
    routes: List<PlannedRoute>,
    onOpen: (PlannedRoute) -> Unit,
    onToggleFavorite: (PlannedRoute) -> Unit,
    onDelete: (PlannedRoute) -> Unit,
    onImport: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FileUpload, contentDescription = null)
                Text("  Importer un fichier GPX ou TCX")
            }
        }
        if (routes.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.Route,
                    title = "Aucun parcours enregistré",
                    description = "Génère des propositions dans l'onglet Proposer, " +
                        "ou importe un fichier reçu d'un ami ou d'un autre service.",
                )
            }
        } else {
            items(routes, key = { it.id }) { route ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(route) },
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = route.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                modifier = Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Chip("${Format.distanceKm(route.distanceM)} km")
                                Chip("+${Format.elevation(route.ascentM)}")
                            }
                        }
                        IconButton(onClick = { onToggleFavorite(route) }) {
                            Icon(
                                imageVector = if (route.isFavorite) Icons.Filled.Star
                                else Icons.Outlined.StarBorder,
                                contentDescription = "Favori",
                                tint = if (route.isFavorite) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onDelete(route) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Supprimer")
                        }
                    }
                }
            }
        }
    }
}
