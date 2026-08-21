package fr.velo.cadence

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import fr.velo.cadence.export.GpxParser
import fr.velo.cadence.model.PlannedRoute
import fr.velo.cadence.routing.RideEstimator
import fr.velo.cadence.ui.nav.CadenceNavHost
import fr.velo.cadence.ui.theme.CadenceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val container get() = (application as CadenceApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIncomingFile(intent)

        setContent {
            CadenceTheme {
                CadenceNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingFile(intent)
    }

    /**
     * Un GPX ouvert depuis une autre application (messagerie, gestionnaire de
     * fichiers, Komoot) est importe puis propose comme parcours courant.
     */
    private fun handleIncomingFile(intent: Intent?) {
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        } ?: return

        lifecycleScope.launch {
            runCatching {
                val parsed: PlannedRoute = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { GpxParser.parse(it) }
                        ?: error("Fichier illisible")
                }
                val profile = container.userPreferences.profile.first()
                val enriched = parsed.copy(
                    estimatedDurationMs = RideEstimator.estimateDuration(parsed.points, profile),
                )
                val id = container.routeRepository.save(enriched)
                container.selectedRoute.value = enriched.copy(id = id)
            }
        }
    }
}
