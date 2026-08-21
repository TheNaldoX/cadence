package fr.velo.cadence.ui.plan

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.velo.cadence.CadenceApp
import fr.velo.cadence.di.AppContainer
import fr.velo.cadence.export.ExportFormat
import fr.velo.cadence.export.ExportResult
import fr.velo.cadence.export.RouteExporter
import fr.velo.cadence.model.PlannedRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RouteDetailUiState(
    val route: PlannedRoute? = null,
    val saved: Boolean = false,
    val exporting: Boolean = false,
    val lastExport: ExportResult? = null,
    val message: String? = null,
    val igpsportInstalled: Boolean = false,
)

class RouteDetailViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(RouteDetailUiState())
    val state: StateFlow<RouteDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.selectedRoute.collect { route ->
                _state.update {
                    it.copy(
                        route = route,
                        saved = route != null && route.id != 0L,
                        igpsportInstalled = RouteExporter.isIgpsportInstalled(getApplication()),
                    )
                }
            }
        }
    }

    fun save() {
        val route = _state.value.route ?: return
        viewModelScope.launch {
            val id = container.routeRepository.save(route)
            val stored = route.copy(id = id)
            container.selectedRoute.value = stored
            _state.update { it.copy(saved = true, message = "Parcours enregistré") }
        }
    }

    fun toggleFavorite() {
        val route = _state.value.route ?: return
        viewModelScope.launch {
            val id = if (route.id == 0L) container.routeRepository.save(route) else route.id
            container.routeRepository.setFavorite(id, !route.isFavorite)
            container.selectedRoute.value = route.copy(id = id, isFavorite = !route.isFavorite)
        }
    }

    fun rename(name: String) {
        val route = _state.value.route ?: return
        container.selectedRoute.value = route.copy(name = name)
        if (route.id != 0L) {
            viewModelScope.launch { container.routeRepository.update(route.copy(name = name)) }
        }
    }

    /**
     * Produit le fichier et l'ecrit dans Telechargements. C'est la seule voie
     * dont on sait qu'elle fonctionne avec l'application iGPSPORT sur Android :
     * le partage direct est propose ensuite, en raccourci.
     */
    fun export(format: ExportFormat) {
        val route = _state.value.route ?: return
        viewModelScope.launch {
            _state.update { it.copy(exporting = true, message = null) }
            runCatching {
                RouteExporter.exportRoute(getApplication(), route, format)
            }.onSuccess { result ->
                _state.update {
                    it.copy(
                        exporting = false,
                        lastExport = result,
                        message = if (result.downloadUri != null) {
                            "${result.displayName} enregistré dans Téléchargements"
                        } else {
                            "${result.displayName} prêt à être partagé"
                        },
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(exporting = false, message = "Export impossible : ${error.message}")
                }
            }
        }
    }

    fun shareIntent(forceChooser: Boolean = false): Intent? {
        val result = _state.value.lastExport ?: return null
        return RouteExporter.shareIntent(getApplication(), result, forceChooser)
    }

    fun openIgpsportIntent(): Intent? = RouteExporter.openIgpsport(getApplication())

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    /** Arme la navigation guidee : la sortie demarrera en suivant ce parcours. */
    fun startGuidedRide(): Long? {
        val route = _state.value.route ?: return null
        container.navigationEngine.start(route)
        return route.id.takeIf { it != 0L }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as CadenceApp
                RouteDetailViewModel(application, application.container)
            }
        }
    }
}
