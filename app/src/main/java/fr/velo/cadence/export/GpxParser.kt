package fr.velo.cadence.export

import android.util.Xml
import fr.velo.cadence.model.Geo
import fr.velo.cadence.model.GeoPoint
import fr.velo.cadence.model.PlannedRoute
import fr.velo.cadence.model.RouteSource
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Lecture d'un GPX ou d'un TCX importe (fichier recu depuis Komoot, Strava,
 * un club, ou partage par un autre cycliste).
 *
 * On accepte indifferemment `<trkpt>`, `<rtept>` et, a defaut, `<wpt>` :
 * chaque outil exporte a sa maniere et refuser un fichier pour cette raison
 * serait inutilement rigide.
 */
object GpxParser {

    fun parse(input: InputStream, fallbackName: String = "Parcours importé"): PlannedRoute {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val trackPoints = ArrayList<GeoPoint>()
        val routePoints = ArrayList<GeoPoint>()
        val waypoints = ArrayList<GeoPoint>()
        var name: String? = null

        var currentLat: Double? = null
        var currentLon: Double? = null
        var currentEle: Double? = null
        var currentTag: String? = null
        var inName = false
        var nameDepth = -1

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfterLast(':').lowercase()
                    currentTag = tag
                    when (tag) {
                        "trkpt", "rtept", "wpt" -> {
                            currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            currentEle = null
                        }
                        // TCX : les coordonnees sont dans des elements, pas des attributs.
                        "trackpoint" -> {
                            currentLat = null
                            currentLon = null
                            currentEle = null
                        }
                        "name" -> if (name == null) {
                            inName = true
                            nameDepth = parser.depth
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        when {
                            inName && name == null -> name = text
                            currentTag == "ele" || currentTag == "altitudemeters" ->
                                currentEle = text.toDoubleOrNull()
                            currentTag == "latitudedegrees" -> currentLat = text.toDoubleOrNull()
                            currentTag == "longitudedegrees" -> currentLon = text.toDoubleOrNull()
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfterLast(':').lowercase()
                    when (tag) {
                        "trkpt", "trackpoint" -> {
                            val lat = currentLat
                            val lon = currentLon
                            if (lat != null && lon != null) trackPoints.add(GeoPoint(lat, lon, currentEle))
                        }
                        "rtept" -> {
                            val lat = currentLat
                            val lon = currentLon
                            if (lat != null && lon != null) routePoints.add(GeoPoint(lat, lon, currentEle))
                        }
                        "wpt" -> {
                            val lat = currentLat
                            val lon = currentLon
                            if (lat != null && lon != null) waypoints.add(GeoPoint(lat, lon, currentEle))
                        }
                        "name" -> if (parser.depth == nameDepth) inName = false
                    }
                    currentTag = null
                }
            }
            event = parser.next()
        }

        val points = when {
            trackPoints.size >= 2 -> trackPoints
            routePoints.size >= 2 -> routePoints
            waypoints.size >= 2 -> waypoints
            else -> throw IllegalArgumentException(
                "Ce fichier ne contient pas de trace exploitable.",
            )
        }

        val (ascent, descent) = Geo.elevationGainLoss(points)
        return PlannedRoute(
            name = name?.takeIf { it.isNotBlank() } ?: fallbackName,
            points = points,
            distanceM = Geo.pathLength(points),
            ascentM = ascent,
            descentM = descent,
            source = RouteSource.IMPORTED,
        )
    }
}
