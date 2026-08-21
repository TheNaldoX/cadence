package fr.velo.cadence.sensors

import java.util.UUID

/**
 * Identifiants et decodeurs des profils Bluetooth standard du cyclisme.
 * Tous les capteurs du commerce (Garmin, Wahoo, Polar, Magene, iGPSPORT...)
 * exposent ces services, il n'y a donc rien de proprietaire a gerer.
 */
object SensorProfiles {

    private fun uuid(short: String): UUID =
        UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

    val CLIENT_CHARACTERISTIC_CONFIG: UUID = uuid("2902")

    /** Heart Rate Service / Heart Rate Measurement. */
    val HEART_RATE_SERVICE: UUID = uuid("180d")
    val HEART_RATE_MEASUREMENT: UUID = uuid("2a37")

    /** Cycling Power Service / Cycling Power Measurement. */
    val CYCLING_POWER_SERVICE: UUID = uuid("1818")
    val CYCLING_POWER_MEASUREMENT: UUID = uuid("2a63")

    /** Cycling Speed and Cadence Service / CSC Measurement. */
    val CSC_SERVICE: UUID = uuid("1816")
    val CSC_MEASUREMENT: UUID = uuid("2a5b")

    val BATTERY_SERVICE: UUID = uuid("180f")
    val BATTERY_LEVEL: UUID = uuid("2a19")

    /** Frequence cardiaque, en battements par minute. */
    fun parseHeartRate(data: ByteArray): Int? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        return if (flags and 0x01 == 0) {
            if (data.size < 2) null else data[1].toInt() and 0xFF
        } else {
            if (data.size < 3) null else u16(data, 1)
        }
    }

    /**
     * Mesure de puissance. Renvoie la puissance instantanee et, si le capteur
     * la transmet, les compteurs de tours de manivelle qui servent a calculer
     * la cadence.
     */
    data class PowerMeasurement(
        val watts: Int,
        val cumulativeCrankRevolutions: Int? = null,
        val lastCrankEventTime: Int? = null,
    )

    fun parseCyclingPower(data: ByteArray): PowerMeasurement? {
        if (data.size < 4) return null
        val flags = u16(data, 0)
        val watts = s16(data, 2)

        var offset = 4
        if (flags and 0x0001 != 0) offset += 1 // Pedal Power Balance
        if (flags and 0x0004 != 0) offset += 2 // Accumulated Torque
        if (flags and 0x0010 != 0) offset += 6 // Wheel Revolution Data

        var crankRevs: Int? = null
        var crankTime: Int? = null
        if (flags and 0x0020 != 0 && data.size >= offset + 4) {
            crankRevs = u16(data, offset)
            crankTime = u16(data, offset + 2)
        }
        return PowerMeasurement(watts, crankRevs, crankTime)
    }

    /** Mesure vitesse / cadence. Les temps d'evenement sont en 1/1024 s. */
    data class CscMeasurement(
        val cumulativeWheelRevolutions: Long? = null,
        val lastWheelEventTime: Int? = null,
        val cumulativeCrankRevolutions: Int? = null,
        val lastCrankEventTime: Int? = null,
    )

    fun parseCsc(data: ByteArray): CscMeasurement? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        var offset = 1
        var wheelRevs: Long? = null
        var wheelTime: Int? = null
        var crankRevs: Int? = null
        var crankTime: Int? = null

        if (flags and 0x01 != 0) {
            if (data.size < offset + 6) return null
            wheelRevs = u32(data, offset)
            wheelTime = u16(data, offset + 4)
            offset += 6
        }
        if (flags and 0x02 != 0) {
            if (data.size < offset + 4) return null
            crankRevs = u16(data, offset)
            crankTime = u16(data, offset + 2)
        }
        return CscMeasurement(wheelRevs, wheelTime, crankRevs, crankTime)
    }

    private fun u16(data: ByteArray, index: Int): Int =
        (data[index].toInt() and 0xFF) or ((data[index + 1].toInt() and 0xFF) shl 8)

    private fun s16(data: ByteArray, index: Int): Int {
        val value = u16(data, index)
        return if (value > 32767) value - 65536 else value
    }

    private fun u32(data: ByteArray, index: Int): Long =
        (data[index].toLong() and 0xFF) or
            ((data[index + 1].toLong() and 0xFF) shl 8) or
            ((data[index + 2].toLong() and 0xFF) shl 16) or
            ((data[index + 3].toLong() and 0xFF) shl 24)
}

/**
 * Convertit les compteurs cumulatifs BLE en cadence et en vitesse.
 * Les compteurs debordent a 65535 pour les manivelles et a 2^32 pour la roue,
 * le calcul de delta doit en tenir compte.
 */
class RevolutionTracker(private val wheelCircumferenceM: Double = 2.096) {

    private var lastCrankRevs: Int? = null
    private var lastCrankTime: Int? = null
    private var lastWheelRevs: Long? = null
    private var lastWheelTime: Int? = null

    private var cadenceRpm: Int = 0
    private var speedMps: Double = 0.0
    private var lastCrankUpdateMs: Long = 0L

    fun reset() {
        lastCrankRevs = null
        lastCrankTime = null
        lastWheelRevs = null
        lastWheelTime = null
        cadenceRpm = 0
        speedMps = 0.0
    }

    fun cadence(): Int {
        // Sans nouvel evenement pendant 3 s, le cycliste a cesse de pedaler.
        if (System.currentTimeMillis() - lastCrankUpdateMs > 3_000) return 0
        return cadenceRpm
    }

    fun speed(): Double = speedMps

    fun onCrank(revolutions: Int, eventTime: Int) {
        val previousRevs = lastCrankRevs
        val previousTime = lastCrankTime
        lastCrankRevs = revolutions
        lastCrankTime = eventTime
        if (previousRevs == null || previousTime == null) return

        val deltaRevs = ((revolutions - previousRevs) + 65536) % 65536
        val deltaTicks = ((eventTime - previousTime) + 65536) % 65536
        if (deltaRevs == 0 || deltaTicks == 0) return

        val seconds = deltaTicks / 1024.0
        cadenceRpm = (deltaRevs / seconds * 60.0).toInt().coerceIn(0, 220)
        lastCrankUpdateMs = System.currentTimeMillis()
    }

    fun onWheel(revolutions: Long, eventTime: Int) {
        val previousRevs = lastWheelRevs
        val previousTime = lastWheelTime
        lastWheelRevs = revolutions
        lastWheelTime = eventTime
        if (previousRevs == null || previousTime == null) return

        val deltaRevs = ((revolutions - previousRevs) + 4_294_967_296L) % 4_294_967_296L
        val deltaTicks = ((eventTime - previousTime) + 65536) % 65536
        if (deltaRevs == 0L || deltaTicks == 0) return

        val seconds = deltaTicks / 1024.0
        speedMps = (deltaRevs * wheelCircumferenceM / seconds).coerceIn(0.0, 30.0)
    }
}
