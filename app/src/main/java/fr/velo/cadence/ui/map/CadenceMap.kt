package fr.velo.cadence.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.velo.cadence.BuildConfig
import fr.velo.cadence.model.BoundingBox
import fr.velo.cadence.model.Geo
import fr.velo.cadence.model.GeoPoint
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Une trace a dessiner sur la carte. */
data class MapTrack(
    val points: List<GeoPoint>,
    val color: Color,
    val widthDp: Float = 5f,
)

/** Un point remarquable : depart, position actuelle, arrivee. */
data class MapMarker(
    val point: GeoPoint,
    val color: Color,
    val radiusDp: Float = 8f,
)

/**
 * Carte vectorielle MapLibre.
 *
 * Le fond de carte vient d'OpenFreeMap : vectoriel, gratuit, sans cle ni
 * compte, contrairement a Mapbox ou Google Maps. Aucune donnee de position
 * n'est envoyee a un service tiers, la carte se contente de servir des
 * tuiles.
 *
 * Le nombre de couches est volontairement fixe (au plus quatre traces) : sur
 * Android, ajouter et retirer des couches a chaque recomposition provoque des
 * scintillements et des fuites de sources.
 */
private const val MAX_TRACKS = 4
private const val MAX_MARKERS = 4

/** Etat de la carte, affiche tant que le fond n'est pas rendu. */
private sealed interface MapState {
    data object Loading : MapState
    data object Ready : MapState
    data class Failed(val reason: String) : MapState
}

@Composable
fun CadenceMap(
    modifier: Modifier = Modifier,
    tracks: List<MapTrack> = emptyList(),
    markers: List<MapMarker> = emptyList(),
    fitBounds: BoundingBox? = null,
    followPoint: GeoPoint? = null,
    followZoom: Double = 15.0,
    interactive: Boolean = true,
    styleUrl: String = BuildConfig.MAP_STYLE_URL,
    onLongPress: ((GeoPoint) -> Unit)? = null,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var lastFitted by remember { mutableStateOf<BoundingBox?>(null) }
    var mapState by remember { mutableStateOf<MapState>(MapState.Loading) }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                // Rendu en TextureView et non en SurfaceView.
                //
                // Une SurfaceView est composee par le systeme dans une couche
                // separee : dans un arbre Compose, ou la carte est posee dans
                // un conteneur a coins arrondis et parfois dans une liste
                // defilante, sa surface n'est jamais creee. Or MapLibre
                // n'appelle getMapAsync qu'a la creation de cette surface :
                // sans elle, aucun style n'est charge et la vue reste sur sa
                // couleur d'attente, le fameux 0xFFF0E9E1. La TextureView est
                // une vue ordinaire, dessinee dans la hierarchie, qui se
                // decoupe et se compose normalement.
                val options = MapLibreMapOptions.createFromAttributes(context)
                    .textureMode(true)

                MapView(context, options).also { view ->
                    view.onCreate(null)
                    // Demarrage explicite : le renderer ne demarre qu'a
                    // onStart, et l'observateur de cycle de vie ci-dessous
                    // peut n'etre attache qu'apres la premiere composition.
                    view.onStart()
                    view.onResume()
                    mapView = view

                    view.addOnDidFailLoadingMapListener { message ->
                        mapState = MapState.Failed(message ?: "raison inconnue")
                    }

                    view.getMapAsync { libreMap ->
                        map = libreMap
                        libreMap.uiSettings.apply {
                            isRotateGesturesEnabled = interactive
                            isTiltGesturesEnabled = false
                            isScrollGesturesEnabled = interactive
                            isZoomGesturesEnabled = interactive
                            isAttributionEnabled = true
                            isLogoEnabled = false
                            isCompassEnabled = interactive
                        }
                        libreMap.setStyle(Style.Builder().fromUri(styleUrl)) { loaded ->
                            prepareLayers(loaded)
                            style = loaded
                            mapState = MapState.Ready
                        }
                        onLongPress?.let { callback ->
                            libreMap.addOnMapLongClickListener { latLng ->
                                callback(GeoPoint(latLng.latitude, latLng.longitude))
                                true
                            }
                        }
                    }
                }
            },
        )

        // Tant que le fond n'est pas la, on le dit. Un rectangle vide laisse
        // l'utilisateur devant un ecran muet sans savoir si ca charge ou si
        // c'est casse.
        if (mapState !is MapState.Ready) {
            val message = when (val current = mapState) {
                is MapState.Failed -> "Carte indisponible — ${current.reason}"
                else -> "Chargement de la carte…"
            }
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }

    // Traces
    LaunchedEffect(style, tracks) {
        val loaded = style ?: return@LaunchedEffect
        for (index in 0 until MAX_TRACKS) {
            val track = tracks.getOrNull(index)
            val source = loaded.getSourceAs<GeoJsonSource>(trackSourceId(index)) ?: continue
            val layer = loaded.getLayer(trackLayerId(index)) as? LineLayer
            if (track == null || track.points.size < 2) {
                source.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
                continue
            }
            // Une trace de 20 000 points fait ramer le rendu : on la simplifie
            // pour l'affichage, la trace exacte reste celle en memoire.
            val simplified = Geo.capPoints(track.points, 3_000)
            val line = LineString.fromLngLats(simplified.map { Point.fromLngLat(it.lon, it.lat) })
            source.setGeoJson(Feature.fromGeometry(line))
            layer?.setProperties(
                PropertyFactory.lineColor(track.color.toArgb()),
                PropertyFactory.lineWidth(track.widthDp),
            )
        }
    }

    // Reperes
    LaunchedEffect(style, markers) {
        val loaded = style ?: return@LaunchedEffect
        for (index in 0 until MAX_MARKERS) {
            val marker = markers.getOrNull(index)
            val source = loaded.getSourceAs<GeoJsonSource>(markerSourceId(index)) ?: continue
            val layer = loaded.getLayer(markerLayerId(index)) as? CircleLayer
            if (marker == null) {
                source.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
                continue
            }
            source.setGeoJson(
                Feature.fromGeometry(Point.fromLngLat(marker.point.lon, marker.point.lat)),
            )
            layer?.setProperties(
                PropertyFactory.circleColor(marker.color.toArgb()),
                PropertyFactory.circleRadius(marker.radiusDp),
            )
        }
    }

    // Cadrage
    LaunchedEffect(map, fitBounds) {
        val libreMap = map ?: return@LaunchedEffect
        val bounds = fitBounds ?: return@LaunchedEffect
        if (bounds == lastFitted) return@LaunchedEffect
        lastFitted = bounds
        runCatching {
            val latLngBounds = LatLngBounds.Builder()
                .include(LatLng(bounds.north, bounds.east))
                .include(LatLng(bounds.south, bounds.west))
                .build()
            libreMap.animateCamera(CameraUpdateFactory.newLatLngBounds(latLngBounds, 80), 600)
        }
    }

    // Suivi de la position pendant la sortie
    LaunchedEffect(map, followPoint) {
        val libreMap = map ?: return@LaunchedEffect
        val point = followPoint ?: return@LaunchedEffect
        runCatching {
            libreMap.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(point.lat, point.lon))
                        .zoom(followZoom)
                        .build(),
                ),
                800,
            )
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val view = mapView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view?.onStart()
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                Lifecycle.Event.ON_STOP -> view?.onStop()
                Lifecycle.Event.ON_DESTROY -> view?.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view?.onStop()
            view?.onDestroy()
        }
    }
}

private fun trackSourceId(index: Int) = "cadence-track-source-$index"
private fun trackLayerId(index: Int) = "cadence-track-layer-$index"
private fun markerSourceId(index: Int) = "cadence-marker-source-$index"
private fun markerLayerId(index: Int) = "cadence-marker-layer-$index"

/**
 * Cree une fois pour toutes les sources et couches ; leur contenu sera
 * remplace au fil des recompositions.
 */
private fun prepareLayers(style: Style) {
    for (index in 0 until MAX_TRACKS) {
        if (style.getSource(trackSourceId(index)) != null) continue
        style.addSource(GeoJsonSource(trackSourceId(index)))
        style.addLayer(
            LineLayer(trackLayerId(index), trackSourceId(index)).withProperties(
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineOpacity(0.9f),
            ),
        )
    }
    for (index in 0 until MAX_MARKERS) {
        if (style.getSource(markerSourceId(index)) != null) continue
        style.addSource(GeoJsonSource(markerSourceId(index)))
        style.addLayer(
            CircleLayer(markerLayerId(index), markerSourceId(index)).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleStrokeWidth(2.5f),
                PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            ),
        )
    }
}
