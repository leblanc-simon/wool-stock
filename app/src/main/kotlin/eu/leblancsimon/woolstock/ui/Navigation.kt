package eu.leblancsimon.woolstock.ui

import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import eu.leblancsimon.woolstock.R
import eu.leblancsimon.woolstock.WoolStockApp
import eu.leblancsimon.woolstock.confirm.ConfirmScreen
import eu.leblancsimon.woolstock.data.api.ApiException
import eu.leblancsimon.woolstock.data.api.StockLot
import eu.leblancsimon.woolstock.inventory.InventoryScreen
import eu.leblancsimon.woolstock.login.LoginScreen
import eu.leblancsimon.woolstock.scan.CameraScreen
import eu.leblancsimon.woolstock.scan.CapturedLabel
import eu.leblancsimon.woolstock.settings.SettingsScreen
import eu.leblancsimon.woolstock.settings.UsersScreen
import eu.leblancsimon.woolstock.stock.StockExport
import eu.leblancsimon.woolstock.stock.StockScreen
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.IOException
import kotlin.reflect.KClass

/** Ce que l'on fait d'une étiquette scannée ; argument de navigation. */
@Keep
enum class ScanMode { ENTRY, EXIT, INVENTORY }

@Serializable
object StockRoute

@Serializable
object EntryRoute

@Serializable
object ExitRoute

/** L'onglet Inventaire : la session en cours, et le scan qui s'y rattache. */
@Serializable
object InventoryGraph

@Serializable
object InventoryRoute

@Serializable
data class InventoryScanRoute(val inventoryId: Long)

@Serializable
object SettingsRoute

/** Connexion par adresse email ; seul écran accessible sans session, avec les réglages. */
@Serializable
object LoginRoute

/** Gestion des comptes, pour les admins. */
@Serializable
object UsersRoute

/** Saisie à confirmer : résultat figé d'une capture, ou bain choisi dans le stock. */
@Serializable
data class ConfirmRoute(
    val mode: ScanMode,
    val ean: String? = null,
    val dyeLot: String? = null,
    val dyeLotRaw: String? = null,
    val brand: String? = null,
    val colorCode: String? = null,
    val colorName: String? = null,
    /** Bain choisi dans la liste du stock plutôt que lu sur une étiquette. */
    val fromStock: Boolean = false,
    /** Inventaire auquel rattacher un comptage. */
    val inventoryId: Long? = null,
) {
    companion object {
        fun captured(mode: ScanMode, label: CapturedLabel, inventoryId: Long? = null) = ConfirmRoute(
            mode = mode,
            ean = label.ean,
            dyeLot = label.dyeLot,
            dyeLotRaw = label.dyeLotRaw,
            brand = label.brand,
            colorCode = label.colorCode,
            colorName = label.colorName,
            inventoryId = inventoryId,
        )

        fun exit(lot: StockLot) = ConfirmRoute(
            mode = ScanMode.EXIT,
            ean = lot.ean,
            dyeLot = lot.dyeLot,
            brand = lot.brand,
            colorCode = lot.colorCode,
            colorName = lot.colorName,
            fromStock = true,
        )
    }
}

/**
 * Saisie enregistrée, annoncée au retour de l'écran de confirmation. [quantity] est la
 * quantité saisie, ou pour un comptage le total compté du bain.
 */
data class SavedScan(val mode: ScanMode, val quantity: Int, val dyeLot: String, val recipient: String? = null)

private enum class Tab(
    val route: Any,
    val routeClass: KClass<*>,
    /** Premier écran de l'onglet : on y revient en touchant l'onglet déjà ouvert. */
    val start: Any,
    @StringRes val label: Int,
    val icon: ImageVector,
) {
    STOCK(StockRoute, StockRoute::class, StockRoute, R.string.nav_stock, Icons.AutoMirrored.Filled.List),
    ENTRY(EntryRoute, EntryRoute::class, EntryRoute, R.string.nav_entry, Icons.Filled.AddCircle),
    EXIT(ExitRoute, ExitRoute::class, ExitRoute, R.string.nav_exit, Icons.AutoMirrored.Filled.ExitToApp),
    INVENTORY(InventoryGraph, InventoryGraph::class, InventoryRoute, R.string.nav_inventory, Icons.Filled.CheckCircle),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WoolStockNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val container = (context.applicationContext as WoolStockApp).container
    val settings = container.settings
    val session by settings.session.collectAsStateWithLifecycle()
    val startRoute: Any = remember { if (settings.session.value != null) StockRoute else LoginRoute }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    // Export Excel lancé depuis l'en-tête : il continue si l'on change d'onglet entre-temps.
    var exporting by remember { mutableStateOf(false) }
    val exportStock: () -> Unit = {
        exporting = true
        scope.launch {
            val failure = try {
                StockExport.share(context, container.stockRepository.export(), resources.getString(R.string.stock_export_chooser))
                null
            } catch (e: ApiException) {
                e
            } catch (e: IOException) {
                e
            }
            exporting = false
            failure?.let { snackbarHostState.showSnackbar(it.toErrorMessage().text(resources)) }
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentTab = Tab.entries.firstOrNull { tab ->
        backStackEntry?.destination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
    }

    // Session perdue (déconnexion, ou le serveur ne la reconnaît plus) : retour à la connexion.
    LaunchedEffect(session == null) {
        val current = backStackEntry?.destination
        if (session == null && current != null && !current.hasRoute(LoginRoute::class)) {
            navController.navigate(LoginRoute) { popUpTo(navController.graph.id) { inclusive = true } }
        }
    }

    Scaffold(
        // Chaque écran gère la barre d'état : la caméra s'affiche dessous.
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // En-tête commun aux onglets : logo, nom de l'application, export (onglet Stock), réglages.
        topBar = {
            if (currentTab != null) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(36.dp))
                            Text(stringResource(R.string.app_name))
                        }
                    },
                    actions = {
                        if (currentTab == Tab.STOCK) ExportAction(exporting, onExport = exportStock)
                        IconButton(onClick = { navController.navigate(SettingsRoute) }) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                        }
                    },
                )
            }
        },
        bottomBar = {
            // Barre visible sur les onglets, masquée sur la confirmation et les réglages.
            if (currentTab != null) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == currentTab,
                            onClick = {
                                if (tab == currentTab) {
                                    navController.popBackStack(tab.start, inclusive = false)
                                } else {
                                    navController.navigateToTab(tab)
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startRoute,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable<LoginRoute> {
                LoginScreen(
                    onLoggedIn = {
                        navController.navigate(StockRoute) { popUpTo(navController.graph.id) { inclusive = true } }
                    },
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                )
            }
            composable<StockRoute> {
                StockScreen(onExit = { lot -> navController.navigate(ConfirmRoute.exit(lot)) })
            }
            composable<EntryRoute> {
                CameraScreen(ScanMode.ENTRY, onCapture = { navController.navigate(ConfirmRoute.captured(ScanMode.ENTRY, it)) })
            }
            composable<ExitRoute> {
                CameraScreen(ScanMode.EXIT, onCapture = { navController.navigate(ConfirmRoute.captured(ScanMode.EXIT, it)) })
            }
            navigation<InventoryGraph>(startDestination = InventoryRoute) {
                composable<InventoryRoute> {
                    InventoryScreen(onScan = { id -> navController.navigate(InventoryScanRoute(id)) })
                }
                composable<InventoryScanRoute> { entry ->
                    val inventoryId = entry.toRoute<InventoryScanRoute>().inventoryId
                    CameraScreen(
                        ScanMode.INVENTORY,
                        onCapture = { navController.navigate(ConfirmRoute.captured(ScanMode.INVENTORY, it, inventoryId)) },
                    )
                }
            }
            composable<ConfirmRoute> {
                ConfirmScreen(
                    onSaved = { saved ->
                        navController.popBackStack()
                        val dyeLot = saved.dyeLot.ifEmpty { "—" }
                        val message = when (saved.mode) {
                            ScanMode.ENTRY -> resources.getQuantityString(
                                R.plurals.confirm_saved_entry, saved.quantity, saved.quantity, dyeLot,
                            )
                            ScanMode.EXIT -> resources.getQuantityString(
                                R.plurals.confirm_saved_exit, saved.quantity, saved.quantity, dyeLot, saved.recipient.orEmpty(),
                            )
                            ScanMode.INVENTORY -> resources.getQuantityString(
                                R.plurals.confirm_saved_inventory, saved.quantity, saved.quantity, dyeLot,
                            )
                        }
                        scope.launch { snackbarHostState.showSnackbar(message) }
                    },
                    onCancel = { navController.popBackStack() },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenUsers = { navController.navigate(UsersRoute) },
                )
            }
            composable<UsersRoute> {
                UsersScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

/** Export Excel du stock : l'icône de partage, ou une roue pendant le téléchargement. */
@Composable
private fun ExportAction(exporting: Boolean, onExport: () -> Unit) {
    if (exporting) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    } else {
        IconButton(onClick = onExport) {
            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.stock_export))
        }
    }
}

/** Un onglet garde son état : on y revient là où on l'a laissé. */
private fun NavHostController.navigateToTab(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
