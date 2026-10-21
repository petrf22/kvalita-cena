package cz.kvalitacena.ui.receipt.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharpnessTest {

  private fun image(width: Int, height: Int, pixel: (Int, Int) -> Int): ByteArray =
    ByteArray(width * height) { i -> pixel(i % width, i / width).toByte() }

  /** Svislé černobílé pruhy šířky [period] — „text" s ostrými hranami. */
  private fun stripes(width: Int, height: Int, period: Int) =
    image(width, height) { x, _ -> if ((x / period) % 2 == 0) 0 else 255 }

  /** Tytéž pruhy rozmazané prostým průměrem přes [radius] sousedů v řádku. */
  private fun blurredStripes(width: Int, height: Int, period: Int, radius: Int): ByteArray {
    val sharp = IntArray(width) { x -> if ((x / period) % 2 == 0) 0 else 255 }
    val row = IntArray(width) { x ->
      val from = maxOf(0, x - radius)
      val to = minOf(width - 1, x + radius)
      (from..to).sumOf { sharp[it] } / (to - from + 1)
    }
    return image(width, height) { x, _ -> row[x] }
  }

  @Test
  fun jednolityObrazekMaNulovouOstrost() {
    val flat = image(64, 48) { _, _ -> 128 }
    assertEquals(0.0, laplacianVariance(flat, 64, 48), 1e-9)
    assertFalse(isSharpEnough(laplacianVariance(flat, 64, 48)))
  }

  @Test
  fun ostreHranyPrekrociPrah() {
    val variance = laplacianVariance(stripes(96, 64, 4), 96, 64)
    assertTrue("ostré pruhy mají mít vysokou varianci, ne $variance", isSharpEnough(variance))
  }

  @Test
  fun rozmazaniVarianciSnizi() {
    val sharp = laplacianVariance(stripes(96, 64, 12), 96, 64)
    val blurred = laplacianVariance(blurredStripes(96, 64, 12, 10), 96, 64)
    assertTrue("rozmazání má ostrost snížit ($blurred >= $sharp)", blurred < sharp)
    assertFalse("silně rozmazané pruhy nemají projít prahem ($blurred)", isSharpEnough(blurred))
  }

  @Test
  fun respektujeDelkuRadkuBufferu() {
    val width = 32
    val height = 16
    val stride = 40
    val padded = ByteArray(stride * height)
    val plain = stripes(width, height, 3)
    for (y in 0 until height) System.arraycopy(plain, y * width, padded, y * stride, width)
    // Výplň za koncem řádku nesmí výsledek ovlivnit.
    for (y in 0 until height) for (x in width until stride) padded[y * stride + x] = 77
    assertEquals(
      laplacianVariance(plain, width, height),
      laplacianVariance(padded, width, height, rowStride = stride),
      1e-9,
    )
  }

  @Test
  fun maleObrazkyVratiNulu() {
    assertEquals(0.0, laplacianVariance(ByteArray(4), 2, 2), 0.0)
  }
}
