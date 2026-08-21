package fr.velo.cadence.export

import fr.velo.cadence.model.Geo
import fr.velo.cadence.model.PlannedRoute
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Ecriture d'un parcours au format TCX Course.
 *
 * Contrairement au GPX, le TCX porte nativement des `<CoursePoint>`, c'est-a-
 * dire de vraies consignes de virage. Le manuel du BSC200S annonce le TCX
 * comme format accepte, c'est donc la meilleure option pour obtenir des
 * alertes de direction sur le compteur sans passer par du binaire.
 */
object TcxWriter {

    private const val MAX_TRACK_POINTS = 5_000
    private const val MAX_COURSE_POINTS = 190

    private val timestampFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    fun writeCourse(route: PlannedRoute): String {
        val points = Geo.capPoints(route.points, MAX_TRACK_POINTS)
        require(points.size >= 2) { "Parcours trop court pour être exporté" }
        val cumulative = Geo.cumulativeDistances(points)
        val formatter = timestampFormat
        val startMs = System.currentTimeMillis()
        val totalSeconds = (route.estimatedDurationMs / 1000L).coerceAtLeast(points.size.toLong())
        val totalDistance = cumulative.last()

        fun timeAt(index: Int): String {
            val ratio = if (totalDistance > 0) cumulative[index] / totalDistance else 0.0
            return formatter.format(Date(startMs + (ratio * totalSeconds * 1000).toLong()))
        }

        val sb = StringBuilder(points.size * 200 + 2048)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<TrainingCenterDatabase\n")
        sb.append("    xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\"\n")
        sb.append("    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        sb.append("    xsi:schemaLocation=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2 ")
        sb.append("http://www.garmin.com/xmlschemas/TrainingCenterDatabasev2.xsd\">\n")
        sb.append("  <Courses>\n    <Course>\n")
        // Le nom d'un Course TCX est limite a 15 caracteres par le schema.
        sb.append("      <Name>").append(escape(route.name.take(15))).append("</Name>\n")

        sb.append("      <Lap>\n")
        sb.append("        <TotalTimeSeconds>").append(totalSeconds).append("</TotalTimeSeconds>\n")
        sb.append("        <DistanceMeters>").append("%.1f".format(Locale.US, totalDistance))
            .append("</DistanceMeters>\n")
        sb.append("        <BeginPosition>\n")
        sb.append("          <LatitudeDegrees>").append(coord(points.first().lat)).append("</LatitudeDegrees>\n")
        sb.append("          <LongitudeDegrees>").append(coord(points.first().lon)).append("</LongitudeDegrees>\n")
        sb.append("        </BeginPosition>\n")
        sb.append("        <EndPosition>\n")
        sb.append("          <LatitudeDegrees>").append(coord(points.last().lat)).append("</LatitudeDegrees>\n")
        sb.append("          <LongitudeDegrees>").append(coord(points.last().lon)).append("</LongitudeDegrees>\n")
        sb.append("        </EndPosition>\n")
        sb.append("        <Intensity>Active</Intensity>\n")
        sb.append("      </Lap>\n")

        sb.append("      <Track>\n")
        for (i in points.indices) {
            val p = points[i]
            sb.append("        <Trackpoint>\n")
            sb.append("          <Time>").append(timeAt(i)).append("</Time>\n")
            sb.append("          <Position>\n")
            sb.append("            <LatitudeDegrees>").append(coord(p.lat)).append("</LatitudeDegrees>\n")
            sb.append("            <LongitudeDegrees>").append(coord(p.lon)).append("</LongitudeDegrees>\n")
            sb.append("          </Position>\n")
            p.ele?.let {
                sb.append("          <AltitudeMeters>").append("%.1f".format(Locale.US, it))
                    .append("</AltitudeMeters>\n")
            }
            sb.append("          <DistanceMeters>").append("%.1f".format(Locale.US, cumulative[i]))
                .append("</DistanceMeters>\n")
            sb.append("        </Trackpoint>\n")
        }
        sb.append("      </Track>\n")

        val instructions = route.instructions
            .map { instruction ->
                var index = cumulative.indexOfFirst { it >= instruction.distanceFromStartM }
                if (index < 0) index = points.lastIndex
                instruction to index
            }
            .distinctBy { it.second }
            .take(MAX_COURSE_POINTS)

        for ((instruction, index) in instructions) {
            val p = points[index]
            sb.append("      <CoursePoint>\n")
            sb.append("        <Name>").append(escape(instruction.type.shortLabel.take(10))).append("</Name>\n")
            sb.append("        <Time>").append(timeAt(index)).append("</Time>\n")
            sb.append("        <Position>\n")
            sb.append("          <LatitudeDegrees>").append(coord(p.lat)).append("</LatitudeDegrees>\n")
            sb.append("          <LongitudeDegrees>").append(coord(p.lon)).append("</LongitudeDegrees>\n")
            sb.append("        </Position>\n")
            sb.append("        <PointType>").append(pointType(instruction.type.name)).append("</PointType>\n")
            sb.append("      </CoursePoint>\n")
        }

        sb.append("    </Course>\n  </Courses>\n</TrainingCenterDatabase>\n")
        return sb.toString()
    }

    /** Valeurs autorisees par le schema TCX pour PointType. */
    private fun pointType(type: String): String = when (type) {
        "LEFT", "SHARP_LEFT", "SLIGHT_LEFT" -> "Left"
        "RIGHT", "SHARP_RIGHT", "SLIGHT_RIGHT" -> "Right"
        "CONTINUE" -> "Straight"
        "DEPART" -> "Generic"
        "ARRIVAL" -> "Generic"
        "U_TURN" -> "Left"
        else -> "Generic"
    }

    private fun coord(value: Double): String = "%.7f".format(Locale.US, value)

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
