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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import cz.kvalitacena.R
import cz.kvalitacena.scanner.BarcodeAnalyzer
import cz.kvalitacena.scanner.ZxingBarcodeScanner

/** Záložka "Sken" — obrazovky 1/3 flow "sken → cena → výběr provozovny → odeslání" (viz plán projektu). */
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
        stringResource(R.string.scan_camera_permission_hint),
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}

@Composable
private fun CameraPreview(onBarcodeDetected: (String) -> Unit) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val scanner = remember { ZxingBarcodeScanner() }
  val analyzer = remember { BarcodeAnalyzer(scanner, onBarcodeDetected) }
  val previewView = remember { PreviewView(context) }

  // Vázáno explicitně přes DisposableEffect, ne jen implicitně přes lifecycleOwner uvnitř
  // AndroidView.factory — s bottom navigation musí kamera zhasnout SPOLEHLIVĚ při přepnutí
  // záložky, ne se jen spolehnout na to, že NavBackStackEntry lifecycle klesne včas.
  DisposableEffect(lifecycleOwner) {
    analyzer.resume() // po návratu na tuhle záložku nesmí zůstat "mrtvá" po prvním skenu
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener(
      {
        val cameraProvider = cameraProviderFuture.get()
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val imageAnalysis = ImageAnalysis.Builder()
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .build()
          .also { it.setAnalyzer(ContextCompat.getMainExecutor(context), analyzer) }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
      },
      ContextCompat.getMainExecutor(context),
    )

    onDispose {
      // Future je v tuhle chvíli už vyřešený (kamera se stihla svázat výše), .get() tu
      // neblokuje — pojistka navíc, appka nesmí dál žrát baterii po přepnutí záložky.
      runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
    }
  }

  AndroidView(modifier = Modifier.fillMaxSize(), factory = { previewView })
}
