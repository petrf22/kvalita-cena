package cz.kvalitacena.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import cz.kvalitacena.R
import cz.kvalitacena.scanner.BarcodeAnalyzer
import cz.kvalitacena.scanner.ZxingBarcodeScanner

/** Záložka "Sken" — obrazovky 1/3 flow "sken → cena → výběr provozovny → odeslání". */
@Composable
fun ScanScreen(onBarcodeDetected: (String) -> Unit) {
  val context = LocalContext.current
  var hasCameraPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED,
    )
  }
  var manualEntryVisible by rememberSaveable { mutableStateOf(false) }
  var manualCode by rememberSaveable { mutableStateOf("") }
  var torchAvailable by remember { mutableStateOf(false) }
  var torchEnabled by rememberSaveable { mutableStateOf(false) }
  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted -> hasCameraPermission = granted }

  LaunchedEffect(Unit) {
    if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
  }

  if (hasCameraPermission) {
    Box(modifier = Modifier.fillMaxSize()) {
      CameraPreview(
        torchEnabled = torchEnabled,
        onTorchAvailabilityChange = { torchAvailable = it },
        onBarcodeDetected = onBarcodeDetected,
      )
      Surface(
        modifier = Modifier.align(Alignment.TopCenter).padding(20.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.7f),
      ) {
        Text(
          stringResource(R.string.scan_instruction),
          color = Color.White,
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
      }
      Box(
        modifier = Modifier
          .align(Alignment.Center)
          .width(280.dp)
          .height(160.dp)
          .border(3.dp, Color.White, RoundedCornerShape(12.dp)),
      )
      Row(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
      ) {
        if (torchAvailable) {
          Button(onClick = { torchEnabled = !torchEnabled }) {
            Text(stringResource(if (torchEnabled) R.string.scan_flashlight_off else R.string.scan_flashlight_on))
          }
        }
        Button(onClick = { manualEntryVisible = true }) {
          Text(stringResource(R.string.scan_manual_entry))
        }
      }
    }
  } else {
    Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Text(
        stringResource(R.string.scan_camera_permission_hint),
        style = MaterialTheme.typography.bodyMedium,
      )
      OutlinedButton(
        onClick = { manualEntryVisible = true },
        modifier = Modifier.padding(top = 16.dp),
      ) {
        Text(stringResource(R.string.scan_manual_entry))
      }
    }
  }

  if (manualEntryVisible) {
    AlertDialog(
      onDismissRequest = { manualEntryVisible = false },
      title = { Text(stringResource(R.string.scan_manual_title)) },
      text = {
        OutlinedTextField(
          value = manualCode,
          onValueChange = { input -> if (input.all(Char::isDigit) && input.length <= 14) manualCode = input },
          label = { Text(stringResource(R.string.scan_manual_label)) },
          supportingText = if (manualCode.isNotEmpty() && !isManualBarcodeValid(manualCode)) {
            { Text(stringResource(R.string.scan_manual_error)) }
          } else null,
          isError = manualCode.isNotEmpty() && !isManualBarcodeValid(manualCode),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
        )
      },
      confirmButton = {
        TextButton(
          enabled = isManualBarcodeValid(manualCode),
          onClick = {
            val code = manualCode.trim()
            manualEntryVisible = false
            manualCode = ""
            onBarcodeDetected(code)
          },
        ) { Text(stringResource(R.string.scan_manual_submit)) }
      },
      dismissButton = {
        TextButton(onClick = { manualEntryVisible = false }) {
          Text(stringResource(R.string.common_cancel))
        }
      },
    )
  }
}

@Composable
private fun CameraPreview(
  torchEnabled: Boolean,
  onTorchAvailabilityChange: (Boolean) -> Unit,
  onBarcodeDetected: (String) -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val scanner = remember { ZxingBarcodeScanner() }
  val analyzer = remember { BarcodeAnalyzer(scanner, onBarcodeDetected) }
  val previewView = remember { PreviewView(context) }
  var boundCamera by remember { mutableStateOf<Camera?>(null) }

  LaunchedEffect(torchEnabled, boundCamera) {
    boundCamera?.cameraControl?.enableTorch(torchEnabled)
  }

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
        boundCamera = cameraProvider.bindToLifecycle(
          lifecycleOwner,
          CameraSelector.DEFAULT_BACK_CAMERA,
          preview,
          imageAnalysis,
        )
        onTorchAvailabilityChange(boundCamera?.cameraInfo?.hasFlashUnit() == true)
      },
      ContextCompat.getMainExecutor(context),
    )

    onDispose {
      boundCamera?.cameraControl?.enableTorch(false)
      boundCamera = null
      onTorchAvailabilityChange(false)
      // Future je v tuhle chvíli už vyřešený (kamera se stihla svázat výše), .get() tu
      // neblokuje — pojistka navíc, appka nesmí dál žrát baterii po přepnutí záložky.
      runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
    }
  }

  AndroidView(modifier = Modifier.fillMaxSize(), factory = { previewView })
}
