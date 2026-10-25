package eu.leblancsimon.woolstock.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.leblancsimon.woolstock.AppContainer
import eu.leblancsimon.woolstock.LocalAppContainer

/** ViewModel de l'écran courant, construit avec les dépendances du conteneur de l'application. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: CreationExtras.(AppContainer) -> VM): VM {
    val container = LocalAppContainer.current
    return viewModel { create(container) }
}
