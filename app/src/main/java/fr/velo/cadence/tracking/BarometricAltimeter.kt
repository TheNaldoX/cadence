package fr.velo.cadence.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Altimetre barometrique.
 *
 * L'altitude GPS derive de plusieurs metres en permanence, ce qui fausse le
 * denivele cumule : sur une sortie vallonnee l'erreur atteint couramment
 * 20 %. Le barometre du telephone, lui, est stable a court terme. On l'utilise
 * donc pour les variations et on le recale sur l'altitude GPS pour l'absolu.
 */
class BarometricAltimeter(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val pressureSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val isAvailable: Boolean get() = pressureSensor != null

    /** Altitude lissee issue du barometre, non recalee. */
    @Volatile
    private var rawAltitude: Double? = null

    /** Decalage entre le barometre et l'altitude GPS de reference. */
    @Volatile
    private var offset: Double = 0.0

    private var calibrationSamples = 0
    private var calibrationSum = 0.0

    fun start() {
        val sensor = pressureSensor ?: return
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        rawAltitude = null
        offset = 0.0
        calibrationSamples = 0
        calibrationSum = 0.0
    }

    /** Altitude corrigee, ou null si le barometre n'est pas disponible. */
    fun altitude(): Double? = rawAltitude?.let { it + offset }

    /**
     * Recalage sur une altitude GPS fiable. Les trente premieres mesures sont
     * moyennees pour absorber le bruit du GPS au demarrage.
     */
    fun calibrate(gpsAltitude: Double, gpsAccuracyM: Float?) {
        val raw = rawAltitude ?: return
        if (gpsAccuracyM != null && gpsAccuracyM > 15f) return
        if (calibrationSamples < 30) {
            calibrationSum += gpsAltitude - raw
            calibrationSamples++
            offset = calibrationSum / calibrationSamples
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_PRESSURE) return
        val hPa = event.values.firstOrNull() ?: return
        val altitude = SensorManager.getAltitude(
            SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
            hPa,
        ).toDouble()
        // Lissage exponentiel : le capteur bruite de quelques dizaines de
        // centimetres d'une mesure a l'autre.
        val previous = rawAltitude
        rawAltitude = if (previous == null) altitude else previous * 0.85 + altitude * 0.15
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
