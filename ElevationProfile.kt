package fr.velo.cadence.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import fr.velo.cadence.model.GeoPoint
import fr.velo.cadence.routing.Climb
import fr.velo.cadence.routing.ClimbDetector
import kotlin.math.roundToInt

/** Ce que le doigt designe sur le profil. */
data class ProfileReadout(
    val distanceM: Double,
    val elevationM: Double,
    val gradientPercent: Double,
)

/**
 * Profil altimetrique interactif.
 *
 * Le profil est reechantillonne a pas fixe puis lisse avant d'etre trace : sur
 * une trace brute, le bruit du GPS suffit a peindre des pentes a 15 % sur du
 * plat. Chaque tranche verticale est coloree selon sa pente, avec le meme
 * bareme que les panneaux de col.
 *
 * Un appui ou un glissement horizontal donne la distance, l'altitude et la
 * pente sous le doigt, et remonte le point correspondant a l'appelant pour
 * qu'il le situe sur la carte.
 */
@Composable
fun ElevationProfile(
    points: List<GeoPoint>,
    modifier: Modifier = Modifier,
    climbs: List<Climb> = emptyList(),
    interactive: Boolean = true,
    showAxis: Boolean = true,
    showReadout: Boolean = true,
    progress: Float? = null,
    onScrub: ((ProfileReadout?) -> Unit)? = null,
) {
    val samples = remember(points) { ClimbDetector.resample(points) }

    if (samples.size < 3) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "Profil altimétrique indisponible",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val minEle = samples.minOf { it.elevationM }
    val maxEle = samples.maxOf { it.elevationM }
    val span = (maxEle - minEle).coerceAtLeast(20.0)
    val total = samples.last().distanceM.coerceAtLeast(1.0)

    var scrubRatio by remember(points) { mutableStateOf<Float?>(null) }
    val readout = scrubRatio?.let { ratio ->
        ClimbDetector.sampleAt(samples, total * ratio)?.let {
            ProfileReadout(it.distanceM, it.elevationM, it.gradientPercent)
        }
    }

    val outline = MaterialTheme.colorScheme.outline
    val marker = MaterialTheme.colorScheme.primary
    val climbTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    fun report(x: Float, width: Float) {
        val ratio = (x / width).coerceIn(0f, 1f)
        scrubRatio = ratio
        onScrub?.invoke(
            ClimbDetector.sampleAt(samples, total * ratio)?.let {
                ProfileReadout(it.distanceM, it.elevationM, it.gradientPercent)
            },
        )
    }

    Column(modifier = modifier) {
        if (showReadout) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (readout != null) {
                    Text(
                        text = "%.1f km".format(java.util.Locale.FRANCE, readout.distanceM / 1000.0),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = "${readout.elevationM.roundToInt()} m",
                        style = MaterialTheme.typography.labelLarge,
                        color = muted,
                    )
                    Text(
                        text = "%+.1f %%".format(java.util.Locale.FRANCE, readout.gradientPercent),
                        style = MaterialTheme.typography.labelLarge,
                        color = gradientColor(readout.gradientPercent),
                    )
                } else {
                    Text(
                        text = if (interactive) {
                            "Touche le profil pour lire la pente"
                        } else {
                            "%.0f km · %+.0f m".format(
                                java.util.Locale.FRANCE, total / 1000.0, maxEle - minEle,
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                }
            }
        }

        var chartModifier = Modifier.fillMaxWidth().weight(1f)
        if (interactive) {
            chartModifier = chartModifier
                .pointerInput(samples) {
                    detectTapGestures { offset -> report(offset.x, size.width.toFloat()) }
                }
                .pointerInput(samples) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> report(offset.x, size.width.toFloat()) },
                        onHorizontalDrag = { change, _ ->
                            report(change.position.x, size.width.toFloat())
                        },
                    )
                }
        }

        Canvas(modifier = chartModifier) {
            val width = size.width
            val height = size.height

            fun x(distance: Double) = (distance / total * width).toFloat()
            fun y(elevation: Double) =
                (height - ((elevation - minEle) / span * height * 0.86 + height * 0.07)).toFloat()

            // Bandeau discret sous chaque ascension reperee.
            for (climb in climbs) {
                val left = x(climb.startDistanceM)
                val right = x(climb.endDistanceM)
                drawRect(
                    color = climbTint,
                    topLeft = Offset(left, 0f),
                    size = androidx.compose.ui.geometry.Size(
                        (right - left).coerceAtLeast(1f), height,
                    ),
                )
            }

            // Une tranche par echantillon, coloree selon sa pente.
            for (i in 0 until samples.size - 1) {
                val a = samples[i]
                val b = samples[i + 1]
                val path = Path().apply {
                    moveTo(x(a.distanceM), height)
                    lineTo(x(a.distanceM), y(a.elevationM))
                    lineTo(x(b.distanceM), y(b.elevationM))
                    lineTo(x(b.distanceM), height)
                    close()
                }
                drawPath(path, color = gradientColor(a.gradientPercent).copy(alpha = 0.85f))
            }

            // Ligne de crete.
            val ridge = Path()
            ridge.moveTo(x(samples[0].distanceM), y(samples[0].elevationM))
            for (i in 1 until samples.size) {
                ridge.lineTo(x(samples[i].distanceM), y(samples[i].elevationM))
            }
            drawPath(ridge, color = outline, style = Stroke(width = 1.5f))

            progress?.let {
                val position = width * it.coerceIn(0f, 1f)
                drawLine(
                    color = marker.copy(alpha = 0.5f),
                    start = Offset(position, 0f),
                    end = Offset(position, height),
                    strokeWidth = 3f,
                )
            }

            readout?.let {
                val position = x(it.distanceM)
                drawLine(
                    color = marker,
                    start = Offset(position, 0f),
                    end = Offset(position, height),
                    strokeWidth = 3f,
                )
                drawCircle(color = marker, radius = 7f, center = Offset(position, y(it.elevationM)))
                drawCircle(
                    color = Color.White,
                    radius = 3f,
                    center = Offset(position, y(it.elevationM)),
                )
            }
        }

        if (showAxis) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${minEle.roundToInt()} m",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
                Text(
                    text = "${(total / 1000).roundToInt()} km",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
                Text(
                    text = "${maxEle.roundToInt()} m",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
            }
        }
    }
}
