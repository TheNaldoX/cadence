package fr.velo.cadence.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.velo.cadence.di.AppContainer
import fr.velo.cadence.di.cadenceViewModelFactory
import fr.velo.cadence.model.Ride
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class RideGroup(val label: String, val rides: List<Ride>) {
    val distanceKm: Double get() = rides.sumOf { it.distanceM } / 1000.0
    val ascentM: Double get() = rides.sumOf { it.ascentM }
}

class HistoryViewModel(container: AppContainer) : ViewModel() {

    private val monthFormatter = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.FRANCE)

    val groups: StateFlow<List<RideGroup>> = container.rideRepository.rides
        .map { rides ->
            rides.groupBy { ride ->
                Instant.ofEpochMilli(ride.startedAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .withDayOfMonth(1)
            }.map { (month, list) ->
                RideGroup(
                    label = month.format(monthFormatter).replaceFirstChar { it.uppercase() },
                    rides = list.sortedByDescending { it.startedAt },
                )
            }.sortedByDescending { group -> group.rides.firstOrNull()?.startedAt ?: 0L }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        val Factory = cadenceViewModelFactory { HistoryViewModel(it) }
    }
}
