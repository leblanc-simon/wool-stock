package io.leblanc.woolstock.scan

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.leblanc.woolstock.platform.ScanFrame
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Code-barre et OCR ML Kit sur chaque image de CameraX, sur un fil d'analyse dédié. */
internal class MlKitFrameAnalysis(private val onFrame: (ScanFrame) -> Unit) {
    private val executorService = Executors.newSingleThreadExecutor()

    /** ML Kit peut encore poster une annulation après la fermeture de l'écran : on l'ignore. */
    val executor = Executor { command ->
        try {
            executorService.execute(command)
        } catch (_: RejectedExecutionException) {
        }
    }

    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
            .build(),
    )
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Code-barre et OCR sur la même image ; coordonnées ramenées dans le repère de la PreviewView. */
    val analyzer = MlKitAnalyzer(
        listOf(barcodeScanner, textRecognizer),
        ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
        executor,
    ) { result ->
        result.getThrowable(barcodeScanner)?.let { Log.w(TAG, "Barcode scanning failed", it) }
        result.getThrowable(textRecognizer)?.let { Log.w(TAG, "Text recognition failed", it) }
        val text = result.getValue(textRecognizer)
        onFrame(
            ScanFrame(
                barcodes = result.getValue(barcodeScanner).orEmpty().mapNotNull { it.rawValue },
                lines = text?.toOcrLines().orEmpty(),
                text = text?.text,
            ),
        )
    }

    fun close() {
        barcodeScanner.close()
        textRecognizer.close()
        executorService.shutdown()
    }

    private companion object {
        const val TAG = "MlKitFrameAnalysis"
    }
}
