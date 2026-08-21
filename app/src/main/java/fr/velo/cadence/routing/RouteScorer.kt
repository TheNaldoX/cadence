package fr.velo.cadence.routing

import fr.velo.cadence.model.PlannedRoute
import fr.velo.cadence.model.RoadStyle
import fr.velo.cadence.model.RouteRequest
import fr.velo.cadence.model.ScoreBreakdown
import fr.velo.cadence.model.TerrainPreference
import kotlin.math.abs
import kotlin.math.exp

/**
 * Notation d'un parcours candidat au regard de ce que l'utilisateur a demande
 * et de son niveau. Chaque critere est ramene entre 0 et 1 avant ponderation,
 * ce qui permet d'expliquer le classement dans l'interface.
 */
object RouteScorer {

    private const val W_DISTANCE = 0.30
    private const val W_TERRAIN = 0.22
    private const val W_SURFACE = 0.18
    private const val W_TRAFFIC = 0.18
    private const val W_EFFORT = 0.12

    fun score(route: PlannedRoute, request: RouteRequest): Pair<Double, ScoreBreakdown> {
        val breakdown = ScoreBreakdown(
            distanceMatch = distanceMatch(route.distanceKm, request.targetDistanceKm),
            terrainMatch = terrainMatch(route.ascentPerKm, request.terrain),
            surfaceQuality = surfaceQuality(route, request.roadStyle),
            trafficCalm = 1.0 - route.surface.trafficIndex,
            effortFit = effortFit(route, request),
        )
        val total = breakdown.distanceMatch * W_DISTANCE +
            breakdown.terrainMatch * W_TERRAIN +
            breakdown.surfaceQuality * W_SURFACE +
            breakdown.trafficCalm * W_TRAFFIC +
            breakdown.effortFit * W_EFFORT
        return total.coerceIn(0.0, 1.0) to breakdown
    }

    /**
     * Ecart relatif a la distance demandee, avec une courbe en cloche : 5 %
     * d'ecart reste tres bon, 25 % devient penalisant.
     */
    private fun distanceMatch(actualKm: Double, targetKm: Double): Double {
        if (targetKm <= 0) return 0.0
        val relative = abs(actualKm - targetKm) / targetKm
        return exp(-(relative / 0.15) * (relative / 0.15))
    }

    private fun terrainMatch(ascentPerKm: Double, terrain: TerrainPreference): Double {
        if (terrain == TerrainPreference.INDIFFERENT) return 0.85
        if (ascentPerKm in terrain.minAscentPerKm..terrain.maxAscentPerKm) return 1.0
        val distance = if (ascentPerKm < terrain.minAscentPerKm) {
            terrain.minAscentPerKm - ascentPerKm
        } else {
            ascentPerKm - terrain.maxAscentPerKm
        }
        // Au-dela de 8 m/km hors de la fourchette, le parcours ne correspond plus.
        return (1.0 - distance / 8.0).coerceIn(0.0, 1.0)
    }

    /**
     * En velo de route, le bitume est ce qui compte. En gravel la logique
     * s'inverse partiellement : une portion non revetue devient un atout.
     */
    private fun surfaceQuality(route: PlannedRoute, style: RoadStyle): Double {
        val paved = route.surface.pavedRatio
        return when (style) {
            RoadStyle.GRAVEL -> {
                val unpaved = 1.0 - paved
                // Optimum autour de 40 % de chemins.
                1.0 - abs(unpaved - 0.4) / 0.6
            }
            RoadStyle.POLYVALENT -> (paved * 0.7 + 0.3).coerceIn(0.0, 1.0)
            else -> {
                // Sous 80 % de bitume un parcours de route devient desagreable.
                if (paved >= 0.95) 1.0 else (paved - 0.55) / 0.40
            }
        }.coerceIn(0.0, 1.0)
    }

    /**
     * Adequation a la forme du cycliste : au-dela de son denivele maximal
     * confortable par kilometre, la sortie devient une epreuve plutot qu'un
     * entrainement.
     */
    private fun effortFit(route: PlannedRoute, request: RouteRequest): Double {
        val level = request.level
        val ascentPerKm = route.ascentPerKm

        val climbFit = when {
            ascentPerKm <= level.comfortableAscentPerKm -> 1.0
            ascentPerKm >= level.maxAscentPerKm -> 0.15
            else -> {
                val span = level.maxAscentPerKm - level.comfortableAscentPerKm
                1.0 - 0.85 * (ascentPerKm - level.comfortableAscentPerKm) / span
            }
        }

        // Une sortie tres longue par rapport au volume hebdomadaire habituel
        // du cycliste est aussi un facteur de difficulte.
        val volumeRatio = route.distanceKm / (level.typicalWeeklyKm / 3.0)
        val volumeFit = when {
            volumeRatio <= 1.0 -> 1.0
            volumeRatio >= 2.2 -> 0.25
            else -> 1.0 - 0.75 * (volumeRatio - 1.0) / 1.2
        }

        return (climbFit * 0.65 + volumeFit * 0.35).coerceIn(0.0, 1.0)
    }
}
