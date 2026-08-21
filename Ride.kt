package fr.velo.cadence.model

/** Une sortie enregistree, sans sa trace detaillee. */
data class Ride(
    val id: Long = 0L,
    val title: String,
    val startedAt: Long,
    val endedAt: Long,
    val movingTimeMs: Long,
    val elapsedTimeMs: Long,
    val distanceM: Double,
    val ascentM: Double,
    val descentM: Double,
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val avgPower: Int?,
    val normalizedPower: Int?,
    val maxPower: Int?,
    val avgCadence: Int?,
    val calories: Int?,
    val routeId: Long?,
    val note: String = "",
) {
    val ascentPerKm: Double
        get() = if (distanceM > 100) ascentM / (distanceM / 1000.0) else 0.0
}

/** Un point de la trace enregistree. */
data class RidePoint(
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    val speedMps: Float?,
    val heartRate: Int?,
    val power: Int?,
    val cadence: Int?,
    val accuracyM: Float?,
    val moving: Boolean,
) {
    fun toGeoPoint(): GeoPoint = GeoPoint(lat, lon, ele)
}

/** Etat de la machine d'enregistrement. */
enum class RecordingState { IDLE, RECORDING, PAUSED, AUTO_PAUSED }

/** Instantane des metriques affichees pendant la sortie. */
data class LiveMetrics(
    val state: RecordingState = RecordingState.IDLE,
    val distanceM: Double = 0.0,
    val movingTimeMs: Long = 0L,
    val elapsedTimeMs: Long = 0L,
    val speedKmh: Double = 0.0,
    val avgSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val ascentM: Double = 0.0,
    val descentM: Double = 0.0,
    val gradientPercent: Double = 0.0,
    val altitudeM: Double? = null,
    val heartRate: Int? = null,
    val power: Int? = null,
    val cadence: Int? = null,
    val calories: Int = 0,
    val gpsAccuracyM: Float? = null,
    val lastPoint: GeoPoint? = null,
    val trace: List<GeoPoint> = emptyList(),
)
