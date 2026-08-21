package fr.velo.cadence.tracking

import android.location.Location
import fr.velo.cadence.model.GeoPoint
import fr.velo.cadence.model.LiveMetrics
import fr.velo.cadence.model.RecordingState
import fr.velo.cadence.model.Ride
import fr.velo.cadence.model.RidePoint
import fr.velo.cadence.model.RiderProfile
import fr.velo.cadence.routing.RideEstimator
import fr.velo.cadence.sensors.SensorReadings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Resultat d'un enregistrement termine. */
data class RecordedRide(
    val ride: Ride,
    val points: List<RidePoint>,
)

/**
 * Machine d'enregistrement d'une sortie.
 *
 * Elle est volontairement independante du service Android : le service se
 * contente de lui pousser des positions et de relayer son etat dans une
 * notification. L'interface, elle, observe directement [metrics], ce qui evite
 * de perdre l'affichage quand le service est relie a nouveau.
 */
class RideRecorder {

    private val _metrics = MutableStateFlow(LiveMetrics())
    val metrics: StateFlow<LiveMetrics> = _metrics.asStateFlow()

    private val filter = GpsFilter()
    private val autoPause = AutoPauseDetector()

    private val points = ArrayList<RidePoint>(8_000)
    private val powerSamples = ArrayList<Int>(8_000)
    private val heartRateSamples = ArrayList<Int>(8_000)
    private val cadenceSamples = ArrayList<Int>(8_000)
    private val gradientWindow = ArrayDeque<Pair<Double, Double>>()

    private var profile: RiderProfile = RiderProfile()
    private var autoPauseEnabled = true
    private var routeId: Long? = null

    private var startedAt = 0L
    private var lastTickAt = 0L
    private var distanceM = 0.0
    private var movingTimeMs = 0L
    private var maxSpeedKmh = 0.0
    private var ascentM = 0.0
    private var descentM = 0.0
    private var referenceElevation: Double? = null
    private var sensors = SensorReadings()
    private var altimeter: BarometricAltimeter? = null
    private var useBarometer = true
    private val trace = ArrayList<GeoPoint>(8_000)

    val isActive: Boolean
        get() = _metrics.value.state != RecordingState.IDLE

    fun attachAltimeter(altimeter: BarometricAltimeter?) {
        this.altimeter = altimeter
    }

    fun start(profile: RiderProfile, autoPauseEnabled: Boolean, useBarometer: Boolean, routeId: Long?) {
        reset()
        this.profile = profile
        this.autoPauseEnabled = autoPauseEnabled
        this.useBarometer = useBarometer
        this.routeId = routeId
        startedAt = System.currentTimeMillis()
        lastTickAt = startedAt
        _metrics.value = LiveMetrics(state = RecordingState.RECORDING)
    }

    fun pause() {
        if (_metrics.value.state == RecordingState.RECORDING ||
            _metrics.value.state == RecordingState.AUTO_PAUSED
        ) {
            _metrics.value = _metrics.value.copy(state = RecordingState.PAUSED, speedKmh = 0.0)
        }
    }

    fun resume() {
        if (_metrics.value.state == RecordingState.PAUSED ||
            _metrics.value.state == RecordingState.AUTO_PAUSED
        ) {
            lastTickAt = System.currentTimeMillis()
            autoPause.reset()
            filter.reset()
            _metrics.value = _metrics.value.copy(state = RecordingState.RECORDING)
        }
    }

    fun updateSensors(readings: SensorReadings) {
        sensors = readings
        if (!isActive) return
        _metrics.value = _metrics.value.copy(
            heartRate = readings.heartRate,
            power = readings.power ?: _metrics.value.power,
            cadence = readings.cadence,
        )
    }

    /** Mise a jour du temps ecoule, appelee chaque seconde par le service. */
    fun tick() {
        if (!isActive) return
        val now = System.currentTimeMillis()
        _metrics.value = _metrics.value.copy(elapsedTimeMs = now - startedAt)
    }

    fun onLocation(location: Location) {
        val state = _metrics.value.state
        if (state == RecordingState.IDLE || state == RecordingState.PAUSED) return

        // Recalage du barometre tant que le GPS donne une altitude fiable.
        if (useBarometer && location.hasAltitude()) {
            altimeter?.calibrate(
                location.altitude,
                if (location.hasAccuracy()) location.accuracy else null,
            )
        }

        val result = filter.accept(location)
        val now = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        val deviceSpeed = if (location.hasSpeed()) location.speed.toDouble() else result.speedMps
        val speedMps = sensors.wheelSpeedMps ?: deviceSpeed

        // Pause automatique : on l'evalue meme sur un point rejete, sinon un
        // arret prolonge ne serait jamais detecte.
        if (autoPauseEnabled) {
            if (state == RecordingState.RECORDING && autoPause.shouldPause(speedMps, now)) {
                _metrics.value = _metrics.value.copy(state = RecordingState.AUTO_PAUSED, speedKmh = 0.0)
                return
            }
            if (state == RecordingState.AUTO_PAUSED) {
                if (!autoPause.shouldResume(speedMps)) return
                autoPause.reset()
                lastTickAt = now
                _metrics.value = _metrics.value.copy(state = RecordingState.RECORDING)
            }
        }

        if (!result.accepted) {
            _metrics.value = _metrics.value.copy(
                gpsAccuracyM = if (location.hasAccuracy()) location.accuracy else null,
            )
            return
        }

        distanceM += result.distanceM
        val deltaMs = (now - lastTickAt).coerceIn(0L, 15_000L)
        if (speedMps > 0.5) movingTimeMs += deltaMs
        lastTickAt = now

        val elevation = altimeter?.altitude()?.takeIf { useBarometer }
            ?: location.altitude.takeIf { location.hasAltitude() }
        updateElevation(elevation)

        val speedKmh = speedMps * 3.6
        if (speedKmh > maxSpeedKmh && speedKmh < 110.0) maxSpeedKmh = speedKmh

        val gradient = updateGradient(distanceM, elevation)
        val estimatedPower = sensors.power ?: RideEstimator.estimatePower(
            speedMps = speedMps,
            grade = gradient / 100.0,
            massKg = profile.totalMassKg,
            cda = profile.level.cda,
        )

        val point = RidePoint(
            timestamp = now,
            lat = location.latitude,
            lon = location.longitude,
            ele = elevation,
            speedMps = speedMps.toFloat(),
            heartRate = sensors.heartRate,
            power = estimatedPower,
            cadence = sensors.cadence,
            accuracyM = if (location.hasAccuracy()) location.accuracy else null,
            moving = speedMps > 0.5,
        )
        points.add(point)
        trace.add(GeoPoint(location.latitude, location.longitude, elevation))
        powerSamples.add(estimatedPower)
        sensors.heartRate?.let { heartRateSamples.add(it) }
        sensors.cadence?.let { cadenceSamples.add(it) }

        val movingHours = movingTimeMs / 3_600_000.0
        val avgPower = if (powerSamples.isEmpty()) 0.0 else powerSamples.average()

        _metrics.value = _metrics.value.copy(
            distanceM = distanceM,
            movingTimeMs = movingTimeMs,
            elapsedTimeMs = now - startedAt,
            speedKmh = speedKmh,
            avgSpeedKmh = if (movingHours > 0.0003) (distanceM / 1000.0) / movingHours else 0.0,
            maxSpeedKmh = maxSpeedKmh,
            ascentM = ascentM,
            descentM = descentM,
            gradientPercent = gradient,
            altitudeM = elevation,
            heartRate = sensors.heartRate,
            power = estimatedPower,
            cadence = sensors.cadence,
            calories = RideEstimator.estimateCalories(movingTimeMs, avgPower),
            gpsAccuracyM = if (location.hasAccuracy()) location.accuracy else null,
            lastPoint = GeoPoint(location.latitude, location.longitude, elevation),
            // La trace affichee est allegee : tracer 20 000 points sur la carte
            // a chaque mise a jour ferait chuter la fluidite.
            trace = if (trace.size <= 1500) trace.toList() else trace.filterIndexed { i, _ ->
                i % (trace.size / 1000 + 1) == 0 || i == trace.lastIndex
            },
        )
    }

    private fun updateElevation(elevation: Double?) {
        if (elevation == null) return
        val reference = referenceElevation
        if (reference == null) {
            referenceElevation = elevation
            return
        }
        // Seuil plus serre avec un barometre, qui derive beaucoup moins que le GPS.
        val threshold = if (useBarometer && altimeter?.isAvailable == true) 1.5 else 4.0
        val delta = elevation - reference
        if (abs(delta) >= threshold) {
            if (delta > 0) ascentM += delta else descentM -= delta
            referenceElevation = elevation
        }
    }

    /** Pente moyenne, en pourcentage, sur les 60 derniers metres parcourus. */
    private fun updateGradient(cumulativeDistance: Double, elevation: Double?): Double {
        if (elevation == null) return _metrics.value.gradientPercent
        gradientWindow.addLast(cumulativeDistance to elevation)
        while (gradientWindow.size > 2 &&
            cumulativeDistance - gradientWindow.first().first > 60.0
        ) {
            gradientWindow.removeFirst()
        }
        val first = gradientWindow.firstOrNull() ?: return 0.0
        val run = cumulativeDistance - first.first
        if (run < 15.0) return _metrics.value.gradientPercent
        return ((elevation - first.second) / run * 100.0).coerceIn(-30.0, 30.0)
    }

    /** Termine l'enregistrement et renvoie la sortie prete a etre sauvegardee. */
    fun stop(): RecordedRide? {
        if (!isActive) return null
        val now = System.currentTimeMillis()
        val collected = points.toList()
        val state = _metrics.value

        val ride = Ride(
            title = defaultTitle(startedAt),
            startedAt = startedAt,
            endedAt = now,
            movingTimeMs = movingTimeMs,
            elapsedTimeMs = now - startedAt,
            distanceM = distanceM,
            ascentM = ascentM,
            descentM = descentM,
            avgSpeedKmh = state.avgSpeedKmh,
            maxSpeedKmh = maxSpeedKmh,
            avgHeartRate = heartRateSamples.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            maxHeartRate = heartRateSamples.maxOrNull(),
            avgPower = powerSamples.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            normalizedPower = RideEstimator.normalizedPower(powerSamples).takeIf { it > 0 },
            maxPower = powerSamples.maxOrNull(),
            avgCadence = cadenceSamples.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            calories = state.calories,
            routeId = routeId,
        )
        reset()
        _metrics.value = LiveMetrics()
        return RecordedRide(ride, collected)
    }

    fun discard() {
        reset()
        _metrics.value = LiveMetrics()
    }

    private fun reset() {
        points.clear()
        trace.clear()
        powerSamples.clear()
        heartRateSamples.clear()
        cadenceSamples.clear()
        gradientWindow.clear()
        filter.reset()
        autoPause.reset()
        distanceM = 0.0
        movingTimeMs = 0L
        maxSpeedKmh = 0.0
        ascentM = 0.0
        descentM = 0.0
        referenceElevation = null
        routeId = null
    }

    private fun defaultTitle(timestamp: Long): String {
        val hour = SimpleDateFormat("HH", Locale.FRANCE).format(Date(timestamp)).toInt()
        val moment = when (hour) {
            in 5..11 -> "Sortie du matin"
            in 12..14 -> "Sortie du midi"
            in 15..18 -> "Sortie de l'après-midi"
            in 19..22 -> "Sortie du soir"
            else -> "Sortie de nuit"
        }
        return moment
    }
}
