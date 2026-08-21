package fr.velo.cadence.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DirectionsBike
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.velo.cadence.export.ExportFormat
import fr.velo.cadence.routing.Climb
import fr.velo.cadence.routing.ClimbDetector
import fr.velo.cadence.ui.components.Chip
import fr.velo.cadence.ui.components.ElevationChart
import fr.velo.cadence.ui.components.ElevationProfile
import fr.velo.cadence.ui.components.Format
import fr.velo.cadence.ui.components.MetricTile
import fr.velo.cadence.ui.components.ProfileReadout
import fr.velo.cadence.ui.components.SectionCard
import fr.velo.cadence.ui.components.gradientColor
import fr.velo.cadence.ui.map.CadenceMap
import fr.velo.cadence.ui.map.MapMarker
import fr.velo.cadence.ui.map.MapTrack
import fr.velo.cadence.ui.theme.MetricSmall
import fr.velo.cadence.ui.theme.TrackBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDetailScreen(
    onBack: () -> Unit,
    onStartGuidedRide: () -> Unit,
    viewModel: RouteDetailViewModel = viewModel(factory = RouteDetailViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Point designe par le doigt sur le profil : on le repercute sur la carte.
    var scrub by remember { mutableStateOf<ProfileReadout?>(null) }
    val routePoints = state.route?.points
    val climbs = remember(routePoints) {
        routePoints?.let { ClimbDetector.detect(it) } ?: emptyList()
    }
    val scrubPoint = remember(routePoints, scrub) {
        val points = routePoints
        val readout = scrub
        if (points == null || readout == null) null
        else points.getOrNull(ClimbDetector.pointIndexAt(points, readout.distanceM))
    }
    val snackbar = remember { SnackbarHostState() }
    var format by remember { mutableStateOf(ExportFormat.GPX) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val route = state.route
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = route?.name ?: "Parcours",
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
                    IconButton(onClick = viewModel::toggleFavorite) {
                        Icon(
                            imageVector = if (route?.isFavorite == true) Icons.Filled.Star
                            else Icons.Outlined.StarBorder,
                            contentDescription = "Favori",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (route == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Aucun parcours sélectionné")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(20.dp)),
                ) {
                    CadenceMap(
                        modifier = Modifier.fillMaxSize(),
                        tracks = listOf(MapTrack(route.points, TrackBlue, 6f)),
                        markers = listOfNotNull(
                            route.points.firstOrNull()?.let {
                                MapMarker(it, MaterialTheme.colorScheme.primary, 9f, "Départ")
                            },
                            scrubPoint?.let {
                                MapMarker(it, MaterialTheme.colorScheme.secondary, 8f)
                            },
                        ),
                        fitBounds = route.bounds,
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MetricTile(
                        "Distance",
                        Format.distanceKm(route.distanceM),
                        "km",
                        modifier = Modifier.weight(1f),
                        valueStyle = MetricSmall,
                    )
                    MetricTile(
                        "Dénivelé",
                        route.ascentM.toInt().toString(),
                        "m",
                        modifier = Modifier.weight(1f),
                        valueStyle = MetricSmall,
                    )
                    MetricTile(
                        "Durée estimée",
                        Format.duration(route.estimatedDurationMs),
                        modifier = Modifier.weight(1f),
                        valueStyle = MetricSmall,
                    )
                    MetricTile(
                        "Pente moy.",
                        "%.1f".format(route.ascentPerKm),
                        "m/km",
                        modifier = Modifier.weight(1f),
                        valueStyle = MetricSmall,
                    )
                }
            }

            item {
                SectionCard(title = "Profil altimétrique") {
                    ElevationProfile(
                        points = route.points,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .padding(top = 12.dp),
                        climbs = climbs,
                        interactive = true,
                        onScrub = { scrub = it },
                    )
                }
            }

            if (climbs.isNotEmpty()) {
                item {
                    SectionCard(
                        title = "Côtes (${climbs.size})",
                        trailing = {
                            Text(
                                text = "+${climbs.sumOf { it.gainM }.toInt()} m au total",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    ) {
                        Column(Modifier.padding(top = 10.dp)) {
                            climbs.forEachIndexed { index, climb ->
                                ClimbRow(climb)
                                if (index < climbs.lastIndex) {
                                    Spacer(Modifier.height(12.dp))
                                }
                            }
                        }
                    }
                }
            }

            item {
                SectionCard(title = "Routes empruntées") {
                    Column(Modifier.padding(top = 12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("${(route.surface.pavedRatio * 100).toInt()} % revêtu")
                            Chip(trafficLabel(route.surface.trafficIndex))
                        }
                        val top = route.surface.topSurfaces(4)
                        if (top.isNotEmpty()) {
                            Column(Modifier.padding(top = 10.dp)) {
                                top.forEach { (surface, meters) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = surfaceLabel(surface),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Text(
                                            text = Format.distance(meters),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (route.instructions.size > 2) {
                item {
                    SectionCard(title = "Feuille de route (${route.instructions.size} consignes)") {
                        Column(Modifier.padding(top = 10.dp)) {
                            route.instructions.take(12).forEach { instruction ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = instruction.type.label,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        text = Format.distance(instruction.distanceFromStartM, true),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (route.instructions.size > 12) {
                                Text(
                                    text = "… et ${route.instructions.size - 12} autres",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            viewModel.startGuidedRide()
                            onStartGuidedRide()
                        },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Outlined.DirectionsBike, contentDescription = null)
                        Text("  Suivre")
                    }
                    OutlinedButton(
                        onClick = viewModel::save,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        enabled = !state.saved,
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = null)
                        Text(if (state.saved) "  Enregistré" else "  Enregistrer")
                    }
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "Envoyer vers mon compteur",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Le BSC200S accepte le GPX et le TCX. Le TCX et le FIT " +
                                "transportent en plus les consignes de virage ; commence par " +
                                "le GPX, c'est le format le mieux supporté.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(
                            modifier = Modifier.padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ExportFormat.entries.forEach { candidate ->
                                // La carte d'export est deja en secondaryContainer :
                                // une puce selectionnee de la meme couleur y
                                // disparaitrait. On la passe donc en primary.
                                FilterChip(
                                    selected = format == candidate,
                                    onClick = { format = candidate },
                                    label = { Text(candidate.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                )
                            }
                        }
                        Button(
                            onClick = { viewModel.export(format) },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            enabled = !state.exporting,
                        ) {
                            if (state.exporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Outlined.Download, contentDescription = null)
                            }
                            Text("  Générer le fichier ${format.label}")
                        }

                        if (state.lastExport != null) {
                            Column(Modifier.padding(top = 12.dp)) {
                                Text(
                                    text = "Fichier : ${state.lastExport?.displayName}",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Text(
                                    text = "1. Ouvre l'app iGPSPORT Ride\n" +
                                        "2. Ma page → Mes parcours → +\n" +
                                        "3. Importer un parcours, choisis le fichier dans " +
                                        "Téléchargements\n" +
                                        "4. Ouvre le parcours puis « Envoyer vers l'appareil »",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                                Row(
                                    modifier = Modifier.padding(top = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (state.igpsportInstalled) {
                                        OutlinedButton(onClick = {
                                            viewModel.openIgpsportIntent()?.let { context.startActivity(it) }
                                        }) {
                                            Text("Ouvrir iGPSPORT")
                                        }
                                    }
                                    OutlinedButton(onClick = {
                                        viewModel.shareIntent(forceChooser = true)?.let {
                                            context.startActivity(it)
                                        }
                                    }) {
                                        Icon(Icons.Outlined.Share, contentDescription = null)
                                        Text("  Partager")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun surfaceLabel(surface: String): String = when (surface) {
    "asphalt" -> "Bitume"
    "paved" -> "Revêtu"
    "concrete", "concrete:plates" -> "Béton"
    "chipseal" -> "Enduit gravillonné"
    "gravel", "fine_gravel" -> "Gravier"
    "compacted" -> "Chemin compacté"
    "unpaved", "ground", "dirt", "earth" -> "Terre"
    "cobblestone", "sett" -> "Pavés"
    "paving_stones" -> "Dalles"
    "grass" -> "Herbe"
    "unknown" -> "Non renseigné"
    else -> surface.replaceFirstChar { it.uppercase() }
}

/**
 * Une ascension : sa categorie, ses chiffres, et le detail kilometre par
 * kilometre. C'est la lecture qu'on fait devant une pancarte de col, et celle
 * qui dit vraiment ou ca va faire mal.
 */
@Composable
private fun ClimbRow(climb: Climb) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(gradientColor(climb.avgGradient))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = climb.category.shortLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Black,
                    )
                }
                Text(
                    text = "  ${climb.summary}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                text = "+${climb.gainM.toInt()} m",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "du km %.1f au km %.1f · max %.1f %% · sommet à %d m".format(
                java.util.Locale.FRANCE,
                climb.startDistanceM / 1000.0,
                climb.endDistanceM / 1000.0,
                climb.maxGradient,
                climb.topElevationM.toInt(),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )

        if (climb.gradientPerKm.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                climb.gradientPerKm.forEach { gradient ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(22.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(gradientColor(gradient)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "%.0f".format(gradient),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Black,
                        )
                    }
                }
            }
        }
    }
}
