package fr.velo.cadence.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "rides")
data class RideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
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
    /** Trace complete encodee (voir PolylineCodec), pour un affichage rapide. */
    val encodedTrace: String = "",
)

@Entity(
    tableName = "ride_points",
    foreignKeys = [
        ForeignKey(
            entity = RideEntity::class,
            parentColumns = ["id"],
            childColumns = ["rideId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("rideId"), Index(value = ["rideId", "timestamp"])],
)
data class RidePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val rideId: Long,
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
)

@Entity(tableName = "routes", indices = [Index("createdAt"), Index("isFavorite")])
data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val encodedPoints: String,
    val distanceM: Double,
    val ascentM: Double,
    val descentM: Double,
    val estimatedDurationMs: Long,
    val createdAt: Long,
    val source: String,
    val isFavorite: Boolean,
    /** Consignes de navigation serialisees en JSON. */
    val instructionsJson: String = "[]",
    /** Repartition des revetements serialisee en JSON. */
    val surfaceJson: String = "{}",
)

/** Un record personnel sur une distance donnee (meilleur temps glissant). */
@Entity(tableName = "personal_records", indices = [Index("distanceM")])
data class PersonalRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val distanceM: Double,
    val durationMs: Long,
    val rideId: Long,
    val achievedAt: Long,
)
