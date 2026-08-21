package fr.velo.cadence.data.repo

import fr.velo.cadence.data.db.dao.RouteDao
import fr.velo.cadence.data.db.entity.RouteEntity
import fr.velo.cadence.model.PlannedRoute
import fr.velo.cadence.model.PolylineCodec
import fr.velo.cadence.model.RouteSource
import fr.velo.cadence.model.SurfaceBreakdown
import fr.velo.cadence.model.TurnInstruction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RouteRepository(private val dao: RouteDao) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val routes: Flow<List<PlannedRoute>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    fun observe(id: Long): Flow<PlannedRoute?> = dao.observeById(id).map { it?.toModel() }

    suspend fun get(id: Long): PlannedRoute? = dao.getById(id)?.toModel()

    suspend fun save(route: PlannedRoute): Long = dao.insert(route.toEntity())

    suspend fun update(route: PlannedRoute) = dao.update(route.toEntity())

    suspend fun setFavorite(id: Long, favorite: Boolean) = dao.setFavorite(id, favorite)

    suspend fun delete(id: Long) = dao.delete(id)

    private fun RouteEntity.toModel(): PlannedRoute = PlannedRoute(
        id = id,
        name = name,
        points = PolylineCodec.decode(encodedPoints),
        distanceM = distanceM,
        ascentM = ascentM,
        descentM = descentM,
        instructions = runCatching {
            json.decodeFromString<List<TurnInstruction>>(instructionsJson)
        }.getOrDefault(emptyList()),
        surface = runCatching {
            json.decodeFromString<SurfaceBreakdown>(surfaceJson)
        }.getOrDefault(SurfaceBreakdown()),
        estimatedDurationMs = estimatedDurationMs,
        createdAt = createdAt,
        source = runCatching { RouteSource.valueOf(source) }.getOrDefault(RouteSource.GENERATED),
        isFavorite = isFavorite,
    )

    private fun PlannedRoute.toEntity(): RouteEntity = RouteEntity(
        id = id,
        name = name,
        encodedPoints = PolylineCodec.encode(points),
        distanceM = distanceM,
        ascentM = ascentM,
        descentM = descentM,
        estimatedDurationMs = estimatedDurationMs,
        createdAt = createdAt,
        source = source.name,
        isFavorite = isFavorite,
        instructionsJson = json.encodeToString(instructions),
        surfaceJson = json.encodeToString(surface),
    )
}
