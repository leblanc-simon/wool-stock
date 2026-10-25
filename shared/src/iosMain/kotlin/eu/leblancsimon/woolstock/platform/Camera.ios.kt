package eu.leblancsimon.woolstock.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.UIKitView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import eu.leblancsimon.woolstock.scan.VisionCamera
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun CameraPreview(torchOn: Boolean, onFrame: (ScanFrame) -> Unit, modifier: Modifier) {
    val currentOnFrame by rememberUpdatedState(onFrame)
    val camera = remember { VisionCamera { currentOnFrame(it) } }
    // Les boîtes Vision sont en points ; DebugOverlay dessine en pixels.
    camera.pixelsPerPoint = LocalDensity.current.density.toDouble()
    DisposableEffect(camera) {
        camera.start()
        onDispose { camera.stop() }
    }
    LaunchedEffect(torchOn) { camera.setTorch(torchOn) }
    UIKitView(factory = { camera.previewView }, modifier = modifier)
}

@Composable
actual fun rememberCameraPermission(): CameraPermission {
    var status by remember { mutableStateOf(cameraStatus()) }
    // Au retour des Réglages, l'autorisation a pu changer.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = cameraStatus() }
    val ask: () -> Unit = {
        AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { _ ->
            dispatch_async(dispatch_get_main_queue()) { status = cameraStatus() }
        }
    }
    LaunchedEffect(Unit) {
        if (status == AVAuthorizationStatusNotDetermined) ask()
    }
    return CameraPermission(
        granted = status == AVAuthorizationStatusAuthorized,
        // iOS ne pose la question qu'une fois : un refus ne se lève ensuite que dans les Réglages.
        request = { if (status == AVAuthorizationStatusNotDetermined) ask() else openAppSettings() },
        openSettings = ::openAppSettings,
    )
}

private fun cameraStatus() = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)

private fun openAppSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
}
