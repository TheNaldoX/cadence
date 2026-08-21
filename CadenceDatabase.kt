package fr.velo.cadence.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import fr.velo.cadence.data.db.dao.RideDao
import fr.velo.cadence.data.db.dao.RouteDao
import fr.velo.cadence.data.db.entity.PersonalRecordEntity
import fr.velo.cadence.data.db.entity.RideEntity
import fr.velo.cadence.data.db.entity.RidePointEntity
import fr.velo.cadence.data.db.entity.RouteEntity

@Database(
    entities = [
        RideEntity::class,
        RidePointEntity::class,
        RouteEntity::class,
        PersonalRecordEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class CadenceDatabase : RoomDatabase() {

    abstract fun rideDao(): RideDao
    abstract fun routeDao(): RouteDao

    companion object {
        @Volatile
        private var instance: CadenceDatabase? = null

        fun get(context: Context): CadenceDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CadenceDatabase::class.java,
                    "cadence.db",
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
