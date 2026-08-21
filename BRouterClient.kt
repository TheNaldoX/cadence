package fr.velo.cadence.net

import fr.velo.cadence.BuildConfig
import fr.velo.cadence.model.Geo
import fr.velo.cadence.model.GeoPoint
import fr.velo.cadence.model.SurfaceBreakdown
import fr.velo.cadence.model.TurnInstruction
import fr.velo.cadence.model.TurnType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import kotlin.math.abs

/** Trace brute renvoyee par le moteur de routage. */
data class RoutedTrack(
    val points: List<GeoPoint>,
    val distanceM: Double,
    val ascentM: Double,
    val descentM: Double,
    val instructions: List<TurnInstruction>,
    val surface: SurfaceBreakdown,
    val engineTotalTimeS: Double,
)

/**
 * Client du moteur de routage BRouter.
 *
 * BRouter est concu pour le velo : ses profils integrent le revetement, la
 * pente et une estimation du trafic, et il sait generer des boucles
 * (engineMode=4), ce qui est exactement ce qu'il faut pour proposer des
 * sorties a partir d'une distance cible.
 *
 * Piege important : `roundTripDistance` n'est pas la longueur de la boucle
 * mais le RAYON du cercle sur lequel les points intermediaires sont places.
 * La longueur obtenue vaut environ 2*pi*R, davantage en relief. Le generateur
 * corrige donc le rayon par iterations successives.
 */
class BRouterClient(
    private val baseUrl: String = BuildConfig.BROUTER_BASE_URL,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Itineraire passant par les points donnes, dans l'ordre. */
    suspend fun route(
        waypoints: List<GeoPoint>,
        profile: String,
    ): RoutedTrack = withContext(Dispatchers.IO) {
        require(waypoints.size >= 2) { "Il faut au moins deux points" }
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("lonlats", waypoints.joinToString("|") { fmt(it) })
            .addQueryParameter("profile", profile)
            .addQueryParameter("alternativeidx", "0")
            .addQueryParameter("format", "geojson")
            .addQueryParameter("timode", "2")
            .build()
        parse(fetch(url.toString()))
    }

    /**
     * Boucle partant et revenant au point donne.
     *
     * @param radiusM rayon du cercle de construction, pas la longueur finale
     * @param directionDeg direction initiale ; la fixer rend le resultat
     *   reproductible, alors que BRouter tire une direction au hasard sinon
     */
    suspend fun roundTrip(
        start: GeoPoint,
        profile: String,
        radiusM: Double,
        points: Int = 5,
        directionDeg: Int,
    ): RoutedTrack = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("lonlats", fmt(start))
            .addQueryParameter("profile", profile)
            .addQueryParameter("format", "geojson")
            .addQueryParameter("timode", "2")
            .addQueryParameter("engineMode", "4")
            .addQueryParameter("roundTripDistance", radiusM.toInt().toString())
            .addQueryParameter("roundTripPoints", points.coerceIn(3, 20).toString())
            .addQueryParameter("direction", (((directionDeg % 360) + 360) % 360).toString())
            .build()
        parse(fetch(url.toString()))
    }

    private fun fmt(p: GeoPoint): String =
        "%.6f,%.6f".format(java.util.Locale.US, p.lon, p.lat)

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url).get().build()
        Http.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw RoutingException(
                    "Le serveur de routage a répondu ${response.code}. " +
                        if (body.length < 200) body else "",
                )
            }
            if (body.isBlank()) throw RoutingException("Réponse de routage vide")
            return body
        }
    }

    private fun parse(body: String): RoutedTrack {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw RoutingException("Réponse de routage illisible", it) }

        // Acces defensifs : `jsonArray` et `jsonObject` levent une exception si
        // l'element n'a pas le bon type, ce qui court-circuiterait le traitement
        // d'erreur du reste de l'application.
        val feature = (root["features"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw RoutingException("Aucun itinéraire trouvé pour ces paramètres")
        val props = feature["properties"] as? JsonObject ?: JsonObject(emptyMap())

        val coords = (feature["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray
            ?: throw RoutingException("Itinéraire sans géométrie")

        val points = coords.mapNotNull { element ->
            val arr = element as? JsonArray ?: return@mapNotNull null
            val lon = arr.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lat = arr.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val ele = arr.getOrNull(2)?.jsonPrimitive?.doubleOrNull
            GeoPoint(lat, lon, ele)
        }
        if (points.size < 2) throw RoutingException("Itinéraire trop court")

        val distance = props.num("track-length") ?: Geo.pathLength(points)
        val ascent = props.num("filtered ascend")
            ?: props.num("plain-ascend")
            ?: Geo.elevationGainLoss(points).first
        // BRouter ne renvoie pas le denivele negatif : sur une boucle il egale
        // le positif, sinon on le recalcule depuis les altitudes.
        val descent = Geo.elevationGainLoss(points).second.takeIf { it > 0 } ?: ascent

        return RoutedTrack(
            points = points,
            distanceM = distance,
            ascentM = ascent,
            descentM = descent,
            instructions = parseInstructions(props, points),
            surface = parseSurface(props, distance),
            engineTotalTimeS = props.num("total-time") ?: 0.0,
        )
    }

    private fun JsonObject.num(key: String): Double? {
        val prim = this[key] as? JsonPrimitive ?: return null
        return prim.doubleOrNull ?: prim.contentOrNull?.trim()?.toDoubleOrNull()
    }

    /**
     * Les "voice hints" de BRouter reperent les vrais points de decision. Le
     * code de manoeuvre a change entre versions du moteur, donc on ne s'y fie
     * pas : le type de virage est rededuit de la geometrie locale, ce qui
     * reste juste quelle que soit la version du serveur.
     */
    private fun parseInstructions(props: JsonObject, points: List<GeoPoint>): List<TurnInstruction> {
        val cumulative = Geo.cumulativeDistances(points)
        val hints = props["voicehints"] as? JsonArray
        val indices = buildList {
            hints?.forEach { element ->
                val arr = element as? JsonArray ?: return@forEach
                val idx = arr.getOrNull(0)?.jsonPrimitive?.doubleOrNull?.toInt() ?: return@forEach
                if (idx in points.indices) add(idx)
            }
        }.distinct().sorted()

        val out = ArrayList<TurnInstruction>(indices.size + 2)
        out.add(TurnInstruction(0, TurnType.DEPART, 0.0))
        for (idx in indices) {
            if (idx == 0 || idx == points.lastIndex) continue
            val type = turnTypeAt(points, idx)
            if (type == TurnType.CONTINUE) continue
            out.add(TurnInstruction(idx, type, cumulative[idx]))
        }
        out.add(
            TurnInstruction(
                points.lastIndex,
                TurnType.ARRIVAL,
                cumulative[points.lastIndex],
            ),
        )
        return out
    }

    /**
     * Type de manoeuvre deduit du changement de cap, mesure sur une fenetre
     * d'environ 30 m de part et d'autre pour ne pas confondre un virage avec
     * la courbure naturelle de la route.
     */
    private fun turnTypeAt(points: List<GeoPoint>, index: Int): TurnType {
        val before = pointAtDistance(points, index, -30.0)
        val after = pointAtDistance(points, index, 30.0)
        val incoming = Geo.bearing(before, points[index])
        val outgoing = Geo.bearing(points[index], after)
        val delta = Geo.angleDelta(incoming, outgoing)
        val a = abs(delta)
        return when {
            a < 20 -> TurnType.CONTINUE
            a > 150 -> TurnType.U_TURN
            delta > 0 -> when {
                a < 45 -> TurnType.SLIGHT_RIGHT
                a < 110 -> TurnType.RIGHT
                else -> TurnType.SHARP_RIGHT
            }
            else -> when {
                a < 45 -> TurnType.SLIGHT_LEFT
                a < 110 -> TurnType.LEFT
                else -> TurnType.SHARP_LEFT
            }
        }
    }

    private fun pointAtDistance(points: List<GeoPoint>, from: Int, offsetM: Double): GeoPoint {
        var remaining = abs(offsetM)
        val step = if (offsetM >= 0) 1 else -1
        var i = from
        while (remaining > 0) {
            val next = i + step
            if (next !in points.indices) return points[i]
            remaining -= Geo.distance(points[i], points[next])
            i = next
        }
        return points[i]
    }

    /**
     * Les "messages" de BRouter donnent, segment par segment, les tags OSM de
     * la voie empruntee. On en tire la repartition des revetements et un
     * indice de trafic, sans avoir a interroger Overpass.
     */
    private fun parseSurface(props: JsonObject, totalDistance: Double): SurfaceBreakdown {
        val messages = props["messages"] as? JsonArray ?: return SurfaceBreakdown(totalM = totalDistance)
        if (messages.size < 2) return SurfaceBreakdown(totalM = totalDistance)

        val header = (messages[0] as? JsonArray)?.map {
            (it as? JsonPrimitive)?.contentOrNull.orEmpty()
        } ?: return SurfaceBreakdown(totalM = totalDistance)

        val distanceCol = header.indexOfFirst { it.equals("Distance", ignoreCase = true) }
        val tagsCol = header.indexOfFirst { it.equals("WayTags", ignoreCase = true) }
        if (distanceCol < 0 || tagsCol < 0) return SurfaceBreakdown(totalM = totalDistance)

        val bySurface = HashMap<String, Double>()
        val byHighway = HashMap<String, Double>()
        var trafficWeighted = 0.0
        var total = 0.0

        for (i in 1 until messages.size) {
            val row = messages[i] as? JsonArray ?: continue
            val segment = row.getOrNull(distanceCol)?.jsonPrimitive?.contentOrNull
                ?.trim()?.toDoubleOrNull() ?: continue
            if (segment <= 0.0) continue
            val tags = row.getOrNull(tagsCol)?.jsonPrimitive?.contentOrNull.orEmpty()
            val parsed = parseTags(tags)

            val surface = parsed["surface"] ?: inferSurface(parsed["highway"])
            val highway = parsed["highway"] ?: "unknown"

            bySurface[surface] = (bySurface[surface] ?: 0.0) + segment
            byHighway[highway] = (byHighway[highway] ?: 0.0) + segment
            trafficWeighted += segment * trafficWeight(parsed)
            total += segment
        }

        return SurfaceBreakdown(
            bySurface = bySurface,
            byHighway = byHighway,
            trafficWeightedM = trafficWeighted,
            totalM = if (total > 0) total else totalDistance,
        )
    }

    private fun parseTags(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val out = HashMap<String, String>()
        for (token in raw.split(' ', '\t')) {
            val eq = token.indexOf('=')
            if (eq > 0 && eq < token.length - 1) {
                out[token.substring(0, eq)] = token.substring(eq + 1)
            }
        }
        return out
    }

    private fun inferSurface(highway: String?): String = when (highway) {
        null -> "unknown"
        "motorway", "trunk", "primary", "secondary", "tertiary",
        "residential", "living_street", "unclassified", "service",
        -> "unknown"
        "track", "path", "bridleway" -> "unpaved"
        else -> "unknown"
    }

    /**
     * Indice de trafic entre 0 et 1. BRouter fournit parfois
     * `estimated_traffic_class` ; sinon la classe de voie OSM donne une
     * approximation suffisante pour comparer deux parcours entre eux.
     */
    private fun trafficWeight(tags: Map<String, String>): Double {
        tags["estimated_traffic_class"]?.toIntOrNull()?.let {
            return (it / 6.0).coerceIn(0.0, 1.0)
        }
        val base = when (tags["highway"]) {
            "motorway", "motorway_link", "trunk", "trunk_link" -> 1.0
            "primary", "primary_link" -> 0.85
            "secondary", "secondary_link" -> 0.60
            "tertiary", "tertiary_link" -> 0.35
            "unclassified" -> 0.20
            "residential" -> 0.25
            "living_street" -> 0.10
            "service" -> 0.15
            "cycleway", "path", "track", "footway", "bridleway" -> 0.02
            else -> 0.30
        }
        // Une voie interdite aux vehicules motorises est calme quel que soit
        // son classement administratif.
        if (tags["motor_vehicle"] == "no" || tags["access"] == "no") return base * 0.2
        if (tags["bicycle_road"] == "yes" || tags["cyclestreet"] == "yes") return base * 0.3
        return base
    }
}
