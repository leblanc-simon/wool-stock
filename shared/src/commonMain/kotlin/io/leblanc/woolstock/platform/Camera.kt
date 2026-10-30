package io.leblanc.woolstock.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.leblanc.woolstock.parser.OcrLine

/**
 * Ce que la caméra a lu sur une image : les codes-barres bruts et les lignes OCR, dont les
 * boîtes sont dans le repère de l'aperçu, en pixels (celui où DebugOverlay dessine).
 */
class ScanFrame(val barcodes: List<String>, val lines: List<OcrLine>, val text: String?)

/**
 * Aperçu plein cadre de la caméra arrière, qui analyse chaque image (code-barre et texte) sur
 * l'appareil et la passe à [onFrame], depuis un fil d'analyse. CameraX et ML Kit sur Android,
 * AVFoundation et Vision sur iOS.
 */
@Composable
expect fun CameraPreview(torchOn: Boolean, onFrame: (ScanFrame) -> Unit, modifier: Modifier = Modifier)

/** Autorisation d'utiliser la caméra, et de quoi la demander ou ouvrir les réglages du système. */
class CameraPermission(val granted: Boolean, val request: () -> Unit, val openSettings: () -> Unit)

/** Demande l'autorisation dès le premier affichage si elle n'est pas encore accordée. */
@Composable
expect fun rememberCameraPermission(): CameraPermission
