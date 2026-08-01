package cz.kvalitacena.scanner

import androidx.camera.core.ImageProxy

/**
 * Abstrakce nad čtením čárových kódů — implementace jde vyměnit (ZXing ⇄ ML Kit) jednou
 * třídou, viz plán založení projektu a ZxingBarcodeScanner. Implementace přebírá vlastnictví
 * {@code image} a je zodpovědná za jeho uzavření (typicky přes `image.use { ... }`).
 */
fun interface BarcodeScanner {
  fun scan(image: ImageProxy): String?
}
