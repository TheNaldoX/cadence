package fr.velo.cadence.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import fr.velo.cadence.model.Geo
import fr.velo.cadence.model.GeoPoint
import fr.velo.cadence.ui.theme.GradientEasy
import fr.velo.cadence.ui.theme.GradientHard
import fr.velo.cadence.ui.theme.GradientMedium
import fr.velo.cadence.ui.theme.GradientVeryHard
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Profil altimetrique colore par pente : c'est l'information la plus utile
 * avant de partir, bien plus qu'un simple chiffre de denivele total. Un
 * parcours a 800 m de D+ etale sur 100 km n'a rien a voir avec le meme
 * denivele concentre sur deux cols.
 */
@Composable
fun ElevationChart(
    points: List<GeoPoint>,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    showAxis: Boolean = true,
) {
    val elevations = points.mapNotNull { it.ele }
    if (points.size < 2 || elevations.size < 2) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "Profil altimétrique indisponible",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val minEle = elevations.min()
    val maxEle = elevations.max()
    val span = (maxEle - minEle).coerceAtLeast(20.0)
    val cumulative = Geo.cumulativeDistances(points)
    val total = cumulative.last().coerceAtLeast(1.0)
    val outline = MaterialTheme.colorScheme.outline
    val marker = MaterialTheme.colorScheme.primary

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val width = size.width
            val height = size.height

            fun x(index: Int) = (cumulative[index] / total * width).toFloat()
            fun y(elevation: Double) =
                (height - ((elevation - minEle) / span * height * 0.88 + height * 0.06)).toFloat()

            // Une barre verticale par tranche, coloree selon la pente locale.
            // Plus lisible qu'un degrade continu et beaucoup moins couteux a
            // dessiner qu'un chemin par point sur une trace de 20 000 points.
            val slices = 160
            for (slice in 0 until slices) {
                val startRatio = slice.toDouble() / slices
                val endRatio = (slice + 1.0) / slices
                val startIndex = indexAtRatio(cumulative, total, startRatio)
                val endIndex = indexAtRatio(cumulative, total, endRatio)
                if (endIndex <= startIndex) continue

                val startEle = points[startIndex].ele ?: continue
                val endEle = points[endIndex].ele ?: continue
                val run = cumulative[endIndex] - cumulative[startIndex]
                val grade = if (run > 1.0) (endEle - startEle) / run * 100.0 else 0.0

                val path = Path().apply {
                    moveTo(x(startIndex), height)
                    lineTo(x(startIndex), y(startEle))
                    lineTo(x(endIndex), y(endEle))
                    lineTo(x(endIndex), height)
                    close()
                }
                drawPath(path, color = gradientColor(grade).copy(alpha = 0.85f))
            }

            // Ligne de crete
            val ridge = Path()
            ridge.moveTo(x(0), y(points[0].ele ?: minEle))
            for (i in 1 until points.size) {
                val ele = points[i].ele ?: continue
                ridge.lineTo(x(i), y(ele))
            }
            drawPath(ridge, color = outline, style = Stroke(width = 1.5f))

            progress?.let {
                val position = width * it.coerceIn(0f, 1f)
                drawLine(
                    color = marker,
                    start = androidx.compose.ui.geometry.Offset(position, 0f),
                    end = androidx.compose.ui.geometry.Offset(position, height),
                    strokeWidth = 3f,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${(total / 1000).roundToInt()} km",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${maxEle.roundToInt()} m",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun indexAtRatio(cumulative: DoubleArray, total: Double, ratio: Double): Int {
    val target = total * ratio
    var low = 0
    var high = cumulative.lastIndex
    while (low < high) {
        val mid = (low + high) / 2
        if (cumulative[mid] < target) low = mid + 1 else high = mid
    }
    return low
}

/** Code couleur des pentes, proche des conventions des applications velo. */
fun gradientColor(percent: Double): Color = when {
    abs(percent) < 3 -> GradientEasy
    abs(percent) < 6 -> GradientMedium
    abs(percent) < 10 -> GradientHard
    else -> GradientVeryHard
}

/** Histogramme simple pour les volumes hebdomadaires ou mensuels. */
@Composable
fun BarChart(
    values: List<Pair<String, Double>>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    valueLabel: (Double) -> String = { "%.0f".format(it) },
) {
    if (values.isEmpty()) return
    val max = values.maxOf { it.second }.coerceAtLeast(1.0)
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            values.forEach { (_, value) ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val ratio = (value / max).toFloat().coerceIn(0f, 1f)
                        val barHeight = size.height * ratio
                        drawRoundedBar(
                            color = if (value <= 0.0) onSurfaceVariant.copy(alpha = 0.15f) else barColor,
                            height = barHeight,
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            values.forEach { (label, _) ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            }
        }
        values.maxByOrNull { it.second }?.let {
            Text(
                text = "Maximum : ${valueLabel(it.second)}",
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun DrawScope.drawRoundedBar(color: Color, height: Float) {
    if (height <= 0f) return
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(color, color.copy(alpha = 0.65f))),
        topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - height),
        size = androidx.compose.ui.geometry.Size(size.width, height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
    )
}

/** Barre de progression segmentee, utilisee pour les scores de parcours. */
@Composable
fun ScoreBar(
    value: Double,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = modifier.height(6.dp).fillMaxWidth()) {
        drawRoundRect(
            color = track,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
        )
        drawRoundRect(
            color = color,
            size = androidx.compose.ui.geometry.Size(
                size.width * value.toFloat().coerceIn(0f, 1f),
                size.height,
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
        )
    }
}
