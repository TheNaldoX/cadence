package fr.velo.cadence

import android.app.Application
import fr.velo.cadence.di.AppContainer
import org.osmdroid.config.Configuration
import java.io.File

class CadenceApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        configureMapTiles()
    }

    /**
     * Configuration d'osmdroid, a faire avant toute creation de carte.
     *
     * Deux points comptent : les serveurs de tuiles d'OpenStreetMap refusent
     * le trafic anonyme, donc l'identifiant applicatif est obligatoire ; et le
     * cache est place dans le dossier prive de l'application, ce qui evite de
     * demander la moindre permission de stockage.
     */
    private fun configureMapTiles() {
        val config = Configuration.getInstance()
        config.userAgentValue = BuildConfig.HTTP_USER_AGENT
        val base = File(cacheDir, "osmdroid")
        config.osmdroidBasePath = base
        config.osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
        // 200 Mo de tuiles gardees en cache : de quoi refaire ses parcours
        // habituels sans retelecharger, sans remplir le telephone.
        config.tileFileSystemCacheMaxBytes = 200L * 1024 * 1024
        config.tileFileSystemCacheTrimBytes = 150L * 1024 * 1024
    }
}
