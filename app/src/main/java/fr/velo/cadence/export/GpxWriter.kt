package fr.velo.cadence.export

import fr.velo.cadence.model.Geo
import fr.velo.cadence.model.PlannedRoute
import fr.velo.cadence.model.RidePoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Ecriture de fichiers GPX.
 *
 * Deux precautions dictees par le comportement des compteurs iGPSPORT :
 * - on ecrit une piste (`<trk>`) et non un itineraire (`<rte>`), car c'est ce
 *   que les compteurs lisent de facon fiable ;
 * - la trace est simplifiee, un fichier de plusieurs centaines de milliers de
 *   points etant refuse ou tronque par l'application compagnon.
 */
object GpxWriter {

    private const val MAX_TRACK_POINTS = 5_000

    private val timestampFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    /** Parcours planifie, destine a etre suivi par le compteur. */
    fun writeRoute(route: PlannedRoute, includeWaypoints: Boolean = true): String {
        val points = Geo.capPoints(route.points, MAX_TRACK_POINTS)
        val sb = StringBuilder(points.size * 90 + 1024)
        sb.append(header())
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(escape(route.name)).append("</name>\n")
        sb.append("    <time>").append(timestampFormat.format(Date(route.createdAt))).append("</time>\n")
        sb.append("  </metadata>\n")

        if (includeWaypoints) {
            // Les consignes sont aussi ecrites en points d'interet : certains
            // logiciels et compteurs savent les afficher, les autres les
            // ignorent sans erreur.
            for (instruction in route.instructions) {
                val p = route.points.getOrNull(instruction.pointIndex) ?: continue
                sb.append("  <wpt lat=\"").append(coord(p.lat))
                    .append("\" lon=\"").append(coord(p.lon)).append("\">\n")
                p.ele?.let { sb.append("    <ele>").append(elevation(it)).append("</ele>\n") }
                sb.append("    <name>").append(escape(instruction.type.shortLabel)).append("</name>\n")
                sb.append("    <sym>").append(escape(symbolFor(instruction.type.name))).append("</sym>\n")
                sb.append("  </wpt>\n")
            }
        }

        sb.append("  <trk>\n")
        sb.append("    <name>").append(escape(route.name)).append("</name>\n")
        sb.append("    <type>cycling</type>\n")
        sb.append("    <trkseg>\n")
        for (p in points) {
            sb.append("      <trkpt lat=\"").append(coord(p.lat))
                .append("\" lon=\"").append(coord(p.lon)).append("\">")
            p.ele?.let { sb.append("<ele>").append(elevation(it)).append("</ele>") }
            sb.append("</trkpt>\n")
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }

    /** Sortie enregistree, destinee a etre importee dans Strava ou un autre service. */
    fun writeRide(title: String, points: List<RidePoint>): String {
        val sb = StringBuilder(points.size * 140 + 1024)
        sb.append(header())
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(escape(title)).append("</name>\n")
        points.firstOrNull()?.let {
            sb.append("    <time>").append(timestampFormat.format(Date(it.timestamp))).append("</time>\n")
        }
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n    <name>").append(escape(title)).append("</name>\n")
        sb.append("    <type>cycling</type>\n    <trkseg>\n")

        val formatter = timestampFormat
        for (p in points) {
            sb.append("      <trkpt lat=\"").append(coord(p.lat))
                .append("\" lon=\"").append(coord(p.lon)).append("\">\n")
            p.ele?.let { sb.append("        <ele>").append(elevation(it)).append("</ele>\n") }
            sb.append("        <time>").append(formatter.format(Date(p.timestamp))).append("</time>\n")

            val hasExtensions = p.heartRate != null || p.cadence != null || p.power != null
            if (hasExtensions) {
                sb.append("        <extensions>\n")
                p.power?.let { sb.append("          <power>").append(it).append("</power>\n") }
                if (p.heartRate != null || p.cadence != null) {
                    sb.append("          <gpxtpx:TrackPointExtension>\n")
                    p.heartRate?.let {
                        sb.append("            <gpxtpx:hr>").append(it).append("</gpxtpx:hr>\n")
                    }
                    p.cadence?.let {
                        sb.append("            <gpxtpx:cad>").append(it).append("</gpxtpx:cad>\n")
                    }
                    sb.append("          </gpxtpx:TrackPointExtension>\n")
                }
                sb.append("        </extensions>\n")
            }
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }

    private fun header(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"Cadence\"\n")
        append("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        append("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        append("     xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\"\n")
        append("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 ")
        append("http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
    }

    private fun symbolFor(type: String): String = when {
        type.contains("LEFT") -> "Left"
        type.contains("RIGHT") -> "Right"
        type == "ARRIVAL" -> "Flag, Blue"
        type == "DEPART" -> "Flag, Green"
        else -> "Waypoint"
    }

    private fun coord(value: Double): String = "%.6f".format(Locale.US, value)

    private fun elevation(value: Double): String = "%.1f".format(Locale.US, value)

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
