package fr.velo.cadence.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.velo.cadence.data.prefs.AppSettings
import fr.velo.cadence.di.AppContainer
import fr.velo.cadence.di.cadenceViewModelFactory
import fr.velo.cadence.model.RiderLevel
import fr.velo.cadence.model.RiderProfile
import fr.velo.cadence.model.RoadStyle
import fr.velo.cadence.stats.StatsCalculator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileUiState(
    val profile: RiderProfile = RiderProfile(),
    val settings: AppSettings = AppSettings(),
    val estimatedLevel: RiderLevel? = null,
    val hasBarometer: Boolean = false,
)

class ProfileViewModel(private val container: AppContainer) : ViewModel() {

    val state: StateFlow<ProfileUiState> = combine(
        container.userPreferences.profile,
        container.userPreferences.settings,
        container.rideRepository.rides,
    ) { profile, settings, rides ->
        ProfileUiState(
            profile = profile,
            settings = settings,
            estimatedLevel = StatsCalculator.estimateLevel(rides),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

    fun update(transform: (RiderProfile) -> RiderProfile) {
        viewModelScope.launch {
            container.userPreferences.updateProfile(transform(state.value.profile))
        }
    }

    fun setName(name: String) = update { it.copy(displayName = name) }
    fun setLevel(level: RiderLevel) = update { it.copy(level = level) }
    fun setRoadStyle(style: RoadStyle) = update { it.copy(preferredRoadStyle = style) }
    fun setRiderWeight(kg: Double) = update { it.copy(riderWeightKg = kg) }
    fun setBikeWeight(kg: Double) = update { it.copy(bikeWeightKg = kg) }
    fun setFtp(watts: Int) = update { it.copy(ftpWatts = watts) }
    fun setMaxHeartRate(bpm: Int) = update { it.copy(maxHeartRate = bpm) }
    fun setAutoLevel(enabled: Boolean) = update { it.copy(autoLevelFromHistory = enabled) }

    fun setAutoPause(enabled: Boolean) {
        viewModelScope.launch { container.userPreferences.setAutoPause(enabled) }
    }

    fun setVoiceGuidance(enabled: Boolean) {
        viewModelScope.launch { container.userPreferences.setVoiceGuidance(enabled) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { container.userPreferences.setKeepScreenOn(enabled) }
    }

    fun setUseBarometer(enabled: Boolean) {
        viewModelScope.launch { container.userPreferences.setUseBarometer(enabled) }
    }

    fun setExportFormat(format: String) {
        viewModelScope.launch { container.userPreferences.setExportFormat(format) }
    }

    companion object {
        val Factory = cadenceViewModelFactory { ProfileViewModel(it) }
    }
}
