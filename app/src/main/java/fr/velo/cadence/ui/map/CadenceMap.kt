package fr.velo.cadence.ui.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import fr.velo.cadence.model.BoundingBox
import fr.velo.cadence.model.Geo
import fr.velo.cadence.model.GeoPoint
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** Une trace a dessiner sur la carte. */
data class MapTrack(
    val points: List<GeoPoint>,
    val color: Color,
    val widthDp: Float = 5f,
)

/** Un point remarquable : depart, position actuelle, etape. */
data class MapMarker(
    val point: GeoPoint,
    val color: Color,
    val radiusDp: Float = 8f,
    val label: String? = null,
)

/**
 * Fonds de carte.
 *
 * CyclOSM par defaut : c'est un rendu concu pour le velo, qui fait ressortir
 * les voies cyclables, les revetements et la hierarchie des routes bien mieux
 * qu'un fond generaliste. OpenTopoMap en second, pour lire le relief avant une
 * sortie en montagne.
 */
object CadenceTiles {

    val CyclOsm: ITileSource = XYTileSource(
        "CyclOSM", 0, 18, 256, ".png",
        arrayOf(
            "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/",
            "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/",
            "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/",
        ),
        "© OpenStreetMap — rendu CyclOSM, hébergé par OpenStreetMap France",
    )

    val Topo: ITileSource = XYTileSource(
        "OpenTopoMap", 0, 17, 256, ".png",
        arrayOf(
            "https://a.tile.opentopomap.org/",
            "https://b.tile.opentopomap.org/",
            "https://c.tile.opentopomap.org/",
        ),
        "© OpenStreetMap, SRTM — rendu OpenTopoMap (CC-BY-SA)",
    )
}

private const val TRACK_OVERLAY_TAG = "cadence-track"
private const val MARKER_OVERLAY_TAG = "cadence-marker"

/**
 * Carte de l'application.
 *
 * osmdroid dessine ses tuiles sur le Canvas d'une vue Android ordinaire. Ce
 * detail est ce qui fait qu'elle fonctionne ici : un moteur a surface OpenGL
 * n'obtient jamais sa surface quand la vue est posee dans un conteneur Compose
 * decoupe aux coins arrondis, et reste indefiniment vide.
 */
@Composable
fun CadenceMap(
    modifier: Modifier = Modifier,
    tracks: List<MapTrack> = emptyList(),
    markers: List<MapMarker> = emptyList(),
    fitBounds: BoundingBox? = null,
    followPoint: GeoPoint? = null,
    followZoom: Double = 15.0,
    interactive: Boolean = true,
    tileSource: ITileSource = CadenceTiles.CyclOsm,
    onLongPress: ((GeoPoint) -> Unit)? = null,
    onTap: ((GeoPoint) -> Unit)? = null,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var lastFitted by remember { mutableStateOf<BoundingBox?>(null) }

    // Les rappels sont lus au moment du geste : on garde la version courante
    // sans avoir a recreer la vue quand ils changent.
    val longPress by rememberUpdatedState(onLongPress)
    val tap by rememberUpdatedState(onTap)

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                MapView(context).apply {
                    setTileSource(tileSource)
                    setMultiTouchControls(interactive)
                    setUseDataConnection(true)
                    isHorizontalMapRepetitionEnabled = false
                    isVerticalMapRepetitionEnabled = false
                    zoomController.setVisibility(
                        org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER,
                    )
                    controller.setZoom(5.0)
                    controller.setCenter(OsmGeoPoint(46.6, 2.4))

                    overlays.add(CopyrightOverlay(context))
                    overlays.add(
                        MapEventsOverlay(
                            object : MapEventsReceiver {
                                override fun singleTapConfirmedHelper(p: OsmGeoPoint?): Boolean {
                                    val handler = tap ?: return false
                                    p ?: return false
                                    handler(GeoPoint(p.latitude, p.longitude))
                                    return true
                                }

                                override fun longPressHelper(p: OsmGeoPoint?): Boolean {
                                    val handler = longPress ?: return false
                                    p ?: return false
                                    handler(GeoPoint(p.latitude, p.longitude))
                                    return true
                                }
                            },
                        ),
                    )
                    mapView = this
                }
            },
            update = { view ->
                view.setMultiTouchControls(interactive)
            },
        )

        if (mapView == null) {
            Text(
                text = "Chargement de la carte…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }

    // --- traces --------------------------------------------------------------
    LaunchedEffect(mapView, tracks) {
        val view = mapView ?: return@LaunchedEffect
        view.overlays.removeAll { (it as? Polyline)?.id == TRACK_OVERLAY_TAG }
        val density = view.resources.displayMetrics.density
        for (track in tracks) {
            if (track.points.size < 2) continue
            // Une trace de 20 000 points fait ramer le rendu : on la simplifie
            // pour l'affichage, la trace exacte reste celle en memoire.
            val simplified = Geo.capPoints(track.points, 2_000)
            val line = Polyline(view).apply {
                id = TRACK_OVERLAY_TAG
                setPoints(simplified.map { OsmGeoPoint(it.lat, it.lon) })
                outlinePaint.color = track.color.toArgb()
                outlinePaint.strokeWidth = track.widthDp * density
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.isAntiAlias = true
                isGeodesic = false
            }
            view.overlays.add(line)
        }
        view.invalidate()
    }

    // --- reperes -------------------------------------------------------------
    LaunchedEffect(mapView, markers) {
        val view = mapView ?: return@LaunchedEffect
        view.overlays.removeAll { (it as? Marker)?.id == MARKER_OVERLAY_TAG }
        for (marker in markers) {
            val item = Marker(view).apply {
                id = MARKER_OVERLAY_TAG
                position = OsmGeoPoint(marker.point.lat, marker.point.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = dotDrawable(view.context, marker.color.toArgb(), marker.radiusDp)
                title = marker.label
                setInfoWindow(null)
            }
            view.overlays.add(item)
        }
        view.invalidate()
    }

    // --- cadrage -------------------------------------------------------------
    LaunchedEffect(mapView, fitBounds) {
        val view = mapView ?: return@LaunchedEffect
        val bounds = fitBounds ?: return@LaunchedEffect
        if (bounds == lastFitted) return@LaunchedEffect
        lastFitted = bounds
        runCatching {
            view.zoomToBoundingBox(
                org.osmdroid.util.BoundingBox(
                    bounds.north, bounds.east, bounds.south, bounds.west,
                ),
                true,
                48,
            )
        }
    }

    // --- suivi de la position pendant la sortie -------------------------------
    LaunchedEffect(mapView, followPoint) {
        val view = mapView ?: return@LaunchedEffect
        val point = followPoint ?: return@LaunchedEffect
        view.controller.animateTo(OsmGeoPoint(point.lat, point.lon), followZoom, 600L)
    }

    // --- cycle de vie ---------------------------------------------------------
    DisposableEffect(lifecycleOwner, mapView) {
        val view = mapView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view?.onPause()
            view?.onDetach()
        }
    }
}

/**
 * Petit disque plein cercle blanc, dessine a la volee : cela evite d'embarquer
 * des images de marqueur pour chaque couleur et chaque densite d'ecran.
 */
private fun dotDrawable(context: Context, color: Int, radiusDp: Float): Drawable {
    val density = context.resources.displayMetrics.density
    val radius = radiusDp * density
    val ring = 2.5f * density
    val size = ((radius + ring) * 2).toInt().coerceAtLeast(4)
    val bitmap = android.graphics.Bitmap.createBitmap(
        size, size, android.graphics.Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(bitmap)
    val center = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(center, center, radius + ring, paint)
    paint.color = color
    canvas.drawCircle(center, center, radius, paint)
    return BitmapDrawable(context.resources, bitmap)
}
