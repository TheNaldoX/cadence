package fr.velo.cadence.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import fr.velo.cadence.data.db.entity.PersonalRecordEntity
import fr.velo.cadence.data.db.entity.RideEntity
import fr.velo.cadence.data.db.entity.RidePointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RideDao {

    @Query("SELECT * FROM rides ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<RideEntity>>

    @Query("SELECT * FROM rides ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RideEntity>>

    @Query("SELECT * FROM rides WHERE id = :id")
    fun observeById(id: Long): Flow<RideEntity?>

    @Query("SELECT * FROM rides WHERE id = :id")
    suspend fun getById(id: Long): RideEntity?

    @Query("SELECT * FROM rides WHERE startedAt >= :from AND startedAt < :to ORDER BY startedAt DESC")
    suspend fun getBetween(from: Long, to: Long): List<RideEntity>

    @Query("SELECT * FROM rides WHERE startedAt >= :from ORDER BY startedAt ASC")
    fun observeSince(from: Long): Flow<List<RideEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(ride: RideEntity): Long

    @Update
    suspend fun update(ride: RideEntity)

    @Query("DELETE FROM rides WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<RidePointEntity>)

    @Query("SELECT * FROM ride_points WHERE rideId = :rideId ORDER BY timestamp ASC")
    suspend fun getPoints(rideId: Long): List<RidePointEntity>

    @Query("SELECT COUNT(*) FROM ride_points WHERE rideId = :rideId")
    suspend fun countPoints(rideId: Long): Int

    @Transaction
    suspend fun insertRideWithPoints(ride: RideEntity, points: List<RidePointEntity>): Long {
        val id = insert(ride)
        if (points.isNotEmpty()) {
            points.chunked(500).forEach { chunk ->
                insertPoints(chunk.map { it.copy(rideId = id) })
            }
        }
        return id
    }

    @Query("SELECT * FROM personal_records ORDER BY distanceM ASC")
    fun observeRecords(): Flow<List<PersonalRecordEntity>>

    @Query("SELECT * FROM personal_records WHERE distanceM = :distanceM LIMIT 1")
    suspend fun getRecord(distanceM: Double): PersonalRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecord(record: PersonalRecordEntity)

    @Query("DELETE FROM personal_records WHERE rideId = :rideId")
    suspend fun deleteRecordsOfRide(rideId: Long)
}
