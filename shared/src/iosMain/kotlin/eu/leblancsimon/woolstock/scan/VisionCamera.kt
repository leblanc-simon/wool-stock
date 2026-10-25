package eu.leblancsimon.woolstock.scan

import eu.leblancsimon.woolstock.parser.OcrLine
import eu.leblancsimon.woolstock.parser.OcrWord
import eu.leblancsimon.woolstock.parser.Rect
import eu.leblancsimon.woolstock.platform.ScanFrame
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVCaptureAutoFocusRangeRestrictionNear
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureFocusModeContinuousAutoFocus
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPreset1920x1080
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.autoFocusRangeRestriction
import platform.AVFoundation.autoFocusRangeRestrictionSupported
import platform.AVFoundation.defaultDeviceWithDeviceType
import platform.AVFoundation.focusMode
import platform.AVFoundation.hasTorch
import platform.AVFoundation.isFocusModeSupported
import platform.AVFoundation.torchMode
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectZero
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.Foundation.NSMakeRange
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.Vision.VNBarcodeObservation
import platform.Vision.VNBarcodeSymbologyEAN13
import platform.Vision.VNBarcodeSymbologyEAN8
import platform.Vision.VNBarcodeSymbologyUPCE
import platform.Vision.VNDetectBarcodesRequest
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedText
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRequestTextRecognitionLevelAccurate
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.Volatile

/**
 * Caméra arrière (AVFoundation) dont chaque image est lue sur l'appareil par Vision : texte et
 * codes-barres EAN/UPC, l'équivalent de CameraX + ML Kit sur Android. Les images arrivent
 * droites (portrait) ; celles qui arrivent pendant une analyse sont abandonnées.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class VisionCamera(private val onFrame: (ScanFrame) -> Unit) {
    private val session = AVCaptureSession()
    private val sessionQueue = dispatch_queue_create("eu.leblancsimon.woolstock.camera", null)
    private val analysisQueue = dispatch_queue_create("eu.leblancsimon.woolstock.analysis", null)
    private val device: AVCaptureDevice? = AVCaptureDevice.defaultDeviceWithDeviceType(
        AVCaptureDeviceTypeBuiltInWideAngleCamera,
        AVMediaTypeVideo,
        AVCaptureDevicePositionBack,
    )
    private val delegate = SampleBufferDelegate(::analyze)
    private var configured = false

    val previewView = CameraPreviewView(session)

    /** Densité de l'écran, mise à jour par la composition, lue par le fil d'analyse. */
    @Volatile
    var pixelsPerPoint = 1.0

    // Pas de correction linguistique : les étiquettes sont faites de codes, pas de phrases.
    private val textRequest = VNRecognizeTextRequest().apply {
        recognitionLevel = VNRequestTextRecognitionLevelAccurate
        usesLanguageCorrection = false
        recognitionLanguages = listOf("fr-FR", "en-US", "de-DE", "it-IT", "es-ES")
    }
    private val barcodeRequest = VNDetectBarcodesRequest().apply {
        // Un UPC-A arrive en EAN-13 précédé d'un 0 : Gtin.normalize s'en charge.
        symbologies = listOf(VNBarcodeSymbologyEAN13, VNBarcodeSymbologyEAN8, VNBarcodeSymbologyUPCE)
    }

    /** startRunning bloque : la session démarre et s'arrête sur sa propre file. */
    fun start() = dispatch_async(sessionQueue) {
        if (!configured) {
            configure()
            configured = true
        }
        if (!session.running) session.startRunning()
    }

    fun stop() = dispatch_async(sessionQueue) {
        if (session.running) session.stopRunning()
    }

    fun setTorch(on: Boolean) = dispatch_async(sessionQueue) {
        val camera = device ?: return@dispatch_async
        if (!camera.hasTorch || !camera.lockForConfiguration(null)) return@dispatch_async
        camera.torchMode = if (on) AVCaptureTorchModeOn else AVCaptureTorchModeOff
        camera.unlockForConfiguration()
    }

    private fun configure() {
        val camera = device ?: return
        val input = AVCaptureDeviceInput.deviceInputWithDevice(camera, null) ?: return
        session.beginConfiguration()
        // Assez de pixels pour lire un petit tampon, comme l'analyse 1920 × 1080 d'Android.
        session.sessionPreset = AVCaptureSessionPreset1920x1080
        if (session.canAddInput(input)) session.addInput(input)
        val output = AVCaptureVideoDataOutput().apply {
            alwaysDiscardsLateVideoFrames = true
            setSampleBufferDelegate(delegate, analysisQueue)
        }
        if (session.canAddOutput(output)) session.addOutput(output)
        output.connectionWithMediaType(AVMediaTypeVideo)?.let { connection ->
            if (connection.isVideoRotationAngleSupported(PORTRAIT)) connection.videoRotationAngle = PORTRAIT
        }
        session.commitConfiguration()
        if (camera.lockForConfiguration(null)) {
            if (camera.isFocusModeSupported(AVCaptureFocusModeContinuousAutoFocus)) {
                camera.focusMode = AVCaptureFocusModeContinuousAutoFocus
            }
            // L'étiquette est à quelques centimètres : la mise au point cherche de près.
            if (camera.autoFocusRangeRestrictionSupported) {
                camera.autoFocusRangeRestriction = AVCaptureAutoFocusRangeRestrictionNear
            }
            camera.unlockForConfiguration()
        }
    }

    private fun analyze(sampleBuffer: CMSampleBufferRef) = autoreleasepool {
        val (viewWidth, viewHeight) = previewView.viewSize ?: return@autoreleasepool
        val pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) ?: return@autoreleasepool
        val mapper = AspectFillMapper(
            imageWidth = CVPixelBufferGetWidth(pixelBuffer).toDouble(),
            imageHeight = CVPixelBufferGetHeight(pixelBuffer).toDouble(),
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            pixelsPerPoint = pixelsPerPoint,
        )
        val handler = VNImageRequestHandler(cVPixelBuffer = pixelBuffer, options = emptyMap<Any?, Any?>())
        if (!handler.performRequests(listOf(textRequest, barcodeRequest), error = null)) return@autoreleasepool
        val lines = textRequest.results.orEmpty().filterIsInstance<VNRecognizedTextObservation>().mapNotNull { it.toOcrLine(mapper) }
        val barcodes = barcodeRequest.results.orEmpty().filterIsInstance<VNBarcodeObservation>().mapNotNull { it.payloadStringValue }
        onFrame(ScanFrame(barcodes, lines, lines.joinToString("\n") { it.text }.ifEmpty { null }))
    }

    private companion object {
        /** Rotation des images du capteur (paysage) pour l'app, tenue en portrait. */
        const val PORTRAIT = 90.0
    }
}

/** Une ligne Vision, avec ses mots : le même découpage que les lignes et éléments de ML Kit. */
@OptIn(ExperimentalForeignApi::class)
private fun VNRecognizedTextObservation.toOcrLine(mapper: AspectFillMapper): OcrLine? {
    val candidate = topCandidates(1u).firstOrNull() as? VNRecognizedText ?: return null
    val text = candidate.string
    if (text.isBlank()) return null
    val words = WORD.findAll(text).mapNotNull { match ->
        val range = NSMakeRange(match.range.first.toULong(), (match.range.last - match.range.first + 1).toULong())
        candidate.boundingBoxForRange(range, error = null)?.let { OcrWord(match.value, it.boundingBox.toRect(mapper)) }
    }.toList()
    return OcrLine(text = text, box = boundingBox.toRect(mapper), words = words)
}

@OptIn(ExperimentalForeignApi::class)
private fun CValue<CGRect>.toRect(mapper: AspectFillMapper): Rect =
    useContents { mapper.map(origin.x, origin.y, size.width, size.height) }

private val WORD = Regex("""\S+""")

@OptIn(ExperimentalForeignApi::class)
private class SampleBufferDelegate(
    private val analyze: (CMSampleBufferRef) -> Unit,
) : NSObject(), AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
    @ObjCSignatureOverride
    override fun captureOutput(output: AVCaptureOutput, didOutputSampleBuffer: CMSampleBufferRef?, fromConnection: AVCaptureConnection) {
        didOutputSampleBuffer?.let(analyze)
    }
}

/** Vue UIKit de l'aperçu, en plein cadre et rognée comme la PreviewView d'Android (FILL_CENTER). */
@OptIn(ExperimentalForeignApi::class)
internal class CameraPreviewView(session: AVCaptureSession) : UIView(frame = CGRectZero.readValue()) {
    private val previewLayer = AVCaptureVideoPreviewLayer(session = session).apply {
        videoGravity = AVLayerVideoGravityResizeAspectFill
    }

    /** Largeur et hauteur en points, lues par le fil d'analyse ; null avant la première mise en page. */
    @Volatile
    var viewSize: Pair<Double, Double>? = null
        private set

    init {
        backgroundColor = UIColor.blackColor
        layer.addSublayer(previewLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        previewLayer.frame = bounds
        CATransaction.commit()
        viewSize = bounds.useContents { (size.width to size.height).takeIf { it.first > 0 && it.second > 0 } }
    }
}
