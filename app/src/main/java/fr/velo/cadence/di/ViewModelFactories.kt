package fr.velo.cadence.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.velo.cadence.CadenceApp

/**
 * Fabrique de ViewModel qui recupere le conteneur depuis l'application.
 * Evite d'avoir a passer les dependances main a main a travers l'arbre Compose.
 */
inline fun <reified VM : ViewModel> cadenceViewModelFactory(
    crossinline create: (AppContainer) -> VM,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
            as CadenceApp
        create(application.container)
    }
}
