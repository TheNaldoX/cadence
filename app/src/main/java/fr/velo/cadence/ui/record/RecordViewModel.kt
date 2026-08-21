package fr.velo.cadence.ui.record

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.velo.cadence.CadenceApp
import fr.velo.cadence.di.AppContainer
import fr.velo.cadence.model.LiveMetrics
import fr.velo.cadence.model.RecordingState
import fr.velo.cadence.navigation.NavigationState
import fr.velo.cadence.navigation.VoiceGuide
import fr.velo.cadence.tracking.LocationTrackingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecordViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {

    val metrics: StateFlow<LiveMetrics> = container.rideRecorder.metrics

    val navigation: StateFlow<NavigationState> = container.navigationEngine.state

    /** Parcours suivi, affiche en fond de carte pendant la navigation. */
    val plannedRoute: StateFlow<fr.velo.cadence.model.PlannedRoute?> = container.selectedRoute

    val savedRideId: StateFlow<Long?> = container.lastSavedRideId

    private val _keepScreenOn = MutableStateFlow(true)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _sensorsConnected = MutableStateFlow<Set<String>>(emptySet())
    val sensorsConnected: StateFlow<Set<String>> = _sensorsConnected.asStateFlow()

    private var voice: VoiceGuide? = null

    val hasSensorsConfigured: StateFlow<Boolean> = container.userPreferences.settings
        .map { settings ->
            settings.heartRateSensor != null ||
                settings.powerSensor != null ||
                settings.cscSensor != null
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            container.userPreferences.settings.collect { settings ->
                _keepScreenOn.value = settings.keepScreenOn
                if (settings.voiceGuidance && voice == null) {
                    voice = VoiceGuide(getApplication())
                } else if (!settings.voiceGuidance) {
                    voice?.shutdown()
                    voice = null
                }
            }
        }
        viewModelScope.launch {
            container.bleSensorManager.readings.collect { readings ->
                _sensorsConnected.value = readings.connected.map { it.label }.toSet()
            }
        }
        // Les positions alimentent aussi le suivi de parcours quand une
        // navigation est armee.
        viewModelScope.launch {
            container.rideRecorder.metrics.collect { live ->
                val point = live.lastPoint
                if (point != null && container.navigationEngine.state.value.active) {
                    container.navigationEngine.update(point)
                }
            }
        }
        viewModelScope.launch {
            container.navigationEngine.announcement.collect { text ->
                if (text != null) {
                    voice?.speak(text)
                    container.navigationEngine.consumeAnnouncement()
                }
            }
        }
    }

    fun start() {
        container.lastSavedRideId.value = null
        val routeId = container.navigationEngine.state.value
            .takeIf { it.active }
            ?.let { container.selectedRoute.value?.id?.takeIf { id -> id != 0L } }
        LocationTrackingService.start(getApplication(), routeId)
    }

    fun pause() = LocationTrackingService.send(getApplication(), LocationTrackingService.ACTION_PAUSE)

    fun resume() = LocationTrackingService.send(getApplication(), LocationTrackingService.ACTION_RESUME)

    fun finish() {
        LocationTrackingService.send(getApplication(), LocationTrackingService.ACTION_STOP)
        container.navigationEngine.stop()
    }

    fun discard() {
        container.rideRecorder.discard()
        LocationTrackingService.send(getApplication(), LocationTrackingService.ACTION_STOP)
        container.navigationEngine.stop()
    }

    fun stopNavigation() = container.navigationEngine.stop()

    fun consumeSavedRide() {
        container.lastSavedRideId.value = null
    }

    /** Vrai si un enregistrement est deja en cours au moment d'ouvrir l'ecran. */
    suspend fun isAutoPauseEnabled(): Boolean =
        container.userPreferences.settings.first().autoPause

    val isIdle: Boolean get() = metrics.value.state == RecordingState.IDLE

    override fun onCleared() {
        voice?.shutdown()
        voice = null
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as CadenceApp
                RecordViewModel(application, application.container)
            }
        }
    }
}
