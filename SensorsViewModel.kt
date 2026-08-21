package fr.velo.cadence.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.velo.cadence.data.prefs.SensorKind
import fr.velo.cadence.di.AppContainer
import fr.velo.cadence.di.cadenceViewModelFactory
import fr.velo.cadence.sensors.DiscoveredSensor
import fr.velo.cadence.sensors.SensorReadings
import fr.velo.cadence.sensors.SensorType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SensorsUiState(
    val scanning: Boolean = false,
    val discovered: List<DiscoveredSensor> = emptyList(),
    val readings: SensorReadings = SensorReadings(),
    val pairedHeartRate: String? = null,
    val pairedPower: String? = null,
    val pairedCsc: String? = null,
    val bluetoothReady: Boolean = false,
)

class SensorsViewModel(private val container: AppContainer) : ViewModel() {

    private val manager = container.bleSensorManager

    val state: StateFlow<SensorsUiState> = combine(
        manager.scanning,
        manager.discovered,
        manager.readings,
        container.userPreferences.settings,
    ) { scanning, discovered, readings, settings ->
        SensorsUiState(
            scanning = scanning,
            discovered = discovered,
            readings = readings,
            pairedHeartRate = settings.heartRateSensor,
            pairedPower = settings.powerSensor,
            pairedCsc = settings.cscSensor,
            bluetoothReady = manager.isSupported && manager.isEnabled && manager.hasPermissions(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SensorsUiState())

    fun startScan() = manager.startScan()

    fun stopScan() = manager.stopScan()

    fun pair(sensor: DiscoveredSensor) {
        viewModelScope.launch {
            container.userPreferences.setSensorAddress(sensor.kind.toPrefKind(), sensor.address)
            manager.connect(sensor.address)
        }
    }

    fun unpair(kind: SensorType, address: String?) {
        viewModelScope.launch {
            container.userPreferences.setSensorAddress(kind.toPrefKind(), null)
            address?.let { manager.disconnect(it) }
        }
    }

    private fun SensorType.toPrefKind(): SensorKind = when (this) {
        SensorType.HEART_RATE -> SensorKind.HEART_RATE
        SensorType.POWER -> SensorKind.POWER
        SensorType.SPEED_CADENCE -> SensorKind.SPEED_CADENCE
    }

    override fun onCleared() {
        manager.stopScan()
        super.onCleared()
    }

    companion object {
        val Factory = cadenceViewModelFactory { SensorsViewModel(it) }
    }
}
