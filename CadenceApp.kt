package fr.velo.cadence

import android.app.Application
import fr.velo.cadence.di.AppContainer
import org.maplibre.android.MapLibre

class CadenceApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // MapLibre doit etre initialise avant toute creation de MapView.
        // Aucune cle n'est necessaire : le fond de carte vient d'OpenFreeMap.
        MapLibre.getInstance(this)
    }
}
