package fr.velo.cadence.routing

import android.util.Log
import fr.velo.cadence.model.BoundingBox
import fr.velo.cadence.model.Geo
import fr.velo.cadence.model.GeoPoint
import fr.velo.cadence.model.PlannedRoute
import fr.velo.cadence.model.RiderProfile
import fr.velo.cadence.model.RouteCandidate
import fr.velo.cadence.model.RouteRequest
import fr.velo.cadence.model.RouteSource
import fr.velo.cadence.net.BRouterClient
import fr.velo.cadence.net.RoutedTrack
import fr.velo.cadence.net.RoutingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs

/**
 * Genere des boucles a partir d'un point de depart et d'une distance cible.
 *
 * La difficulte vient de ce que BRouter raisonne en rayon de cercle et non en
 * longueur de boucle : le rapport entre les deux depend du relief et de la
 * densite du reseau routier, il varie de 6,3 en plaine a plus de 9 en
 * montagne. On part donc de l'approximation 2*pi puis on corrige par
 * iterations proportionnelles jusqu'a tomber dans la tolerance demandee.
 */
class RouteGenerator(
    private val client: BRouterClient = BRouterClient(),
) {

    data class Progress(val done: Int, val total: Int, val message: String)

    private companion object {
        const val TAG = "RouteGenerator"
        const val TOLERANCE = 0.08
        const val MAX_ITERATIONS = 3
        /** Deux boucles dont les traces se ressemblent a moins de 900 m sont jugees identiques. */
        const val DUPLICATE_THRESHOLD_M = 900.0
    }

    /**
     * Produit et classe des propositions. Les directions de depart sont
     * reparties sur 360 degres pour explorer des cotes differents.
     */
    suspend fun generate(
        request: RouteRequest,
        profile: RiderProfile,
        onProgress: (Progress) -> Unit = {},
    ): List<RouteCandidate> = coroutineScope {
        // On explore plus de directions qu'on ne renverra de resultats, pour
        // pouvoir ecarter les doublons et les echecs sans finir a court.
        val explored = (request.candidateCount + 3).coerceIn(4, 12)
        val step = 360 / explored
        val directions = (0 until explored).map { it * step }

        val done = java.util.concurrent.atomic.AtomicInteger(0)
        onProgress(Progress(0, explored, "Recherche de boucles autour du départ"))

        // Deux requetes en parallele au maximum : le serveur BRouter public
        // est un service benevole, on ne le sature pas.
        val results = directions.chunked(2).flatMap { chunk ->
            coroutineContext.ensureActive()
            val batch = chunk.map { direction ->
                async(Dispatchers.IO) {
                    runCatching { converge(request, direction) }
                        .onFailure { Log.w(TAG, "Direction $direction: ${it.message}") }
                        .getOrNull()
                        .also {
                            // Deux coroutines incrementent ce compteur en
                            // parallele : un entier atomique evite de perdre
                            // un increment et d'afficher "7/8" a la fin.
                            val completed = done.incrementAndGet()
                            onProgress(
                                Progress(
                                    completed,
                                    explored,
                                    "Boucle $completed/$explored analysée",
                                ),
                            )
                        }
                }
            }
            val out = batch.awaitAll()
            delay(250)
            out
        }.filterNotNull()

        if (results.isEmpty()) {
            throw RoutingException(
                "Aucune boucle n'a pu être calculée depuis ce point. " +
                    "Vérifiez la connexion, ou essayez un départ plus proche d'une route.",
            )
        }

        val candidates = results.map { (direction, track) ->
            val instructions = track.instructions
            val route = PlannedRoute(
                name = suggestName(request, track),
                points = track.points,
                distanceM = track.distanceM,
                ascentM = track.ascentM,
                descentM = track.descentM,
                instructions = instructions,
                surface = track.surface,
                estimatedDurationMs = RideEstimator.estimateDuration(
                    points = track.points,
                    profile = profile,
                    junctionCount = instructions.size,
                ),
                source = RouteSource.GENERATED,
            )
            val (score, breakdown) = RouteScorer.score(route, request)
            RouteCandidate(route, score, breakdown, direction)
        }

        deduplicate(candidates)
            .sortedByDescending { it.score }
            .take(request.candidateCount)
    }

    /**
     * Cherche le rayon qui produit une boucle de la longueur voulue.
     * Renvoie la direction utilisee et la trace retenue.
     */
    private suspend fun converge(
        request: RouteRequest,
        direction: Int,
    ): Pair<Int, RoutedTrack> {
        val targetM = request.targetDistanceKm * 1000.0
        var radius = targetM / (2 * PI)
        var best: RoutedTrack? = null
        var bestError = Double.MAX_VALUE

        repeat(MAX_ITERATIONS) { iteration ->
            coroutineContext.ensureActive()
            val track = client.roundTrip(
                start = request.start,
                profile = request.roadStyle.brouterProfile,
                radiusM = radius.coerceIn(400.0, 90_000.0),
                points = pointsForDistance(request.targetDistanceKm),
                directionDeg = direction,
            )
            val error = abs(track.distanceM - targetM) / targetM
            if (error < bestError) {
                bestError = error
                best = track
            }
            if (error <= TOLERANCE) return direction to track

            // Correction proportionnelle, amortie pour eviter les oscillations.
            val ratio = (targetM / track.distanceM).coerceIn(0.45, 2.2)
            radius *= (1.0 + (ratio - 1.0) * 0.85)
            if (iteration < MAX_ITERATIONS - 1) delay(200)
        }

        return direction to (best ?: throw RoutingException("Boucle introuvable"))
    }

    /**
     * Plus la boucle est longue, plus il faut de points de construction pour
     * qu'elle reste circulaire au lieu de partir en aller-retour.
     */
    private fun pointsForDistance(targetKm: Double): Int = when {
        targetKm < 25 -> 4
        targetKm < 60 -> 5
        targetKm < 110 -> 6
        targetKm < 180 -> 8
        else -> 10
    }

    /**
     * Ecarte les boucles qui se superposent : deux directions voisines
     * finissent souvent sur le meme itineraire.
     */
    private fun deduplicate(candidates: List<RouteCandidate>): List<RouteCandidate> {
        val sorted = candidates.sortedByDescending { it.score }
        val kept = ArrayList<RouteCandidate>(sorted.size)
        val signatures = ArrayList<List<GeoPoint>>(sorted.size)
        for (candidate in sorted) {
            val signature = resample(candidate.route.points, 24)
            val duplicate = signatures.any { meanSeparation(it, signature) < DUPLICATE_THRESHOLD_M }
            if (!duplicate) {
                kept.add(candidate)
                signatures.add(signature)
            }
        }
        return kept
    }

    /** Reechantillonne une trace en [count] points equidistants. */
    private fun resample(points: List<GeoPoint>, count: Int): List<GeoPoint> {
        if (points.size <= count) return points
        val step = (points.size - 1).toDouble() / (count - 1)
        return (0 until count).map { points[(it * step).toInt().coerceAtMost(points.lastIndex)] }
    }

    /**
     * Distance moyenne entre deux signatures, en testant aussi le sens
     * inverse : la meme boucle parcourue a l'envers reste la meme boucle.
     */
    private fun meanSeparation(a: List<GeoPoint>, b: List<GeoPoint>): Double {
        if (a.isEmpty() || b.isEmpty()) return Double.MAX_VALUE
        val forward = a.indices.sumOf { i ->
            val j = (i.toDouble() / a.size * b.size).toInt().coerceAtMost(b.lastIndex)
            Geo.distance(a[i], b[j])
        } / a.size
        val backward = a.indices.sumOf { i ->
            val j = ((a.size - 1 - i).toDouble() / a.size * b.size).toInt().coerceAtMost(b.lastIndex)
            Geo.distance(a[i], b[j])
        } / a.size
        return minOf(forward, backward)
    }

    private fun suggestName(request: RouteRequest, track: RoutedTrack): String {
        val km = (track.distanceM / 1000.0).toInt()
        val ascentPerKm = if (track.distanceM > 100) track.ascentM / (track.distanceM / 1000.0) else 0.0
        val relief = when {
            ascentPerKm < 6 -> "plat"
            ascentPerKm < 13 -> "vallonné"
            else -> "montagneux"
        }
        val place = request.startLabel.substringBefore(',').trim()
        return if (place.isBlank()) {
            "Boucle de $km km ($relief)"
        } else {
            "Boucle de $km km au départ de $place ($relief)"
        }
    }
}

/** Utilitaire de cadrage carte pour un ensemble de parcours. */
fun boundsOf(routes: List<PlannedRoute>): BoundingBox? =
    BoundingBox.of(routes.flatMap { it.points })
