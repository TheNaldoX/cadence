package fr.velo.cadence.tracking

import android.location.Location
import fr.velo.cadence.model.Geo

/**
 * Filtrage des positions GPS.
 *
 * Un recepteur de telephone produit reguliererement des points aberrants :
 * precision degradee sous les arbres, saut de plusieurs dizaines de metres a
 * l'arret, vitesse impossible. Sans filtrage la distance d'une sortie de
 * 100 km est surestimee de plusieurs kilometres.
 */
class GpsFilter(
    private val maxAccuracyM: Float = 30f,
    private val maxSpeedMps: Double = 30.0,
    private val minMoveM: Double = 2.0,
) {

    private var lastAccepted: Location? = null

    data class Result(
        val accepted: Boolean,
        val distanceM: Double = 0.0,
        val speedMps: Double = 0.0,
        val reason: String? = null,
    )

    fun reset() {
        lastAccepted = null
    }

    fun accept(location: Location): Result {
        if (location.hasAccuracy() && location.accuracy > maxAccuracyM) {
            return Result(false, reason = "précision ${location.accuracy.toInt()} m")
        }
        val previous = lastAccepted
        if (previous == null) {
            lastAccepted = location
            return Result(true)
        }

        val dt = (location.time - previous.time) / 1000.0
        if (dt <= 0.0) return Result(false, reason = "horodatage non croissant")

        val distance = Geo.distance(
            previous.latitude, previous.longitude,
            location.latitude, location.longitude,
        )
        val speed = distance / dt

        // Un saut plus rapide que possible a velo est un artefact, pas un deplacement.
        if (speed > maxSpeedMps) {
            return Result(false, reason = "saut de ${distance.toInt()} m en ${dt.toInt()} s")
        }
        // Sous le seuil de mouvement on ignore le point : c'est du bruit a l'arret.
        if (distance < minMoveM) {
            return Result(false, distanceM = 0.0, speedMps = 0.0, reason = "immobile")
        }

        lastAccepted = location
        return Result(true, distance, speed)
    }
}

/**
 * Detection d'arret automatique. Le compteur se met en pause quand le velo
 * s'immobilise (feu rouge, ravitaillement) et repart des que la vitesse
 * remonte, sans intervention du cycliste.
 */
class AutoPauseDetector(
    private val stopSpeedMps: Double = 1.0,
    private val resumeSpeedMps: Double = 2.2,
    private val stopDelayMs: Long = 8_000L,
) {

    private var belowSince: Long? = null

    fun reset() {
        belowSince = null
    }

    /** @return true s'il faut passer en pause automatique. */
    fun shouldPause(speedMps: Double, nowMs: Long): Boolean {
        if (speedMps > stopSpeedMps) {
            belowSince = null
            return false
        }
        val since = belowSince ?: nowMs.also { belowSince = it }
        return nowMs - since >= stopDelayMs
    }

    fun shouldResume(speedMps: Double): Boolean = speedMps >= resumeSpeedMps
}
