package cz.kvalitacena.scanner

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/** Přemostění mezi CameraX ImageAnalysis a BarcodeScanner — jedno rozpoznání = jedno volání callbacku. */
class BarcodeAnalyzer(
  private val scanner: BarcodeScanner,
  private val onBarcodeDetected: (String) -> Unit,
) : ImageAnalysis.Analyzer {

  @Volatile
  private var paused = false

  fun pause() {
    paused = true
  }

  /** Znovu povolí detekci po návratu na obrazovku skenu — bez toho zůstane po prvním skenu trvale mrtvá. */
  fun resume() {
    paused = false
  }

  override fun analyze(image: ImageProxy) {
    if (paused) {
      image.close()
      return
    }
    val code = scanner.scan(image)
    if (code != null) {
      paused = true // dokud appka nenavigue pryč, nechceme spamovat další detekce
      onBarcodeDetected(code)
    }
  }
}
