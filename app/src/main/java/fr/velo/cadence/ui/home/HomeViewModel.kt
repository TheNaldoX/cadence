package fr.velo.cadence.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.velo.cadence.di.AppContainer
import fr.velo.cadence.di.cadenceViewModelFactory
import fr.velo.cadence.model.PlannedRoute
import fr.velo.cadence.model.Ride
import fr.velo.cadence.model.RiderLevel
import fr.velo.cadence.model.RiderProfile
import fr.velo.cadence.stats.PeriodSummary
import fr.velo.cadence.stats.StatsCalculator
import fr.velo.cadence.stats.TrainingLoad
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val profile: RiderProfile = RiderProfile(),
    val week: PeriodSummary = PeriodSummary("Cette semaine", 0, 0.0, 0.0, 0L, 0.0),
    val load: TrainingLoad = TrainingLoad(0.0, 0.0),
    val recentRides: List<Ride> = emptyList(),
    val favoriteRoutes: List<PlannedRoute> = emptyList(),
    val suggestedLevel: RiderLevel? = null,
    val isRecording: Boolean = false,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    val state: StateFlow<HomeUiState> = combine(
        container.userPreferences.profile,
        container.rideRepository.rides,
        container.routeRepository.routes,
        container.rideRecorder.metrics,
    ) { profile, rides, routes, metrics ->
        val suggested = StatsCalculator.estimateLevel(rides)
        HomeUiState(
            profile = profile,
            week = StatsCalculator.currentWeek(rides),
            load = StatsCalculator.trainingLoad(rides),
            recentRides = rides.take(3),
            favoriteRoutes = routes.filter { it.isFavorite }.take(3),
            suggestedLevel = suggested?.takeIf { it != profile.level && profile.autoLevelFromHistory },
            isRecording = metrics.state != fr.velo.cadence.model.RecordingState.IDLE,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun acceptSuggestedLevel(level: RiderLevel) {
        viewModelScope.launch { container.userPreferences.setLevel(level) }
    }

    fun openRoute(route: PlannedRoute) {
        container.selectedRoute.value = route
    }

    companion object {
        val Factory = cadenceViewModelFactory { HomeViewModel(it) }
    }
}
