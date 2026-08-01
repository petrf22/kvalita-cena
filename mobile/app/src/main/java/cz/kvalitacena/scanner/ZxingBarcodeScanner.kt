package cz.kvalitacena.scanner

import androidx.camera.core.ImageProxy
import zxingcpp.BarcodeReader

/**
 * ZXing-C++ (Apache-2.0) místo ML Kit — ML Kit je zdarma, ale proprietární a váže appku na
 * Google Play Services, což jde proti principu jen svobodných knihoven a proti tomu, že
 * o uživatelích nemá vědět třetí strana (viz plán založení projektu). Pokud by čtení
 * poškozených/rozmazaných kódů v praxi zlobilo, ML Kit zůstává záložní plán — proto je
 * skener schovaný za rozhraní {@link BarcodeScanner}.
 */
class ZxingBarcodeScanner : BarcodeScanner {
  private val reader = BarcodeReader()

  override fun scan(image: ImageProxy): String? =
    image.use { reader.read(it) }.firstOrNull()?.text
}
