package fr.velo.cadence.routing

import fr.velo.cadence.model.Geo
import fr.velo.cadence.model.GeoPoint
import fr.velo.cadence.model.RiderProfile
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan
import kotlin.math.sqrt

/**
 * Estimation de la duree d'un parcours par un modele physique plutot que par
 * une vitesse moyenne forfaitaire.
 *
 * A chaque segment on resout l'equilibre des puissances :
 *
 *   P = v * ( Crr*m*g*cos(a) + m*g*sin(a) + 0.5*rho*CdA*v^2 ) / rendement
 *
 * ou `a` est la pente du segment. C'est ce qui permet de dire qu'un parcours
 * de 80 km avec 1 500 m de denivele coute beaucoup plus de temps qu'un 80 km
 * plat, et de le dire differemment selon le niveau du cycliste.
 */
object RideEstimator {

    private const val G = 9.81
    private const val AIR_DENSITY = 1.225
    private const val ROLLING_RESISTANCE = 0.005
    private const val DRIVETRAIN_EFFICIENCY = 0.97

    /** Duree estimee, en millisecondes. */
    fun estimateDuration(
        points: List<GeoPoint>,
        profile: RiderProfile,
        junctionCount: Int = 0,
    ): Long {
        if (points.size < 2) return 0L
        val mass = profile.totalMassKg
        val power = profile.enduranceWatts
        val cda = profile.level.cda
        val maxDescent = profile.level.maxDescentSpeedKmh / 3.6

        var seconds = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val d = Geo.distance(a, b)
            if (d < 0.5) continue
            val dEle = (b.ele ?: 0.0) - (a.ele ?: 0.0)
            val grade = (dEle / d).coerceIn(-0.25, 0.25)
            val v = solveSpeed(power, mass, cda, grade, maxDescent)
            seconds += d / v
        }
        // Chaque intersection ou changement de direction coute quelques
        // secondes de ralentissement, ce que le modele de puissance ignore.
        seconds += junctionCount * 4.0
        return (seconds * 1000).toLong()
    }

    /** Vitesse d'equilibre en m/s pour une pente donnee. */
    fun solveSpeed(
        powerWatts: Double,
        massKg: Double,
        cda: Double,
        grade: Double,
        maxDescentSpeedMps: Double,
    ): Double {
        val angle = atan(grade)
        val gravity = massKg * G * sin(angle)
        val rolling = ROLLING_RESISTANCE * massKg * G * cos(angle)
        val resistance = gravity + rolling

        if (resistance < 0) {
            // Descente : la gravite entraine, la vitesse est bornee par la
            // trainee puis par ce que le cycliste accepte de tenir.
            val terminal = sqrt(-resistance * 2.0 / (AIR_DENSITY * cda))
            val pedalled = bisect(powerWatts, cda, resistance)
            return minOf(maxOf(terminal, pedalled), maxDescentSpeedMps)
        }
        return bisect(powerWatts, cda, resistance).coerceIn(1.2, maxDescentSpeedMps)
    }

    /**
     * Resolution de P*rendement = v*R + 0.5*rho*CdA*v^3 par dichotomie.
     * La fonction est strictement croissante en v, la convergence est sure.
     */
    private fun bisect(powerWatts: Double, cda: Double, resistance: Double): Double {
        val target = powerWatts * DRIVETRAIN_EFFICIENCY
        var low = 0.1
        var high = 40.0
        repeat(40) {
            val mid = (low + high) / 2.0
            val needed = mid * resistance + 0.5 * AIR_DENSITY * cda * mid * mid * mid
            if (needed > target) high = mid else low = mid
        }
        return (low + high) / 2.0
    }


    /**
     * Puissance mecanique instantanee estimee a partir de la vitesse et de la
     * pente. Permet d'afficher une puissance et des calories credibles meme
     * sans capteur, en descente la valeur est ramenee a zero puisque le
     * cycliste ne pedale generalement pas.
     */
    fun estimatePower(
        speedMps: Double,
        grade: Double,
        massKg: Double,
        cda: Double,
    ): Int {
        if (speedMps <= 0.5) return 0
        val angle = atan(grade.coerceIn(-0.25, 0.25))
        val gravity = massKg * G * sin(angle)
        val rolling = ROLLING_RESISTANCE * massKg * G * cos(angle)
        val drag = 0.5 * AIR_DENSITY * cda * speedMps * speedMps
        val watts = speedMps * (gravity + rolling + drag) / DRIVETRAIN_EFFICIENCY
        return watts.coerceAtLeast(0.0).toInt()
    }

    /**
     * Puissance normalisee : moyenne glissante de 30 s elevee a la puissance 4
     * puis moyennee, comme le fait le modele de Coggan. Rend compte du cout
     * reel d'un effort irregulier.
     */
    fun normalizedPower(powerSamples: List<Int>, sampleIntervalS: Double = 1.0): Int {
        if (powerSamples.size < 30) return powerSamples.takeIf { it.isNotEmpty() }?.average()?.toInt() ?: 0
        val window = (30.0 / sampleIntervalS).toInt().coerceAtLeast(1)
        var sum = 0.0
        var count = 0
        var rolling = 0.0
        for (i in powerSamples.indices) {
            rolling += powerSamples[i].toDouble()
            if (i >= window) rolling -= powerSamples[i - window].toDouble()
            if (i >= window - 1) {
                val avg = rolling / window
                sum += avg * avg * avg * avg
                count++
            }
        }
        if (count == 0) return 0
        return Math.pow(sum / count, 0.25).toInt()
    }

    /** Vitesse moyenne estimee, en km/h. */
    fun estimateAverageSpeedKmh(
        points: List<GeoPoint>,
        profile: RiderProfile,
    ): Double {
        val ms = estimateDuration(points, profile)
        if (ms <= 0) return 0.0
        return (Geo.pathLength(points) / 1000.0) / (ms / 3_600_000.0)
    }

    /**
     * Depense energetique estimee, en kilocalories. Le rendement musculaire
     * d'un cycliste tourne autour de 24 %, d'ou le facteur applique au travail
     * mecanique.
     */
    fun estimateCalories(durationMs: Long, averagePowerWatts: Double): Int {
        val seconds = durationMs / 1000.0
        val mechanicalKj = averagePowerWatts * seconds / 1000.0
        return (mechanicalKj / 0.24 / 4.184).toInt()
    }
}
