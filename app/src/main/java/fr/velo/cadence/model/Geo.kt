package fr.velo.cadence.model

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Un point geographique. [ele] est l'altitude en metres, nulle si inconnue. */
data class GeoPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
)

/** Rectangle englobant, utilise pour cadrer la carte. */
data class BoundingBox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    val centerLat: Double get() = (south + north) / 2.0
    val centerLon: Double get() = (west + east) / 2.0

    companion object {
        fun of(points: List<GeoPoint>): BoundingBox? {
            if (points.isEmpty()) return null
            var s = points[0].lat
            var n = points[0].lat
            var w = points[0].lon
            var e = points[0].lon
            for (p in points) {
                if (p.lat < s) s = p.lat
                if (p.lat > n) n = p.lat
                if (p.lon < w) w = p.lon
                if (p.lon > e) e = p.lon
            }
            return BoundingBox(s, w, n, e)
        }
    }
}

object Geo {

    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Distance orthodromique en metres (formule de haversine). */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    fun distance(a: GeoPoint, b: GeoPoint): Double = distance(a.lat, a.lon, b.lat, b.lon)

    /** Longueur cumulee d'une polyligne, en metres. */
    fun pathLength(points: List<GeoPoint>): Double {
        var total = 0.0
        for (i in 1 until points.size) total += distance(points[i - 1], points[i])
        return total
    }

    /** Distances cumulees depuis le depart, en metres. Meme taille que [points]. */
    fun cumulativeDistances(points: List<GeoPoint>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            out[i] = out[i - 1] + distance(points[i - 1], points[i])
        }
        return out
    }

    /** Cap en degres (0 = nord, sens horaire). */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val phi1 = Math.toRadians(a.lat)
        val phi2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Ecart angulaire signe le plus court entre deux caps, dans [-180, 180]. */
    fun angleDelta(from: Double, to: Double): Double {
        var d = (to - from + 540.0) % 360.0 - 180.0
        if (d == -180.0) d = 180.0
        return d
    }

    /** Point situe a [distanceM] metres de [origin] dans la direction [bearingDeg]. */
    fun destination(origin: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint {
        val delta = distanceM / EARTH_RADIUS_M
        val theta = Math.toRadians(bearingDeg)
        val phi1 = Math.toRadians(origin.lat)
        val lambda1 = Math.toRadians(origin.lon)
        val phi2 = asin(sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta))
        val lambda2 = lambda1 + atan2(
            sin(theta) * sin(delta) * cos(phi1),
            cos(delta) - sin(phi1) * sin(phi2),
        )
        return GeoPoint(Math.toDegrees(phi2), (Math.toDegrees(lambda2) + 540.0) % 360.0 - 180.0)
    }

    /**
     * Distance d'un point a un segment, en metres, avec projection locale plane.
     * Suffisamment precis a l'echelle de quelques centaines de metres.
     */
    fun distanceToSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(p.lat))
        val px = (p.lon - a.lon) * mPerDegLon
        val py = (p.lat - a.lat) * mPerDegLat
        val bx = (b.lon - a.lon) * mPerDegLon
        val by = (b.lat - a.lat) * mPerDegLat
        val len2 = bx * bx + by * by
        if (len2 < 1e-9) return sqrt(px * px + py * py)
        var t = (px * bx + py * by) / len2
        t = t.coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Simplification Douglas-Peucker. [toleranceM] est la deviation maximale toleree.
     * Utilise avant l'export pour ne pas envoyer 30 000 points au compteur.
     */
    fun simplify(points: List<GeoPoint>, toleranceM: Double): List<GeoPoint> {
        if (points.size < 3 || toleranceM <= 0.0) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            if (end - start < 2) continue
            var maxDist = 0.0
            var index = -1
            for (i in start + 1 until end) {
                val d = distanceToSegment(points[i], points[start], points[end])
                if (d > maxDist) {
                    maxDist = d
                    index = i
                }
            }
            if (index >= 0 && maxDist > toleranceM) {
                keep[index] = true
                stack.addLast(start to index)
                stack.addLast(index to end)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * Reduit une trace a au plus [maxPoints] points en augmentant progressivement
     * la tolerance de simplification.
     */
    fun capPoints(points: List<GeoPoint>, maxPoints: Int): List<GeoPoint> {
        if (points.size <= maxPoints) return points
        var tolerance = 2.0
        var result = points
        var guard = 0
        while (result.size > maxPoints && guard < 20) {
            result = simplify(points, tolerance)
            tolerance *= 1.8
            guard++
        }
        if (result.size > maxPoints) {
            val step = result.size.toDouble() / maxPoints
            result = (0 until maxPoints).map { result[(it * step).toInt().coerceAtMost(result.size - 1)] }
        }
        return result
    }

    /**
     * Denivele positif et negatif filtres. Un seuil ([thresholdM]) elimine le bruit
     * du GPS et du modele numerique de terrain, comme le fait le "filtered ascend"
     * de BRouter ou le lissage de Strava.
     */
    fun elevationGainLoss(points: List<GeoPoint>, thresholdM: Double = 3.0): Pair<Double, Double> {
        val elevations = points.mapNotNull { it.ele }
        if (elevations.size < 2) return 0.0 to 0.0
        var gain = 0.0
        var loss = 0.0
        var reference = elevations[0]
        for (i in 1 until elevations.size) {
            val delta = elevations[i] - reference
            if (abs(delta) >= thresholdM) {
                if (delta > 0) gain += delta else loss -= delta
                reference = elevations[i]
            }
        }
        return gain to loss
    }
}
