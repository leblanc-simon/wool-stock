package eu.leblancsimon.woolstock.scan

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.leblancsimon.woolstock.parser.LabelDumps
import eu.leblancsimon.woolstock.parser.Vote
import eu.leblancsimon.woolstock.platform.CameraPreview
import eu.leblancsimon.woolstock.platform.rememberCameraPermission
import eu.leblancsimon.woolstock.platform.rememberFileSharer
import eu.leblancsimon.woolstock.resources.Res
import eu.leblancsimon.woolstock.resources.camera_auto_capture_off
import eu.leblancsimon.woolstock.resources.camera_brand
import eu.leblancsimon.woolstock.resources.camera_capture
import eu.leblancsimon.woolstock.resources.camera_color
import eu.leblancsimon.woolstock.resources.camera_debug
import eu.leblancsimon.woolstock.resources.camera_dye_lot
import eu.leblancsimon.woolstock.resources.camera_dye_lot_missing
import eu.leblancsimon.woolstock.resources.camera_ean
import eu.leblancsimon.woolstock.resources.camera_export
import eu.leblancsimon.woolstock.resources.camera_export_chooser
import eu.leblancsimon.woolstock.resources.camera_export_empty
import eu.leblancsimon.woolstock.resources.camera_mode_entry
import eu.leblancsimon.woolstock.resources.camera_mode_exit
import eu.leblancsimon.woolstock.resources.camera_mode_inventory
import eu.leblancsimon.woolstock.resources.camera_searching_barcode
import eu.leblancsimon.woolstock.resources.camera_torch
import eu.leblancsimon.woolstock.resources.format_raw_to_value
import eu.leblancsimon.woolstock.resources.format_votes
import eu.leblancsimon.woolstock.resources.permission_grant
import eu.leblancsimon.woolstock.resources.permission_rationale
import eu.leblancsimon.woolstock.resources.permission_settings
import eu.leblancsimon.woolstock.resources.permission_title
import eu.leblancsimon.woolstock.ui.ScanMode
import eu.leblancsimon.woolstock.ui.appViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Scan d'une étiquette, pour une entrée, une sortie ou un comptage selon [mode]. */
@Composable
fun CameraScreen(
    mode: ScanMode,
    onCapture: (CapturedLabel) -> Unit,
    viewModel: ScanViewModel = appViewModel { ScanViewModel(it.labelParser) },
) {
    val permission = rememberCameraPermission()
    if (permission.granted) {
        CameraContent(mode, viewModel, onCapture)
    } else {
        PermissionRequest(onRequest = permission.request, onOpenSettings = permission.openSettings)
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(Res.string.permission_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(Res.string.permission_rationale), textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text(stringResource(Res.string.permission_grant)) }
        TextButton(onClick = onOpenSettings) { Text(stringResource(Res.string.permission_settings)) }
    }
}

@Composable
private fun CameraContent(
    mode: ScanMode,
    viewModel: ScanViewModel,
    onCapture: (CapturedLabel) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val sharer = rememberFileSharer()
    val currentOnCapture by rememberUpdatedState(onCapture)

    LaunchedEffect(Unit) { viewModel.resetScan() }
    LaunchedEffect(viewModel) { viewModel.captures.collect { currentOnCapture(it) } }

    val exportEmpty = stringResource(Res.string.camera_export_empty)
    val chooserTitle = stringResource(Res.string.camera_export_chooser)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(torchOn = state.torchOn, onFrame = viewModel::onFrame, modifier = Modifier.fillMaxSize())
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
                OverlayChip(stringResource(Res.string.camera_debug), state.debugEnabled) { viewModel.setDebug(!state.debugEnabled) }
                OverlayChip(stringResource(Res.string.camera_torch), state.torchOn) { viewModel.setTorch(!state.torchOn) }
                if (state.debugEnabled) {
                    TextButton(onClick = {
                        val dump = viewModel.exportDump()
                        scope.launch {
                            if (dump == null) {
                                snackbarHostState.showSnackbar(exportEmpty)
                            } else {
                                // Relevé OCR à déposer tel quel dans label-parser/src/jvmTest/resources/labels.
                                sharer.share("${dump.id}.json", "application/json", LabelDumps.encode(dump).encodeToByteArray(), chooserTitle)
                            }
                        }
                    }) { Text(stringResource(Res.string.camera_export), color = Color.White) }
                }
            }
            Button(onClick = viewModel::capture, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.camera_capture))
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
                    ScanMode.ENTRY -> Res.string.camera_mode_entry
                    ScanMode.EXIT -> Res.string.camera_mode_exit
                    ScanMode.INVENTORY -> Res.string.camera_mode_inventory
                },
            ),
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelLarge,
        )
        val ean = snapshot.ean?.value ?: state.lastEan
        Text(
            text = ean?.let { stringResource(Res.string.camera_ean, it) } ?: stringResource(Res.string.camera_searching_barcode),
            color = Color.White,
            fontWeight = if (ean != null) FontWeight.Bold else FontWeight.Normal,
        )
        val dyeLot = snapshot.dyeLot
        if (dyeLot != null) {
            VoteLine(dyeLot, Res.string.camera_dye_lot, showRaw = state.debugEnabled)
        } else {
            Text(stringResource(Res.string.camera_dye_lot_missing), color = Color.White.copy(alpha = 0.7f))
        }
        snapshot.brand?.let { VoteLine(it, Res.string.camera_brand) }
        val color = listOfNotNull(snapshot.colorCode, snapshot.colorName)
        if (color.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.camera_color, color.joinToString(" ") { it.value }),
                color = Color.White.copy(alpha = if (color.all { it.accepted }) 1f else 0.7f),
            )
        }
        if (state.debugEnabled) {
            Text(stringResource(Res.string.camera_auto_capture_off), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        } else if (state.autoCaptureProgress > 0f) {
            LinearProgressIndicator(progress = { state.autoCaptureProgress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Valeur votée : en gras si acceptée, atténuée tant qu'elle est sous le seuil. */
@Composable
private fun VoteLine(vote: Vote, labelRes: StringResource, showRaw: Boolean = false) {
    val value = if (showRaw && vote.rawValue != vote.value) {
        stringResource(Res.string.format_raw_to_value, vote.rawValue, vote.value)
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
            text = stringResource(Res.string.format_votes, vote.votes, vote.windowFrames),
            color = color,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
