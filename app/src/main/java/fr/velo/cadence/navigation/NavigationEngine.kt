package fr.velo.cadence.navigation

import fr.velo.cadence.model.Geo
import fr.velo.cadence.model.GeoPoint
import fr.velo.cadence.model.PlannedRoute
import fr.velo.cadence.model.TurnInstruction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NavigationState(
    val active: Boolean = false,
    val routeName: String = "",
    val nextInstruction: TurnInstruction? = null,
    val distanceToNextM: Double = 0.0,
    val distanceCoveredM: Double = 0.0,
    val distanceRemainingM: Double = 0.0,
    val ascentRemainingM: Double = 0.0,
    val offRoute: Boolean = false,
    val distanceFromRouteM: Double = 0.0,
    val snappedIndex: Int = 0,
) {
    val progress: Float
        get() {
            val total = distanceCoveredM + distanceRemainingM
            return if (total <= 0) 0f else (distanceCoveredM / total).toFloat().coerceIn(0f, 1f)
        }
}

/**
 * Suivi d'un parcours pendant la sortie.
 *
 * Le BSC200S ne fait que du suivi de trace, sans carte : l'application prend
 * donc en charge l'affichage detaille et les annonces, pendant que le compteur
 * sert de repere sur le guidon.
 *
 * La recherche du point le plus proche est bornee a une fenetre autour de la
 * derniere position connue : chercher sur toute la trace a chaque seconde
 * coute cher, et surtout ferait sauter la progression d'un aller a un retour
 * qui se croisent.
 */
class NavigationEngine {

    private companion object {
        const val OFF_ROUTE_THRESHOLD_M = 45.0
        const val OFF_ROUTE_CONFIRMATIONS = 3
        const val SEARCH_WINDOW = 400
        const val ANNOUNCE_FAR_M = 300.0
        const val ANNOUNCE_NEAR_M = 100.0
        const val ANNOUNCE_IMMEDIATE_M = 30.0
    }

    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private var route: PlannedRoute? = null
    private var cumulative: DoubleArray = DoubleArray(0)
    private var ascentAhead: DoubleArray = DoubleArray(0)
    private var lastIndex = 0
    private var offRouteCount = 0
    private val announcedFor = mutableMapOf<Int, Double>()

    /** Annonce a prononcer, consommee une seule fois par l'appelant. */
    private val _announcement = MutableStateFlow<String?>(null)
    val announcement: StateFlow<String?> = _announcement.asStateFlow()

    fun consumeAnnouncement() {
        _announcement.value = null
    }

    fun start(route: PlannedRoute) {
        this.route = route
        cumulative = Geo.cumulativeDistances(route.points)
        ascentAhead = computeAscentAhead(route.points)
        lastIndex = 0
        offRouteCount = 0
        announcedFor.clear()
        _state.value = NavigationState(
            active = true,
            routeName = route.name,
            nextInstruction = route.instructions.firstOrNull { it.pointIndex > 0 },
            distanceRemainingM = route.distanceM,
            ascentRemainingM = route.ascentM,
        )
    }

    fun stop() {
        route = null
        _state.value = NavigationState()
    }

    fun update(position: GeoPoint) {
        val current = route ?: return
        val points = current.points
        if (points.size < 2) return

        val from = (lastIndex - 20).coerceAtLeast(0)
        val to = (lastIndex + SEARCH_WINDOW).coerceAtMost(points.lastIndex)

        var bestIndex = lastIndex
        var bestDistance = Double.MAX_VALUE
        for (i in from until to) {
            val d = Geo.distanceToSegment(position, points[i], points[i + 1])
            if (d < bestDistance) {
                bestDistance = d
                bestIndex = i
            }
        }

        // Si rien de convaincant dans la fenetre, on rebalaye toute la trace :
        // c'est le cas apres un ecart ou une reprise en cours de parcours.
        if (bestDistance > OFF_ROUTE_THRESHOLD_M * 2) {
            for (i in 0 until points.lastIndex) {
                val d = Geo.distanceToSegment(position, points[i], points[i + 1])
                if (d < bestDistance) {
                    bestDistance = d
                    bestIndex = i
                }
            }
        }

        lastIndex = bestIndex
        offRouteCount = if (bestDistance > OFF_ROUTE_THRESHOLD_M) offRouteCount + 1 else 0
        val offRoute = offRouteCount >= OFF_ROUTE_CONFIRMATIONS

        val covered = cumulative[bestIndex]
        val remaining = (current.distanceM - covered).coerceAtLeast(0.0)
        val next = current.instructions.firstOrNull { it.pointIndex > bestIndex }
        val toNext = next?.let { (cumulative[it.pointIndex] - covered).coerceAtLeast(0.0) } ?: 0.0

        if (next != null && !offRoute) maybeAnnounce(next, toNext)
        if (offRoute && offRouteCount == OFF_ROUTE_CONFIRMATIONS) {
            _announcement.value = "Vous vous êtes écarté du parcours"
        }

        _state.value = NavigationState(
            active = true,
            routeName = current.name,
            nextInstruction = next,
            distanceToNextM = toNext,
            distanceCoveredM = covered,
            distanceRemainingM = remaining,
            ascentRemainingM = ascentAhead.getOrElse(bestIndex) { 0.0 },
            offRoute = offRoute,
            distanceFromRouteM = bestDistance,
            snappedIndex = bestIndex,
        )
    }

    /**
     * Trois annonces par manoeuvre : anticipation, preparation, execution.
     * Le seuil deja franchi est memorise pour ne pas repeter la meme consigne
     * a chaque position recue.
     */
    private fun maybeAnnounce(instruction: TurnInstruction, distanceM: Double) {
        val last = announcedFor[instruction.pointIndex] ?: Double.MAX_VALUE
        val threshold = when {
            distanceM <= ANNOUNCE_IMMEDIATE_M -> ANNOUNCE_IMMEDIATE_M
            distanceM <= ANNOUNCE_NEAR_M -> ANNOUNCE_NEAR_M
            distanceM <= ANNOUNCE_FAR_M -> ANNOUNCE_FAR_M
            else -> return
        }
        if (threshold >= last) return
        announcedFor[instruction.pointIndex] = threshold

        _announcement.value = when (threshold) {
            ANNOUNCE_IMMEDIATE_M -> instruction.type.label
            ANNOUNCE_NEAR_M -> "Dans 100 mètres, ${instruction.type.label.lowercase()}"
            else -> "Dans 300 mètres, ${instruction.type.label.lowercase()}"
        }
    }

    /** Denivele positif restant a partir de chaque point de la trace. */
    private fun computeAscentAhead(points: List<GeoPoint>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in points.size - 2 downTo 0) {
            val a = points[i].ele
            val b = points[i + 1].ele
            val delta = if (a != null && b != null) (b - a).coerceAtLeast(0.0) else 0.0
            out[i] = out[i + 1] + delta
        }
        return out
    }
}
