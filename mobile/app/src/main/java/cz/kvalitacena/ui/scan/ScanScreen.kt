package cz.kvalitacena.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import cz.kvalitacena.scanner.BarcodeAnalyzer
import cz.kvalitacena.scanner.ZxingBarcodeScanner

/** Obrazovka 1/3 flow "sken → cena → výběr provozovny → odeslání" (viz plán projektu). */
@Composable
fun ScanScreen(onBarcodeDetected: (String) -> Unit) {
  val context = LocalContext.current
  var hasCameraPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED,
    )
  }
  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted -> hasCameraPermission = granted }

  LaunchedEffect(Unit) {
    if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
  }

  if (hasCameraPermission) {
    CameraPreview(onBarcodeDetected = onBarcodeDetected)
  } else {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
      Text(
        "Pro skenování čárových kódů appka potřebuje přístup ke kameře.",
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}

@Composable
private fun CameraPreview(onBarcodeDetected: (String) -> Unit) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val scanner = remember { ZxingBarcodeScanner() }
  val analyzer = remember { BarcodeAnalyzer(scanner, onBarcodeDetected) }

  AndroidView(
    modifier = Modifier.fillMaxSize(),
    factory = { ctx ->
      val previewView = PreviewView(ctx)
      val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
      cameraProviderFuture.addListener(
        {
          val cameraProvider = cameraProviderFuture.get()
          val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
          }
          val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(ContextCompat.getMainExecutor(ctx), analyzer) }

          cameraProvider.unbindAll()
          cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageAnalysis,
          )
        },
        ContextCompat.getMainExecutor(ctx),
      )
      previewView
    },
  )
}
