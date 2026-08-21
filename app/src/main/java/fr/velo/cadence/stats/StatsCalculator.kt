package fr.velo.cadence.stats

import fr.velo.cadence.model.Ride
import fr.velo.cadence.model.RiderLevel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt

data class PeriodSummary(
    val label: String,
    val rideCount: Int,
    val distanceM: Double,
    val ascentM: Double,
    val movingTimeMs: Long,
    val avgSpeedKmh: Double,
) {
    val distanceKm: Double get() = distanceM / 1000.0
}

data class TrainingLoad(
    /** Charge aigue : moyenne exponentielle sur 7 jours. */
    val acute: Double,
    /** Charge chronique : moyenne exponentielle sur 42 jours. */
    val chronic: Double,
) {
    /** Rapport charge aigue / chronique. Au-dela de 1,5 le risque de surcharge augmente. */
    val ratio: Double get() = if (chronic < 1.0) 0.0 else acute / chronic

    val advice: String
        get() = when {
            chronic < 1.0 -> "Pas encore assez d'historique"
            ratio < 0.8 -> "Charge en baisse, tu es frais"
            ratio <= 1.3 -> "Charge équilibrée, continue comme ça"
            ratio <= 1.5 -> "Charge élevée, surveille la fatigue"
            else -> "Charge très élevée, pense à récupérer"
        }
}

object StatsCalculator {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun summarize(label: String, rides: List<Ride>): PeriodSummary {
        val distance = rides.sumOf { it.distanceM }
        val moving = rides.sumOf { it.movingTimeMs }
        val hours = moving / 3_600_000.0
        return PeriodSummary(
            label = label,
            rideCount = rides.size,
            distanceM = distance,
            ascentM = rides.sumOf { it.ascentM },
            movingTimeMs = moving,
            avgSpeedKmh = if (hours > 0.01) (distance / 1000.0) / hours else 0.0,
        )
    }

    /** Regroupe les sorties par semaine ISO, des plus anciennes aux plus recentes. */
    fun weekly(rides: List<Ride>, weeks: Int = 12): List<PeriodSummary> {
        val today = LocalDate.now(zone)
        val weekFields = WeekFields.of(Locale.FRANCE)
        val buckets = LinkedHashMap<LocalDate, MutableList<Ride>>()
        for (i in (weeks - 1) downTo 0) {
            val monday = today.minusWeeks(i.toLong())
                .with(weekFields.dayOfWeek(), 1L)
            buckets[monday] = mutableListOf()
        }
        for (ride in rides) {
            val date = Instant.ofEpochMilli(ride.startedAt).atZone(zone).toLocalDate()
            val monday = date.with(weekFields.dayOfWeek(), 1L)
            buckets[monday]?.add(ride)
        }
        return buckets.map { (monday, list) ->
            summarize("${monday.dayOfMonth}/${monday.monthValue}", list)
        }
    }

    fun monthly(rides: List<Ride>, months: Int = 12): List<PeriodSummary> {
        val today = LocalDate.now(zone).withDayOfMonth(1)
        val buckets = LinkedHashMap<LocalDate, MutableList<Ride>>()
        for (i in (months - 1) downTo 0) {
            buckets[today.minusMonths(i.toLong())] = mutableListOf()
        }
        for (ride in rides) {
            val date = Instant.ofEpochMilli(ride.startedAt).atZone(zone).toLocalDate().withDayOfMonth(1)
            buckets[date]?.add(ride)
        }
        val names = listOf("Jan", "Fév", "Mar", "Avr", "Mai", "Juin", "Juil", "Aoû", "Sep", "Oct", "Nov", "Déc")
        return buckets.map { (month, list) -> summarize(names[month.monthValue - 1], list) }
    }

    fun currentWeek(rides: List<Ride>): PeriodSummary {
        val monday = LocalDate.now(zone).with(DayOfWeek.MONDAY)
        val from = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        return summarize("Cette semaine", rides.filter { it.startedAt >= from })
    }

    /**
     * Charge d'entrainement facon TSS simplifie : a defaut de capteur de
     * puissance, l'effort d'une sortie est estime a partir de sa duree et de
     * son denivele, ce qui suffit a suivre une tendance.
     */
    fun trainingLoad(rides: List<Ride>): TrainingLoad {
        if (rides.isEmpty()) return TrainingLoad(0.0, 0.0)
        val today = LocalDate.now(zone)
        val daily = DoubleArray(60)
        for (ride in rides) {
            val date = Instant.ofEpochMilli(ride.startedAt).atZone(zone).toLocalDate()
            val ago = java.time.temporal.ChronoUnit.DAYS.between(date, today).toInt()
            if (ago in 0 until 60) daily[ago] += effortScore(ride)
        }
        var acute = 0.0
        var chronic = 0.0
        val kAcute = 2.0 / (7 + 1)
        val kChronic = 2.0 / (42 + 1)
        for (i in 59 downTo 0) {
            acute = daily[i] * kAcute + acute * (1 - kAcute)
            chronic = daily[i] * kChronic + chronic * (1 - kChronic)
        }
        return TrainingLoad(acute, chronic)
    }

    private fun effortScore(ride: Ride): Double {
        val hours = ride.movingTimeMs / 3_600_000.0
        val climbFactor = 1.0 + (ride.ascentPerKm / 100.0)
        return hours * 55.0 * climbFactor
    }

    /**
     * Estimation du niveau a partir de l'historique : volume hebdomadaire moyen,
     * vitesse moyenne et denivele avale. Sert a pre-remplir le profil et a
     * calibrer les recommandations sans que l'utilisateur ait a s'auto-evaluer.
     */
    fun estimateLevel(rides: List<Ride>): RiderLevel? {
        val relevant = rides.filter { it.distanceM > 5_000 }
        if (relevant.size < 3) return null

        val oldest = relevant.minOf { it.startedAt }
        val weeksSpan = ((System.currentTimeMillis() - oldest) / (7.0 * 86_400_000.0))
            .coerceAtLeast(1.0)
            .coerceAtMost(16.0)
        val weeklyKm = relevant.sumOf { it.distanceM } / 1000.0 / weeksSpan

        val totalMovingHours = relevant.sumOf { it.movingTimeMs } / 3_600_000.0
        val avgSpeed = if (totalMovingHours > 0.1) {
            (relevant.sumOf { it.distanceM } / 1000.0) / totalMovingHours
        } else {
            0.0
        }
        val avgAscentPerKm = relevant.sumOf { it.ascentM } /
            (relevant.sumOf { it.distanceM } / 1000.0).coerceAtLeast(1.0)
        val longestKm = relevant.maxOf { it.distanceM } / 1000.0

        // Chaque indicateur donne un score de 0 a 4, la moyenne ponderee decide.
        val volumeScore = when {
            weeklyKm < 60 -> 0.0
            weeklyKm < 120 -> 1.0
            weeklyKm < 200 -> 2.0
            weeklyKm < 300 -> 3.0
            else -> 4.0
        }
        val speedScore = when {
            avgSpeed < 20 -> 0.0
            avgSpeed < 24 -> 1.0
            avgSpeed < 27 -> 2.0
            avgSpeed < 30 -> 3.0
            else -> 4.0
        }
        val climbScore = when {
            avgAscentPerKm < 5 -> 0.0
            avgAscentPerKm < 9 -> 1.0
            avgAscentPerKm < 13 -> 2.0
            avgAscentPerKm < 17 -> 3.0
            else -> 4.0
        }
        val enduranceScore = when {
            longestKm < 40 -> 0.0
            longestKm < 80 -> 1.0
            longestKm < 120 -> 2.0
            longestKm < 160 -> 3.0
            else -> 4.0
        }

        val weighted = speedScore * 0.35 + volumeScore * 0.3 +
            enduranceScore * 0.2 + climbScore * 0.15
        val index = weighted.roundToInt().coerceIn(0, 4)
        return RiderLevel.entries[index]
    }
}
