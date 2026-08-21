package fr.velo.cadence.ui.history

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.velo.cadence.CadenceApp
import fr.velo.cadence.di.AppContainer
import fr.velo.cadence.export.ExportResult
import fr.velo.cadence.export.RouteExporter
import fr.velo.cadence.model.GeoPoint
import fr.velo.cadence.model.Ride
import fr.velo.cadence.model.RidePoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RideDetailUiState(
    val ride: Ride? = null,
    val trace: List<GeoPoint> = emptyList(),
    val points: List<RidePoint> = emptyList(),
    val loading: Boolean = true,
    val exporting: Boolean = false,
    val lastExport: ExportResult? = null,
    val message: String? = null,
)

class RideDetailViewModel(
    application: Application,
    private val container: AppContainer,
    private val rideId: Long,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(RideDetailUiState())
    val state: StateFlow<RideDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val ride = container.rideRepository.get(rideId)
            val points = container.rideRepository.points(rideId)
            val trace = points.map { it.toGeoPoint() }.ifEmpty {
                container.rideRepository.trace(rideId)
            }
            _state.update {
                it.copy(ride = ride, points = points, trace = trace, loading = false)
            }
        }
    }

    fun rename(title: String) {
        viewModelScope.launch {
            container.rideRepository.updateTitle(rideId, title)
            _state.update { it.copy(ride = it.ride?.copy(title = title)) }
        }
    }

    fun updateNote(note: String) {
        viewModelScope.launch {
            container.rideRepository.updateNote(rideId, note)
            _state.update { it.copy(ride = it.ride?.copy(note = note)) }
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            container.rideRepository.delete(rideId)
            onDone()
        }
    }

    /** Export GPX, pour televerser la sortie sur Strava ou l'archiver. */
    fun exportGpx() {
        val ride = _state.value.ride ?: return
        viewModelScope.launch {
            _state.update { it.copy(exporting = true) }
            runCatching {
                RouteExporter.exportRide(getApplication(), ride, _state.value.points)
            }.onSuccess { result ->
                _state.update {
                    it.copy(
                        exporting = false,
                        lastExport = result,
                        message = "${result.displayName} enregistré dans Téléchargements",
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(exporting = false, message = "Export impossible : ${error.message}")
                }
            }
        }
    }

    fun shareIntent(): Intent? = _state.value.lastExport?.let {
        RouteExporter.shareIntent(getApplication(), it, forceChooser = true)
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    companion object {
        /**
         * L'identifiant de la sortie est fixe a la construction : le passer
         * par la fabrique evite d'avoir a manipuler un SavedStateHandle pour
         * une seule valeur.
         */
        fun factory(rideId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as CadenceApp
                RideDetailViewModel(application, application.container, rideId)
            }
        }
    }
}
