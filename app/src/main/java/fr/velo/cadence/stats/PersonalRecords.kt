package fr.velo.cadence.stats

import fr.velo.cadence.model.Geo
import fr.velo.cadence.model.RidePoint

/**
 * Recherche des meilleurs temps sur des distances de reference, par fenetre
 * glissante sur la trace. C'est l'equivalent des "records personnels" de
 * Strava, calcule localement.
 */
object PersonalRecords {

    val STANDARD_DISTANCES_M = listOf(
        1_000.0, 5_000.0, 10_000.0, 20_000.0, 40_000.0, 50_000.0, 100_000.0, 160_934.0,
    )

    fun label(distanceM: Double): String = when {
        distanceM >= 160_000 -> "100 miles"
        distanceM >= 1_000 -> "${(distanceM / 1000).toInt()} km"
        else -> "${distanceM.toInt()} m"
    }

    /**
     * Renvoie, pour chaque distance de reference couverte par la sortie, le
     * meilleur temps realise sur une portion continue de cette longueur.
     */
    fun bestEfforts(points: List<RidePoint>): Map<Double, Long> {
        if (points.size < 2) return emptyMap()

        val n = points.size
        val cumDist = DoubleArray(n)
        for (i in 1 until n) {
            cumDist[i] = cumDist[i - 1] + Geo.distance(
                points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon,
            )
        }
        val total = cumDist[n - 1]
        val result = LinkedHashMap<Double, Long>()

        for (target in STANDARD_DISTANCES_M) {
            if (total < target) continue
            var best = Long.MAX_VALUE
            var start = 0
            for (end in 1 until n) {
                // On avance le debut tant que la fenetre reste assez longue.
                while (start < end && cumDist[end] - cumDist[start + 1] >= target) start++
                if (cumDist[end] - cumDist[start] >= target) {
                    val dt = points[end].timestamp - points[start].timestamp
                    if (dt in 1 until best) best = dt
                }
            }
            if (best != Long.MAX_VALUE) result[target] = best
        }
        return result
    }
}
