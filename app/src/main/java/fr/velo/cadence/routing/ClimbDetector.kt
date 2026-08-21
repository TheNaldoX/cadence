package fr.velo.cadence.routing

import fr.velo.cadence.model.Geo
import fr.velo.cadence.model.GeoPoint
import kotlin.math.roundToInt

/**
 * Categorie d'une ascension, selon l'usage cycliste : le produit de la
 * longueur par la pente moyenne. Une bosse de 2 km a 5 % et un col de 5 km a
 * 2 % demandent le meme travail, et se retrouvent donc au meme rang.
 */
enum class ClimbCategory(val label: String, val shortLabel: String) {
    NON_CLASSEE("Bosse", "—"),
    QUATRIEME("4ᵉ catégorie", "4"),
    TROISIEME("3ᵉ catégorie", "3"),
    DEUXIEME("2ᵉ catégorie", "2"),
    PREMIERE("1ʳᵉ catégorie", "1"),
    HORS_CATEGORIE("Hors catégorie", "HC");

    companion object {
        /** [score] vaut longueur en metres multipliee par la pente en pourcent. */
        fun fromScore(score: Double): ClimbCategory = when {
            score >= 80_000 -> HORS_CATEGORIE
            score >= 64_000 -> PREMIERE
            score >= 32_000 -> DEUXIEME
            score >= 16_000 -> TROISIEME
            score >= 8_000 -> QUATRIEME
            else -> NON_CLASSEE
        }
    }
}

/** Une ascension reperee sur le parcours. */
data class Climb(
    val startDistanceM: Double,
    val endDistanceM: Double,
    val lengthM: Double,
    val gainM: Double,
    val avgGradient: Double,
    val maxGradient: Double,
    val startElevationM: Double,
    val topElevationM: Double,
    val category: ClimbCategory,
    /** Pente moyenne de chaque kilometre de l'ascension, pour l'afficher en barres. */
    val gradientPerKm: List<Double>,
) {
    val score: Double get() = lengthM * avgGradient

    val summary: String
        get() = "%.1f km à %.1f %%".format(java.util.Locale.FRANCE, lengthM / 1000.0, avgGradient)
}

/**
 * Reperage des ascensions sur un profil altimetrique.
 *
 * Le releve brut est inexploitable tel quel : le modele numerique de terrain
 * et le GPS produisent un bruit de quelques metres qui, derive, invente des
 * pentes a 15 % sur du plat. Le profil est donc reechantillonne a pas fixe
 * puis lisse avant toute mesure de pente.
 */
object ClimbDetector {

    /** Pas de reechantillonnage, en metres. */
    private const val STEP_M = 25.0

    /** Fenetre de lissage, en nombre de pas (soit 125 m). */
    private const val SMOOTH_WINDOW = 5

    /** Une pente au-dela de ce seuil est consideree comme montante. */
    private const val CLIMB_THRESHOLD = 2.5

    /** Un replat plus court que cela ne coupe pas une ascension en deux. */
    private const val BRIDGE_MAX_LENGTH_M = 250.0

    /** ... a condition de ne pas y perdre plus que cela. */
    private const val BRIDGE_MAX_DROP_M = 15.0

    private const val MIN_LENGTH_M = 400.0
    private const val MIN_GAIN_M = 25.0

    /** Profil reechantillonne, expose pour l'affichage et le survol. */
    data class Sample(
        val distanceM: Double,
        val elevationM: Double,
        val gradientPercent: Double,
    )

    /**
     * Reechantillonne le profil a pas constant et lisse les altitudes.
     * Renvoie une liste vide si la trace ne porte pas d'altitude.
     */
    fun resample(points: List<GeoPoint>): List<Sample> {
        if (points.size < 2) return emptyList()
        val cumulative = Geo.cumulativeDistances(points)
        val total = cumulative.last()
        if (total < STEP_M * 2) return emptyList()

        val elevations = DoubleArray(points.size)
        var known = 0
        var last = Double.NaN
        for (i in points.indices) {
            val e = points[i].ele
            if (e != null) {
                elevations[i] = e
                last = e
                known++
            } else {
                elevations[i] = last
            }
        }
        if (known < 2) return emptyList()
        // Les altitudes manquantes en tete de trace prennent la premiere connue.
        val first = elevations.firstOrNull { !it.isNaN() } ?: return emptyList()
        for (i in points.indices) if (elevations[i].isNaN()) elevations[i] = first

        val count = (total / STEP_M).toInt() + 1
        val raw = DoubleArray(count)
        var cursor = 0
        for (s in 0 until count) {
            val d = s * STEP_M
            while (cursor < cumulative.size - 2 && cumulative[cursor + 1] < d) cursor++
            val d0 = cumulative[cursor]
            val d1 = cumulative[cursor + 1]
            val t = if (d1 - d0 > 1e-6) ((d - d0) / (d1 - d0)).coerceIn(0.0, 1.0) else 0.0
            raw[s] = elevations[cursor] + (elevations[cursor + 1] - elevations[cursor]) * t
        }

        // Moyenne glissante centree : elimine le bruit sans decaler le profil.
        val smooth = DoubleArray(count)
        val half = SMOOTH_WINDOW / 2
        for (s in 0 until count) {
            var sum = 0.0
            var n = 0
            for (k in (s - half)..(s + half)) {
                if (k in 0 until count) {
                    sum += raw[k]
                    n++
                }
            }
            smooth[s] = sum / n
        }

        return (0 until count).map { s ->
            val prev = smooth[(s - 1).coerceAtLeast(0)]
            val next = smooth[(s + 1).coerceAtMost(count - 1)]
            val run = STEP_M * ((s + 1).coerceAtMost(count - 1) - (s - 1).coerceAtLeast(0))
            val gradient = if (run > 0) (next - prev) / run * 100.0 else 0.0
            Sample(s * STEP_M, smooth[s], gradient.coerceIn(-30.0, 30.0))
        }
    }

    fun detect(points: List<GeoPoint>): List<Climb> = detectFrom(resample(points))

    fun detectFrom(samples: List<Sample>): List<Climb> {
        if (samples.size < 4) return emptyList()

        // 1. Les tronçons ou ca monte franchement.
        val runs = ArrayList<IntRange>()
        var start = -1
        for (i in samples.indices) {
            val climbing = samples[i].gradientPercent >= CLIMB_THRESHOLD
            if (climbing && start < 0) start = i
            if (!climbing && start >= 0) {
                runs.add(start until i)
                start = -1
            }
        }
        if (start >= 0) runs.add(start until samples.size)
        if (runs.isEmpty()) return emptyList()

        // 2. On recolle les tronçons separes par un simple replat : un col avec
        //    un faux plat au milieu reste un seul col.
        val merged = ArrayList<IntRange>()
        var current = runs.first()
        for (next in runs.drop(1)) {
            val gapLength = (next.first - current.last) * STEP_M
            val drop = samples[current.last].elevationM - samples[next.first].elevationM
            if (gapLength <= BRIDGE_MAX_LENGTH_M && drop <= BRIDGE_MAX_DROP_M) {
                current = current.first until (next.last + 1)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)

        // 3. On ne garde que ce qui merite d'etre annonce.
        return merged.mapNotNull { range ->
            val from = samples[range.first]
            val to = samples[range.last]
            val length = to.distanceM - from.distanceM
            val gain = to.elevationM - from.elevationM
            if (length < MIN_LENGTH_M || gain < MIN_GAIN_M) return@mapNotNull null

            val avg = gain / length * 100.0
            val max = (range.first..range.last)
                .map { samples[it].gradientPercent }
                .maxOrNull() ?: avg

            Climb(
                startDistanceM = from.distanceM,
                endDistanceM = to.distanceM,
                lengthM = length,
                gainM = gain,
                avgGradient = avg,
                maxGradient = max,
                startElevationM = from.elevationM,
                topElevationM = to.elevationM,
                category = ClimbCategory.fromScore(length * avg),
                gradientPerKm = gradientPerKilometre(samples, range),
            )
        }
    }

    /** Pente moyenne de chaque kilometre, comme sur les pancartes de col. */
    private fun gradientPerKilometre(samples: List<Sample>, range: IntRange): List<Double> {
        val perKm = ArrayList<Double>()
        val stepsPerKm = (1000.0 / STEP_M).toInt()
        var i = range.first
        while (i < range.last) {
            val j = (i + stepsPerKm).coerceAtMost(range.last)
            val run = samples[j].distanceM - samples[i].distanceM
            if (run < 100.0) break
            val rise = samples[j].elevationM - samples[i].elevationM
            perKm.add(rise / run * 100.0)
            i = j
        }
        return perKm
    }

    /** Echantillon le plus proche d'une distance donnee. */
    fun sampleAt(samples: List<Sample>, distanceM: Double): Sample? {
        if (samples.isEmpty()) return null
        val index = (distanceM / STEP_M).roundToInt().coerceIn(0, samples.size - 1)
        return samples[index]
    }

    /** Index du point de la trace correspondant a une distance parcourue. */
    fun pointIndexAt(points: List<GeoPoint>, distanceM: Double): Int {
        if (points.size < 2) return 0
        val cumulative = Geo.cumulativeDistances(points)
        var low = 0
        var high = cumulative.lastIndex
        while (low < high) {
            val mid = (low + high) / 2
            if (cumulative[mid] < distanceM) low = mid + 1 else high = mid
        }
        return low
    }
}
