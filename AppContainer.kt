package fr.velo.cadence.di

import android.content.Context
import fr.velo.cadence.data.db.CadenceDatabase
import fr.velo.cadence.data.prefs.UserPreferences
import fr.velo.cadence.data.repo.RideRepository
import fr.velo.cadence.data.repo.RouteRepository
import fr.velo.cadence.navigation.NavigationEngine
import fr.velo.cadence.net.BRouterClient
import fr.velo.cadence.net.GeocodingClient
import fr.velo.cadence.routing.RouteGenerator
import fr.velo.cadence.sensors.BleSensorManager
import fr.velo.cadence.tracking.RideRecorder
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Conteneur de dependances de l'application.
 *
 * L'application n'utilise pas de bibliotheque d'injection : a cette taille,
 * un conteneur explicite construit paresseusement se lit mieux, compile plus
 * vite et evite une couche de generation de code.
 */
class AppContainer(private val context: Context) {

    private val database: CadenceDatabase by lazy { CadenceDatabase.get(context) }

    val userPreferences: UserPreferences by lazy { UserPreferences(context) }

    val rideRepository: RideRepository by lazy { RideRepository(database.rideDao()) }

    val routeRepository: RouteRepository by lazy { RouteRepository(database.routeDao()) }

    val bRouterClient: BRouterClient by lazy { BRouterClient() }

    val geocodingClient: GeocodingClient by lazy { GeocodingClient() }

    val routeGenerator: RouteGenerator by lazy { RouteGenerator(bRouterClient) }

    val bleSensorManager: BleSensorManager by lazy { BleSensorManager(context) }

    /**
     * L'enregistreur vit a l'echelle de l'application : le service lui pousse
     * des positions, l'interface l'observe, et une rotation d'ecran ou un
     * passage en arriere-plan ne fait rien perdre.
     */
    val rideRecorder: RideRecorder by lazy { RideRecorder() }

    val navigationEngine: NavigationEngine by lazy { NavigationEngine() }

    /**
     * Identifiant de la derniere sortie enregistree, publie par le service
     * pour que l'interface puisse ouvrir le resume automatiquement.
     */
    val lastSavedRideId = MutableStateFlow<Long?>(null)

    /**
     * Parcours actuellement ouvert dans l'ecran de detail. Passer l'objet par
     * ici plutot que par un argument de navigation evite de re-serialiser une
     * trace de plusieurs milliers de points a chaque changement d'ecran.
     */
    val selectedRoute = MutableStateFlow<fr.velo.cadence.model.PlannedRoute?>(null)
}
