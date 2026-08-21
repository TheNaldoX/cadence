package fr.velo.cadence.ui.components

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object Format {

    private val FR = Locale.FRANCE

    /** "42,7 km" ou "850 m" selon l'ordre de grandeur. */
    fun distance(meters: Double, forceKm: Boolean = false): String = when {
        forceKm || meters >= 1000 -> "%.1f km".format(FR, meters / 1000.0)
        else -> "${meters.roundToInt()} m"
    }

    fun distanceKm(meters: Double): String = "%.1f".format(FR, meters / 1000.0)

    /** "2 h 14" pour les durees longues, "14:32" pour les courtes. */
    fun duration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d h %02d".format(FR, hours, minutes)
        } else {
            "%02d:%02d".format(FR, minutes, seconds)
        }
    }

    /** Chronometre complet, utilise pendant l'enregistrement. */
    fun timer(millis: Long): String {
        val totalSeconds = millis / 1000
        return "%d:%02d:%02d".format(
            FR,
            totalSeconds / 3600,
            (totalSeconds % 3600) / 60,
            totalSeconds % 60,
        )
    }

    fun speed(kmh: Double): String = "%.1f".format(FR, kmh)

    fun elevation(meters: Double): String = "${meters.roundToInt()} m"

    fun gradient(percent: Double): String = "%+.1f %%".format(FR, percent)

    fun integer(value: Int): String = value.toString()

    private val dayFormat = SimpleDateFormat("EEEE d MMMM", FR)
    private val shortFormat = SimpleDateFormat("d MMM", FR)
    private val timeFormat = SimpleDateFormat("HH:mm", FR)

    fun dayAndTime(timestamp: Long): String {
        val date = Date(timestamp)
        return "${dayFormat.format(date).replaceFirstChar { it.uppercase() }} à ${timeFormat.format(date)}"
    }

    fun shortDate(timestamp: Long): String = shortFormat.format(Date(timestamp))

    /** "il y a 2 jours", plus lisible qu'une date dans une liste recente. */
    fun relative(timestamp: Long): String {
        val delta = System.currentTimeMillis() - timestamp
        val days = delta / 86_400_000
        return when {
            delta < 3_600_000 -> "il y a ${(delta / 60_000).coerceAtLeast(1)} min"
            days < 1 -> "il y a ${delta / 3_600_000} h"
            days < 2 -> "hier"
            days < 7 -> "il y a $days jours"
            else -> shortDate(timestamp)
        }
    }
}
