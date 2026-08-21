package fr.velo.cadence.ui.plan

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.velo.cadence.CadenceApp
import fr.velo.cadence.di.AppContainer
import fr.velo.cadence.export.GpxParser
import fr.velo.cadence.model.GeoPoint
import fr.velo.cadence.model.PlannedRoute
import fr.velo.cadence.model.RoadStyle
import fr.velo.cadence.model.RouteCandidate
import fr.velo.cadence.model.RouteRequest
import fr.velo.cadence.model.RouteSource
import fr.velo.cadence.model.TerrainPreference
import fr.velo.cadence.net.Place
import fr.velo.cadence.routing.RideEstimator
import fr.velo.cadence.routing.RouteGenerator
import fr.velo.cadence.tracking.CurrentLocation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Deux facons de composer une sortie. */
enum class PlanMode(val label: String) {
    AUTOMATIQUE("Boucle proposée"),
    MANUEL("Tracé manuel"),
}

data class PlanUiState(
    val mode: PlanMode = PlanMode.AUTOMATIQUE,
    /** Etapes posees sur la carte, dans l'ordre de passage. */
    val waypoints: List<GeoPoint> = emptyList(),
    val manualLoop: Boolean = true,
    val manualRoute: PlannedRoute? = null,
    val computing: Boolean = false,
    val start: GeoPoint? = null,
    val startLabel: String = "",
    val targetDistanceKm: Float = 50f,
    val terrain: TerrainPreference = TerrainPreference.INDIFFERENT,
    val roadStyle: RoadStyle = RoadStyle.TRANQUILLE,
    val candidates: List<RouteCandidate> = emptyList(),
    val loading: Boolean = false,
    val progress: RouteGenerator.Progress? = null,
    val error: String? = null,
    val searchResults: List<Place> = emptyList(),
    val searching: Boolean = false,
    val locating: Boolean = false,
)

class PlanViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(PlanUiState())
    val state: StateFlow<PlanUiState> = _state.asStateFlow()

    val savedRoutes: StateFlow<List<PlannedRoute>> = container.routeRepository.routes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var generationJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            val profile = container.userPreferences.profile.first()
            _state.update { it.copy(roadStyle = profile.preferredRoadStyle) }
            locate()
        }
    }

    fun locate() {
        viewModelScope.launch {
            _state.update { it.copy(locating = true) }
            val point = CurrentLocation.get(getApplication())
            if (point == null) {
                _state.update {
                    it.copy(
                        locating = false,
                        error = "Position indisponible. Active la localisation ou " +
                            "choisis un point de départ sur la carte.",
                    )
                }
                return@launch
            }
            val label = container.geocodingClient.reverse(point) ?: "Ma position"
            _state.update {
                it.copy(start = point, startLabel = label, locating = false, error = null)
            }
        }
    }

    fun setStart(point: GeoPoint, label: String? = null) {
        _state.update { it.copy(start = point, startLabel = label ?: "Point choisi", error = null) }
        if (label == null) {
            viewModelScope.launch {
                container.geocodingClient.reverse(point)?.let { resolved ->
                    _state.update { it.copy(startLabel = resolved) }
                }
            }
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.length < 3) {
            _state.update { it.copy(searchResults = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            val results = container.geocodingClient.search(query, _state.value.start)
            _state.update { it.copy(searchResults = results, searching = false) }
        }
    }

    fun clearSearch() {
        _state.update { it.copy(searchResults = emptyList()) }
    }

    fun setMode(mode: PlanMode) {
        _state.update { it.copy(mode = mode, error = null) }
    }

    /** Ajoute une etape a la suite des precedentes. */
    fun addWaypoint(point: GeoPoint) {
        _state.update { it.copy(waypoints = it.waypoints + point, manualRoute = null) }
    }

    fun removeWaypoint(index: Int) {
        _state.update { current ->
            if (index !in current.waypoints.indices) current
            else current.copy(
                waypoints = current.waypoints.filterIndexed { i, _ -> i != index },
                manualRoute = null,
            )
        }
    }

    fun moveWaypoint(index: Int, offset: Int) {
        _state.update { current ->
            val target = index + offset
            if (index !in current.waypoints.indices || target !in current.waypoints.indices) {
                current
            } else {
                val list = current.waypoints.toMutableList()
                val moved = list.removeAt(index)
                list.add(target, moved)
                current.copy(waypoints = list, manualRoute = null)
            }
        }
    }

    fun clearWaypoints() {
        _state.update { it.copy(waypoints = emptyList(), manualRoute = null) }
    }

    fun setManualLoop(loop: Boolean) {
        _state.update { it.copy(manualLoop = loop, manualRoute = null) }
    }

    /**
     * Calcule l'itineraire qui passe par le depart puis par chaque etape, dans
     * l'ordre. BRouter route de point en point : c'est le meme moteur que pour
     * les boucles proposees, donc les memes preferences de revetement et de
     * trafic s'appliquent.
     */
    fun computeManualRoute() {
        val current = _state.value
        val start = current.start ?: run {
            _state.update { it.copy(error = "Choisis d'abord un point de départ.") }
            return
        }
        if (current.waypoints.isEmpty()) {
            _state.update { it.copy(error = "Touche la carte pour poser au moins une étape.") }
            return
        }
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _state.update { it.copy(computing = true, error = null) }
            val profile = container.userPreferences.profile.first()
            val waypoints = buildList {
                add(start)
                addAll(current.waypoints)
                if (current.manualLoop) add(start)
            }
            runCatching {
                container.bRouterClient.route(waypoints, current.roadStyle.brouterProfile)
            }.onSuccess { track ->
                val route = PlannedRoute(
                    name = manualName(current, track.distanceM),
                    points = track.points,
                    distanceM = track.distanceM,
                    ascentM = track.ascentM,
                    descentM = track.descentM,
                    instructions = track.instructions,
                    surface = track.surface,
                    estimatedDurationMs = RideEstimator.estimateDuration(
                        points = track.points,
                        profile = profile,
                        junctionCount = track.instructions.size,
                    ),
                    source = RouteSource.MANUAL,
                )
                _state.update { it.copy(computing = false, manualRoute = route) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        computing = false,
                        error = error.message ?: "Impossible de relier ces points.",
                    )
                }
            }
        }
    }

    private fun manualName(state: PlanUiState, distanceM: Double): String {
        val km = (distanceM / 1000.0).toInt()
        val place = state.startLabel.substringBefore(',').trim()
        val kind = if (state.manualLoop) "Boucle" else "Parcours"
        return if (place.isBlank()) "$kind tracé de $km km" else "$kind de $km km depuis $place"
    }

    fun setDistance(km: Float) {
        _state.update { it.copy(targetDistanceKm = km) }
    }

    fun setTerrain(terrain: TerrainPreference) {
        _state.update { it.copy(terrain = terrain) }
    }

    fun setRoadStyle(style: RoadStyle) {
        _state.update { it.copy(roadStyle = style) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Lance la generation. Les propositions ne sont pas enregistrees en base :
     * l'utilisateur choisit ensuite celles qu'il veut conserver, pour ne pas
     * remplir sa bibliotheque de boucles qu'il n'a fait que survoler.
     */
    fun generate() {
        val current = _state.value
        val start = current.start ?: run {
            _state.update { it.copy(error = "Choisis d'abord un point de départ.") }
            return
        }
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, candidates = emptyList()) }
            val profile = container.userPreferences.profile.first()
            val request = RouteRequest(
                start = start,
                startLabel = current.startLabel,
                targetDistanceKm = current.targetDistanceKm.toDouble(),
                terrain = current.terrain,
                roadStyle = current.roadStyle,
                level = profile.level,
                candidateCount = 5,
            )
            runCatching {
                container.routeGenerator.generate(request, profile) { progress ->
                    _state.update { it.copy(progress = progress) }
                }
            }.onSuccess { candidates ->
                _state.update {
                    it.copy(loading = false, progress = null, candidates = candidates)
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        loading = false,
                        progress = null,
                        error = error.message ?: "La génération a échoué.",
                    )
                }
            }
        }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        _state.update { it.copy(loading = false, progress = null) }
    }

    fun open(route: PlannedRoute) {
        container.selectedRoute.value = route
    }

    fun toggleFavorite(route: PlannedRoute) {
        viewModelScope.launch {
            container.routeRepository.setFavorite(route.id, !route.isFavorite)
        }
    }

    fun delete(route: PlannedRoute) {
        viewModelScope.launch { container.routeRepository.delete(route.id) }
    }

    /** Import d'un fichier GPX ou TCX recu d'un autre service. */
    fun import(uri: Uri, onDone: (PlannedRoute?) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val resolver = getApplication<Application>().contentResolver
                val parsed = resolver.openInputStream(uri)?.use { GpxParser.parse(it) }
                    ?: error("Fichier illisible")
                val profile = container.userPreferences.profile.first()
                val withDuration = parsed.copy(
                    estimatedDurationMs = RideEstimator.estimateDuration(parsed.points, profile),
                )
                val id = container.routeRepository.save(withDuration)
                withDuration.copy(id = id)
            }.onSuccess { route ->
                container.selectedRoute.value = route
                onDone(route)
            }.onFailure { error ->
                _state.update {
                    it.copy(error = "Import impossible : ${error.message}")
                }
                onDone(null)
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as CadenceApp
                PlanViewModel(application, application.container)
            }
        }
    }
}
