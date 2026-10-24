package cz.kvalitacena.ui.receipt.capture

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.kvalitacena.AppContainer
import cz.kvalitacena.BuildConfig
import cz.kvalitacena.R
import cz.kvalitacena.crash.AppLog
import cz.kvalitacena.ui.receipt.OfflineBanner
import cz.kvalitacena.ui.receipt.ReceiptLoginRequired
import cz.kvalitacena.ui.receipt.ReceiptStateMessage
import cz.kvalitacena.ui.theme.Spacing
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Rozlišení analyzovaných snímků — z nich se ukládají i automatické díly (šířka účtenky ~1000 px). */
private val ANALYSIS_RESOLUTION = Size(1600, 1200)

/**
 * Snímání účtenky (docs/rozvoj.md, „Sběr snímků na klientovi"): živý náhled s rámečkem nalezené
 * účtenky, průběhem k dalšímu dílu a pokynem, díly se ukládají samy. Před prvním dílem ukazuje
 * vodítko pro horní okraj ([StartGuideOverlay]) a odpočet (docs/ai.md, `START_*`); při prvním
 * snímání se sám otevře návod ([CaptureTipsDialog]). Ruční spoušť funguje vždy — i když detekce
 * nic nenajde, nebo v emulátoru s virtuální kamerou. V debug buildu jde díly načíst i z galerie
 * (ladění bez fyzické účtenky).
 */
@Composable
fun ReceiptCaptureScreen(
  retakeIndex: Int?,
  onDone: () -> Unit,
  onOpenAccount: () -> Unit,
) {
  val sessionKnown by AppContainer.authRepository.sessionKnown.collectAsState()
  val isLoggedIn by AppContainer.authRepository.isLoggedIn.collectAsState()
  if (!sessionKnown) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    return
  }
  if (!isLoggedIn) {
    ReceiptLoginRequired(onOpenAccount)
    return
  }

  val viewModel: ReceiptCaptureViewModel = viewModel(
    factory = viewModelFactory {
      initializer {
        ReceiptCaptureViewModel(AppContainer.receiptDraft, retakeIndex, AppContainer.receiptPrefs.captureStartDelaySeconds)
      }
    },
  )
  val context = LocalContext.current
  var hasCameraPermission by remember {
    mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
  }
  val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    hasCameraPermission = it
  }
  val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
    viewModel.importImages(context, uris)
  }
  val online by AppContainer.networkMonitor.online.collectAsState()
  val draft by viewModel.draft.collectAsState()
  var showTips by rememberSaveable {
    mutableStateOf(!viewModel.isRetake && !AppContainer.receiptPrefs.captureTipsSeen)
  }
  // Návod až nad běžící kamerou — přes systémový dotaz na oprávnění by ho nikdo nečetl.
  val tipsVisible = showTips && hasCameraPermission
  LaunchedEffect(tipsVisible) {
    viewModel.analyzer.paused = tipsVisible
  }
  if (tipsVisible) {
    CaptureTipsDialog(
      onDismiss = {
        AppContainer.receiptPrefs.captureTipsSeen = true
        showTips = false
      },
    )
  }

  LaunchedEffect(Unit) {
    if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
  }
  LaunchedEffect(viewModel.retakeDone) {
    if (viewModel.retakeDone) onDone()
  }

  if (!viewModel.openCvAvailable) {
    ReceiptStateMessage(
      title = stringResource(R.string.receipt_capture_unavailable_title),
      text = stringResource(R.string.receipt_capture_unavailable_text),
    )
    return
  }

  val importFromGallery: (() -> Unit)? = if (BuildConfig.DEBUG) {
    { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
  } else {
    null
  }

  Column(Modifier.fillMaxSize()) {
    if (!online) OfflineBanner(stringResource(R.string.receipt_capture_offline))
    Box(Modifier.weight(1f).fillMaxWidth()) {
      when {
        !hasCameraPermission -> ReceiptStateMessage(
          title = stringResource(R.string.receipt_capture_permission_title),
          text = stringResource(R.string.receipt_capture_permission_text),
          actionLabel = stringResource(R.string.receipt_capture_permission_action),
          onAction = { permissionLauncher.launch(Manifest.permission.CAMERA) },
          secondaryLabel = importFromGallery?.let { stringResource(R.string.receipt_capture_import) },
          onSecondary = importFromGallery,
        )
        viewModel.cameraError -> ReceiptStateMessage(
          title = stringResource(R.string.receipt_capture_camera_error_title),
          text = stringResource(R.string.receipt_capture_camera_error_text),
          actionLabel = stringResource(R.string.common_retry),
          onAction = { viewModel.cameraError = false },
          secondaryLabel = importFromGallery?.let { stringResource(R.string.receipt_capture_import) },
          onSecondary = importFromGallery,
        )
        else -> CameraWithOverlay(viewModel)
      }
    }
    CaptureControls(
      viewModel = viewModel,
      partCount = draft.parts.size,
      canShoot = hasCameraPermission && !viewModel.cameraError,
      onImport = importFromGallery,
      onShowTips = { showTips = true },
      onDone = onDone,
    )
  }
}

/**
 * Náhled kamery s rámečkem účtenky, pokynem a průběhem — před prvním dílem s vodítkem a průběhem
 * odpočtu, potom s průběhem k dalšímu dílu.
 */
@Composable
private fun CameraWithOverlay(viewModel: ReceiptCaptureViewModel) {
  val frame by viewModel.frame.collectAsState()
  val primary = MaterialTheme.colorScheme.primary
  val strokeWidth = with(LocalDensity.current) { 3.dp.toPx() }
  val beforeStart = (frame?.partCount ?: 0) == 0

  // Konec odpočtu = uložený první díl: zavibrování řekne „teď posouvejte", i když se uživatel
  // dívá na účtenku, ne na displej. Jen přechod 0 → 1 z analyzátoru, ne návrat do rozdělaného
  // konceptu (první snímek pak hlásí rovnou uložené díly).
  val haptic = LocalHapticFeedback.current
  var lastPartCount by remember { mutableStateOf<Int?>(null) }
  val partCount = frame?.partCount
  LaunchedEffect(partCount) {
    if (partCount == null) return@LaunchedEffect
    if (lastPartCount == 0 && partCount == 1) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
    lastPartCount = partCount
  }

  Box(Modifier.fillMaxSize()) {
    ReceiptCameraPreview(viewModel)
    if (beforeStart && !viewModel.isRetake) {
      StartGuideOverlay(receiptFound = frame?.quad != null, modifier = Modifier.fillMaxSize())
    }
    Canvas(Modifier.fillMaxSize()) {
      val current = frame ?: return@Canvas
      val quad = current.quad ?: return@Canvas
      val points = quad.points.map {
        imageToFillCenterView(it, current.frameWidth, current.frameHeight, size.width.toDouble(), size.height.toDouble())
      }
      val path = Path().apply {
        moveTo(points[0].x.toFloat(), points[0].y.toFloat())
        points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
        close()
      }
      drawPath(path, primary, style = Stroke(width = strokeWidth))
      // Roh účtenky zvýrazněný tečkou — je hned vidět, že se rámeček drží papíru.
      points.forEach { drawCircle(primary, radius = strokeWidth * 2, center = Offset(it.x.toFloat(), it.y.toFloat())) }
    }
    Column(
      Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(Spacing.lg),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
      ) {
        Text(
          hintText(frame?.hint ?: CaptureHint.FIND_RECEIPT, viewModel.isRetake),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
        )
      }
      val current = frame
      when {
        current == null -> Unit
        current.partCount == 0 && current.startCountdownRunning ->
          LinearProgressIndicator(progress = { current.startProgress }, modifier = Modifier.fillMaxWidth())
        !viewModel.isRetake && current.partCount > 0 ->
          LinearProgressIndicator(progress = { current.progress }, modifier = Modifier.fillMaxWidth())
      }
    }
  }
}

@Composable
private fun hintText(hint: CaptureHint, retake: Boolean): String = stringResource(
  when (hint) {
    CaptureHint.FIND_RECEIPT -> if (retake) R.string.receipt_capture_hint_retake else R.string.receipt_capture_hint_find
    CaptureHint.HOLD_STILL -> R.string.receipt_capture_hint_hold_still
    CaptureHint.STARTING -> R.string.receipt_capture_hint_starting
    CaptureHint.MOVE_DOWN -> R.string.receipt_capture_hint_move_down
    CaptureHint.MOVE_BACK_UP -> R.string.receipt_capture_hint_move_back
    CaptureHint.LIMIT_REACHED -> R.string.receipt_capture_hint_limit
  },
)

@Composable
private fun CaptureControls(
  viewModel: ReceiptCaptureViewModel,
  partCount: Int,
  canShoot: Boolean,
  onImport: (() -> Unit)?,
  onShowTips: () -> Unit,
  onDone: () -> Unit,
) {
  Column(
    Modifier.fillMaxWidth().padding(Spacing.lg),
    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    viewModel.error?.let {
      Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (!viewModel.isRetake) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          stringResource(R.string.receipt_capture_parts, partCount),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.weight(1f),
        )
        // Návod patří k začátku, smazání dílu až k rozdělanému snímání — střídají se na jednom
        // místě, ať se řádek na úzkém displeji nepřeplní.
        if (partCount > 0) {
          TextButton(onClick = viewModel::undoLastPart, enabled = !viewModel.processing) {
            Text(stringResource(R.string.receipt_capture_undo))
          }
        } else {
          TextButton(onClick = onShowTips) {
            Text(stringResource(R.string.receipt_capture_tips_open))
          }
        }
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
      OutlinedButton(
        onClick = { ManualShutter.trigger?.invoke() },
        enabled = canShoot && !viewModel.processing,
        modifier = Modifier.weight(1f),
      ) {
        if (viewModel.processing) {
          CircularProgressIndicator(Modifier.size(20.dp))
        } else {
          Text(stringResource(R.string.receipt_capture_shutter))
        }
      }
      if (!viewModel.isRetake) {
        Button(onClick = onDone, enabled = partCount > 0 && !viewModel.processing, modifier = Modifier.weight(1f)) {
          Text(stringResource(R.string.receipt_capture_done))
        }
      }
    }
    if (onImport != null) {
      TextButton(onClick = onImport, enabled = !viewModel.processing) {
        Text(stringResource(R.string.receipt_capture_import))
      }
    }
  }
}

/**
 * Most mezi tlačítkem spouště a `ImageCapture` svázaným v [ReceiptCameraPreview] — use case
 * žije jen v kompozici náhledu, tlačítko je mimo něj.
 */
private object ManualShutter {
  var trigger: (() -> Unit)? = null
}

/**
 * CameraX: náhled, analýza (živá detekce a automatické díly) a `ImageCapture` pro ruční spoušť.
 * Když zařízení všechny tři use case naráz neunese, zkusí se náhled se spouští bez analýzy —
 * ruční spoušť musí zůstat.
 */
@Composable
private fun ReceiptCameraPreview(viewModel: ReceiptCaptureViewModel) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val previewView = remember {
    PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
  }
  DisposableEffect(lifecycleOwner) {
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    val providerFuture = ProcessCameraProvider.getInstance(context)
    var imageCapture: ImageCapture? = null
    val boundUseCases = mutableListOf<UseCase>()
    providerFuture.addListener(
      {
        val provider = providerFuture.get()
        val resolution = ResolutionSelector.Builder()
          .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
          .setResolutionStrategy(
            ResolutionStrategy(ANALYSIS_RESOLUTION, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
          )
          .build()
        val aspect = ResolutionSelector.Builder()
          .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
          .build()
        val preview = Preview.Builder().setResolutionSelector(aspect).build()
          .also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
          .setResolutionSelector(resolution)
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .build()
          .also { it.setAnalyzer(analysisExecutor, viewModel.analyzer) }
        val capture = ImageCapture.Builder()
          .setResolutionSelector(aspect)
          .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
          .build()
        imageCapture = capture
        provider.unbindAll()
        val bound = runCatching {
          provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture)
            .also { boundUseCases += listOf(preview, analysis, capture) }
        }.recoverCatching {
          AppLog.e("Camera cannot bind analysis together with capture, falling back to manual shutter", it)
          provider.unbindAll()
          provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            .also { boundUseCases += listOf(preview, capture) }
        }
        if (bound.isFailure) {
          AppLog.e("Camera could not be bound", bound.exceptionOrNull())
          viewModel.cameraError = true
        }
      },
      ContextCompat.getMainExecutor(context),
    )
    ManualShutter.trigger = {
      imageCapture?.takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
          override fun onCaptureSuccess(image: ImageProxy) {
            val rotation = image.imageInfo.rotationDegrees
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            image.close()
            viewModel.onManualPhoto(bytes, rotation)
          }

          override fun onError(exception: ImageCaptureException) {
            AppLog.e("Manual receipt capture failed", exception)
          }
        },
      )
    }

    onDispose {
      ManualShutter.trigger = null
      // Jen vlastní use case — onDispose může doběhnout až po svázání kamery na další
      // obrazovce (sken čárového kódu) a unbindAll by jí kameru vzal.
      runCatching { ProcessCameraProvider.getInstance(context).get().unbind(*boundUseCases.toTypedArray()) }
      analysisExecutor.shutdown()
    }
  }

  AndroidView(modifier = Modifier.fillMaxSize(), factory = { previewView })
}
