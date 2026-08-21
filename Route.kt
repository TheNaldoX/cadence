package fr.velo.cadence.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Type de manoeuvre, aligne sur les "voice hints" de BRouter. */
@Serializable
enum class TurnType(val label: String) {
    DEPART("Départ"),
    CONTINUE("Tout droit"),
    SLIGHT_LEFT("Légèrement à gauche"),
    LEFT("À gauche"),
    SHARP_LEFT("Franchement à gauche"),
    SLIGHT_RIGHT("Légèrement à droite"),
    RIGHT("À droite"),
    SHARP_RIGHT("Franchement à droite"),
    U_TURN("Demi-tour"),
    ROUNDABOUT("Rond-point"),
    ARRIVAL("Arrivée");

    /**
     * Code CoursePoint FIT correspondant (profil Course du SDK Garmin),
     * egalement accepte par les compteurs iGPSPORT.
     */
    val fitCoursePointType: Int
        get() = when (this) {
            DEPART -> 0
            CONTINUE -> 8
            SLIGHT_LEFT -> 14
            LEFT -> 6
            SHARP_LEFT -> 12
            SLIGHT_RIGHT -> 15
            RIGHT -> 7
            SHARP_RIGHT -> 13
            U_TURN -> 5
            ROUNDABOUT -> 0
            ARRIVAL -> 10
        }

    /** Libelle court utilise comme nom de CoursePoint (limite a 16 caracteres). */
    val shortLabel: String
        get() = when (this) {
            DEPART -> "Depart"
            CONTINUE -> "Tout droit"
            SLIGHT_LEFT -> "Legt gauche"
            LEFT -> "Gauche"
            SHARP_LEFT -> "Franc gauche"
            SLIGHT_RIGHT -> "Legt droite"
            RIGHT -> "Droite"
            SHARP_RIGHT -> "Franc droite"
            U_TURN -> "Demi-tour"
            ROUNDABOUT -> "Rond-point"
            ARRIVAL -> "Arrivee"
        }
}

/** Une consigne de navigation rattachee a un index de la polyligne. */
@Serializable
data class TurnInstruction(
    val pointIndex: Int,
    val type: TurnType,
    val distanceFromStartM: Double,
    val streetName: String? = null,
) {
    val text: String
        get() = if (streetName.isNullOrBlank()) type.label else "${type.label} sur $streetName"
}

/** Repartition du parcours par revetement, en metres. */
@Serializable
data class SurfaceBreakdown(
    val bySurface: Map<String, Double> = emptyMap(),
    val byHighway: Map<String, Double> = emptyMap(),
    val trafficWeightedM: Double = 0.0,
    val totalM: Double = 0.0,
) {
    /** Part du parcours sur revetement lisse (bitume, beton). */
    val pavedRatio: Double
        get() {
            if (totalM <= 0.0) return 1.0
            val paved = bySurface.entries
                .filter { it.key in PAVED_SURFACES }
                .sumOf { it.value }
            val unknown = bySurface["unknown"] ?: 0.0
            // Une voie sans tag "surface" en France est goudronnee dans la
            // tres grande majorite des cas si elle est classee route.
            return ((paved + unknown * 0.85) / totalM).coerceIn(0.0, 1.0)
        }

    /** Indice de trafic moyen estime, de 0 (desert) a 1 (axe passant). */
    val trafficIndex: Double
        get() = if (totalM <= 0.0) 0.0 else (trafficWeightedM / totalM).coerceIn(0.0, 1.0)

    fun topSurfaces(limit: Int = 3): List<Pair<String, Double>> =
        bySurface.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }

    companion object {
        val PAVED_SURFACES = setOf("asphalt", "paved", "concrete", "chipseal", "concrete:plates")
    }
}

/** Un parcours, propose par le moteur ou importe par l'utilisateur. */
data class PlannedRoute(
    val id: Long = 0L,
    val name: String,
    val points: List<GeoPoint>,
    val distanceM: Double,
    val ascentM: Double,
    val descentM: Double,
    val instructions: List<TurnInstruction> = emptyList(),
    val surface: SurfaceBreakdown = SurfaceBreakdown(),
    val estimatedDurationMs: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val source: RouteSource = RouteSource.GENERATED,
    val isFavorite: Boolean = false,
) {
    val ascentPerKm: Double
        get() = if (distanceM > 100) ascentM / (distanceM / 1000.0) else 0.0

    val distanceKm: Double get() = distanceM / 1000.0

    val bounds: BoundingBox? get() = BoundingBox.of(points)
}

enum class RouteSource { GENERATED, IMPORTED, FROM_RIDE, MANUAL }

/** Un candidat produit par le generateur, avec son score et son detail. */
data class RouteCandidate(
    val route: PlannedRoute,
    val score: Double,
    val breakdown: ScoreBreakdown,
    val seedDirection: Int,
) {
    val scorePercent: Int get() = (score * 100).roundToInt().coerceIn(0, 100)
}

/**
 * Detail du score, affiche a l'utilisateur pour qu'il comprenne pourquoi un
 * parcours est propose plutot qu'un autre.
 */
data class ScoreBreakdown(
    val distanceMatch: Double,
    val terrainMatch: Double,
    val surfaceQuality: Double,
    val trafficCalm: Double,
    val effortFit: Double,
) {
    fun asList(): List<Pair<String, Double>> = listOf(
        "Distance" to distanceMatch,
        "Relief" to terrainMatch,
        "Revêtement" to surfaceQuality,
        "Tranquillité" to trafficCalm,
        "Effort" to effortFit,
    )
}

/** Parametres saisis par l'utilisateur pour generer des propositions. */
data class RouteRequest(
    val start: GeoPoint,
    val startLabel: String = "",
    val targetDistanceKm: Double = 50.0,
    val terrain: TerrainPreference = TerrainPreference.INDIFFERENT,
    val roadStyle: RoadStyle = RoadStyle.TRANQUILLE,
    val level: RiderLevel = RiderLevel.LOISIR,
    val candidateCount: Int = 6,
)
