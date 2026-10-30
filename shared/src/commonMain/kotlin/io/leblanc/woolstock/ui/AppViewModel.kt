package io.leblanc.woolstock.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import io.leblanc.woolstock.AppContainer
import io.leblanc.woolstock.LocalAppContainer

/** ViewModel de l'écran courant, construit avec les dépendances du conteneur de l'application. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: CreationExtras.(AppContainer) -> VM): VM {
    val container = LocalAppContainer.current
    return viewModel { create(container) }
}
