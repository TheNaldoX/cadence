package fr.velo.cadence.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.velo.cadence.data.db.entity.PersonalRecordEntity
import fr.velo.cadence.di.AppContainer
import fr.velo.cadence.di.cadenceViewModelFactory
import fr.velo.cadence.model.Ride
import fr.velo.cadence.model.RiderLevel
import fr.velo.cadence.stats.PeriodSummary
import fr.velo.cadence.stats.StatsCalculator
import fr.velo.cadence.stats.TrainingLoad
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class StatsUiState(
    val weekly: List<PeriodSummary> = emptyList(),
    val monthly: List<PeriodSummary> = emptyList(),
    val allTime: PeriodSummary = PeriodSummary("Total", 0, 0.0, 0.0, 0L, 0.0),
    val year: PeriodSummary = PeriodSummary("Cette année", 0, 0.0, 0.0, 0L, 0.0),
    val load: TrainingLoad = TrainingLoad(0.0, 0.0),
    val records: List<PersonalRecordEntity> = emptyList(),
    val estimatedLevel: RiderLevel? = null,
    val longestRide: Ride? = null,
    val biggestClimb: Ride? = null,
)

class StatsViewModel(container: AppContainer) : ViewModel() {

    val state: StateFlow<StatsUiState> = combine(
        container.rideRepository.rides,
        container.rideRepository.records,
    ) { rides, records ->
        val startOfYear = java.time.LocalDate.now()
            .withDayOfYear(1)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        StatsUiState(
            weekly = StatsCalculator.weekly(rides, 12),
            monthly = StatsCalculator.monthly(rides, 12),
            allTime = StatsCalculator.summarize("Total", rides),
            year = StatsCalculator.summarize(
                "Cette année",
                rides.filter { it.startedAt >= startOfYear },
            ),
            load = StatsCalculator.trainingLoad(rides),
            records = records,
            estimatedLevel = StatsCalculator.estimateLevel(rides),
            longestRide = rides.maxByOrNull { it.distanceM },
            biggestClimb = rides.maxByOrNull { it.ascentM },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    companion object {
        val Factory = cadenceViewModelFactory { StatsViewModel(it) }
    }
}
