package fr.velo.cadence.data.repo

import fr.velo.cadence.data.db.dao.RideDao
import fr.velo.cadence.data.db.entity.PersonalRecordEntity
import fr.velo.cadence.data.db.entity.RideEntity
import fr.velo.cadence.data.db.entity.RidePointEntity
import fr.velo.cadence.model.GeoPoint
import fr.velo.cadence.model.PolylineCodec
import fr.velo.cadence.model.Ride
import fr.velo.cadence.model.RidePoint
import fr.velo.cadence.stats.PersonalRecords
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RideRepository(private val dao: RideDao) {

    val rides: Flow<List<Ride>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    fun recent(limit: Int): Flow<List<Ride>> =
        dao.observeRecent(limit).map { list -> list.map { it.toModel() } }

    fun observe(id: Long): Flow<Ride?> = dao.observeById(id).map { it?.toModel() }

    fun since(from: Long): Flow<List<Ride>> =
        dao.observeSince(from).map { list -> list.map { it.toModel() } }

    val records: Flow<List<PersonalRecordEntity>> = dao.observeRecords()

    suspend fun get(id: Long): Ride? = dao.getById(id)?.toModel()

    suspend fun trace(rideId: Long): List<GeoPoint> =
        dao.getById(rideId)?.encodedTrace?.let { PolylineCodec.decode(it) } ?: emptyList()

    suspend fun points(rideId: Long): List<RidePoint> =
        dao.getPoints(rideId).map { it.toModel() }

    /**
     * Enregistre une sortie et sa trace, puis met a jour les records personnels.
     * La trace resumee est stockee dans la ligne de la sortie pour que la liste
     * de l'historique s'affiche sans lire la table des points.
     */
    suspend fun saveRide(ride: Ride, points: List<RidePoint>): Long {
        val geo = points.map { it.toGeoPoint() }
        val entity = ride.toEntity().copy(
            encodedTrace = PolylineCodec.encode(fr.velo.cadence.model.Geo.capPoints(geo, 800)),
        )
        val rideId = dao.insertRideWithPoints(
            entity,
            points.map { it.toEntity(0L) },
        )
        updateRecords(rideId, points)
        return rideId
    }

    suspend fun updateNote(rideId: Long, note: String) {
        dao.getById(rideId)?.let { dao.update(it.copy(note = note)) }
    }

    suspend fun updateTitle(rideId: Long, title: String) {
        dao.getById(rideId)?.let { dao.update(it.copy(title = title)) }
    }

    suspend fun delete(rideId: Long) {
        dao.deleteRecordsOfRide(rideId)
        dao.delete(rideId)
    }

    private suspend fun updateRecords(rideId: Long, points: List<RidePoint>) {
        val bests = PersonalRecords.bestEfforts(points)
        val now = System.currentTimeMillis()
        for ((distance, durationMs) in bests) {
            val existing = dao.getRecord(distance)
            if (existing == null || durationMs < existing.durationMs) {
                dao.upsertRecord(
                    PersonalRecordEntity(
                        id = existing?.id ?: 0L,
                        distanceM = distance,
                        durationMs = durationMs,
                        rideId = rideId,
                        achievedAt = now,
                    ),
                )
            }
        }
    }

    private fun RideEntity.toModel(): Ride = Ride(
        id = id,
        title = title,
        startedAt = startedAt,
        endedAt = endedAt,
        movingTimeMs = movingTimeMs,
        elapsedTimeMs = elapsedTimeMs,
        distanceM = distanceM,
        ascentM = ascentM,
        descentM = descentM,
        avgSpeedKmh = avgSpeedKmh,
        maxSpeedKmh = maxSpeedKmh,
        avgHeartRate = avgHeartRate,
        maxHeartRate = maxHeartRate,
        avgPower = avgPower,
        normalizedPower = normalizedPower,
        maxPower = maxPower,
        avgCadence = avgCadence,
        calories = calories,
        routeId = routeId,
        note = note,
    )

    private fun Ride.toEntity(): RideEntity = RideEntity(
        id = id,
        title = title,
        startedAt = startedAt,
        endedAt = endedAt,
        movingTimeMs = movingTimeMs,
        elapsedTimeMs = elapsedTimeMs,
        distanceM = distanceM,
        ascentM = ascentM,
        descentM = descentM,
        avgSpeedKmh = avgSpeedKmh,
        maxSpeedKmh = maxSpeedKmh,
        avgHeartRate = avgHeartRate,
        maxHeartRate = maxHeartRate,
        avgPower = avgPower,
        normalizedPower = normalizedPower,
        maxPower = maxPower,
        avgCadence = avgCadence,
        calories = calories,
        routeId = routeId,
        note = note,
    )

    private fun RidePointEntity.toModel(): RidePoint = RidePoint(
        timestamp = timestamp,
        lat = lat,
        lon = lon,
        ele = ele,
        speedMps = speedMps,
        heartRate = heartRate,
        power = power,
        cadence = cadence,
        accuracyM = accuracyM,
        moving = moving,
    )

    private fun RidePoint.toEntity(rideId: Long): RidePointEntity = RidePointEntity(
        rideId = rideId,
        timestamp = timestamp,
        lat = lat,
        lon = lon,
        ele = ele,
        speedMps = speedMps,
        heartRate = heartRate,
        power = power,
        cadence = cadence,
        accuracyM = accuracyM,
        moving = moving,
    )
}
