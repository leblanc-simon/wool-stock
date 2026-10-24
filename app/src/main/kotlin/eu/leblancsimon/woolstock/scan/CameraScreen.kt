package eu.leblancsimon.woolstock.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.leblancsimon.woolstock.R
import eu.leblancsimon.woolstock.parser.Vote
import eu.leblancsimon.woolstock.ui.ScanMode
import kotlinx.coroutines.launch

/** Scan d'une étiquette, pour une entrée, une sortie ou un comptage selon [mode]. */
@Composable
fun CameraScreen(
    mode: ScanMode,
    onCapture: (CapturedLabel) -> Unit,
    viewModel: ScanViewModel = viewModel(factory = ScanViewModel.Factory),
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }
    if (granted) {
        CameraContent(mode, viewModel, onCapture)
    } else {
        PermissionRequest(
            onRequest = { launcher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
        )
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.permission_rationale), textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text(stringResource(R.string.permission_grant)) }
        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.permission_settings)) }
    }
}

@Composable
private fun CameraContent(
    mode: ScanMode,
    viewModel: ScanViewModel,
    onCapture: (CapturedLabel) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val currentOnCapture by rememberUpdatedState(onCapture)

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            // Assez de pixels pour lire un petit tampon, sans trop ralentir l'OCR.
            imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                )
                .build()
        }
    }
    DisposableEffect(lifecycleOwner, controller) {
        controller.setImageAnalysisAnalyzer(viewModel.analysisExecutor, viewModel.analyzer)
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
        }
    }
    LaunchedEffect(state.torchOn) { controller.enableTorch(state.torchOn) }
    LaunchedEffect(Unit) { viewModel.resetScan() }
    LaunchedEffect(viewModel) { viewModel.captures.collect { currentOnCapture(it) } }

    val exportEmpty = stringResource(R.string.camera_export_empty)
    val chooserTitle = stringResource(R.string.camera_export_chooser)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                PreviewView(viewContext).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        state.debugFrame?.let { DebugOverlay(it, Modifier.fillMaxSize()) }

        ScanStatusPanel(
            mode = mode,
            state = state,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
        )

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SnackbarHost(snackbarHostState)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OverlayChip(stringResource(R.string.camera_debug), state.debugEnabled) { viewModel.setDebug(!state.debugEnabled) }
                OverlayChip(stringResource(R.string.camera_torch), state.torchOn) { viewModel.setTorch(!state.torchOn) }
                if (state.debugEnabled) {
                    TextButton(onClick = {
                        val dump = viewModel.exportDump()
                        scope.launch {
                            if (dump == null) snackbarHostState.showSnackbar(exportEmpty)
                            else OcrExport.share(context, dump, chooserTitle)
                        }
                    }) { Text(stringResource(R.string.camera_export), color = Color.White) }
                }
            }
            Button(onClick = viewModel::capture, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.camera_capture))
            }
        }
    }
}

@Composable
private fun OverlayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = OverlayBackground,
            labelColor = Color.White,
        ),
    )
}

private val OverlayBackground = Color.Black.copy(alpha = 0.55f)

@Composable
private fun ScanStatusPanel(mode: ScanMode, state: ScanUiState, modifier: Modifier = Modifier) {
    val snapshot = state.snapshot
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OverlayBackground, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(
                when (mode) {
                    ScanMode.ENTRY -> R.string.camera_mode_entry
                    ScanMode.EXIT -> R.string.camera_mode_exit
                    ScanMode.INVENTORY -> R.string.camera_mode_inventory
                },
            ),
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelLarge,
        )
        val ean = snapshot.ean?.value ?: state.lastEan
        Text(
            text = ean?.let { stringResource(R.string.camera_ean, it) } ?: stringResource(R.string.camera_searching_barcode),
            color = Color.White,
            fontWeight = if (ean != null) FontWeight.Bold else FontWeight.Normal,
        )
        val dyeLot = snapshot.dyeLot
        if (dyeLot != null) {
            VoteLine(dyeLot, R.string.camera_dye_lot, showRaw = state.debugEnabled)
        } else {
            Text(stringResource(R.string.camera_dye_lot_missing), color = Color.White.copy(alpha = 0.7f))
        }
        snapshot.brand?.let { VoteLine(it, R.string.camera_brand) }
        val color = listOfNotNull(snapshot.colorCode, snapshot.colorName)
        if (color.isNotEmpty()) {
            Text(
                text = stringResource(R.string.camera_color, color.joinToString(" ") { it.value }),
                color = Color.White.copy(alpha = if (color.all { it.accepted }) 1f else 0.7f),
            )
        }
        if (state.debugEnabled) {
            Text(stringResource(R.string.camera_auto_capture_off), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        } else if (state.autoCaptureProgress > 0f) {
            LinearProgressIndicator(progress = { state.autoCaptureProgress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Valeur votée : en gras si acceptée, atténuée tant qu'elle est sous le seuil. */
@Composable
private fun VoteLine(vote: Vote, labelRes: Int, showRaw: Boolean = false) {
    val value = if (showRaw && vote.rawValue != vote.value) {
        stringResource(R.string.format_raw_to_value, vote.rawValue, vote.value)
    } else {
        vote.value
    }
    val color = Color.White.copy(alpha = if (vote.accepted) 1f else 0.7f)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(labelRes, value),
            color = color,
            fontWeight = if (vote.accepted) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            text = stringResource(R.string.format_votes, vote.votes, vote.windowFrames),
            color = color,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
