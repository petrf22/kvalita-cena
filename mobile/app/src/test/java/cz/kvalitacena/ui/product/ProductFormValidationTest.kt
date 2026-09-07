package cz.kvalitacena.ui.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductFormValidationTest {

  @Test
  fun emptyWhenNeitherPhotoWasPicked() {
    assertTrue(pendingPhotoUploads<String>(null, null).isEmpty())
  }

  @Test
  fun sendsOnlyItemPhotoWhenLabelWasNotPicked() {
    assertEquals(listOf(PendingPhotoUpload("item-uri", "ITEM")), pendingPhotoUploads("item-uri", null))
  }

  @Test
  fun sendsOnlyLabelPhotoWhenItemWasNotPicked() {
    assertEquals(listOf(PendingPhotoUpload("label-uri", "LABEL")), pendingPhotoUploads(null, "label-uri"))
  }

  @Test
  fun putsItemPhotoFirstSoItBecomesTheMainPhoto() {
    assertEquals(
      listOf(PendingPhotoUpload("item-uri", "ITEM"), PendingPhotoUpload("label-uri", "LABEL")),
      pendingPhotoUploads("item-uri", "label-uri"),
    )
  }

  /**
   * Inverze `NetContentCalculator.validateUomMatchesUnitBase` — jiné odvození by skončilo
   * chybou UOM_MISMATCH až při uložení. „Jednotka nevyplněná" je jediný způsob, jak říct „cena
   * platí za balení"; dřív to byla volba „Kus", která se ptala na nezodpověditelné (rohlík:
   * kus, nebo hmotnost?).
   */
  @Test
  fun derivesTheUnitBaseTheServerAcceptsForTheChosenUnit() {
    assertEquals("MASS", unitBaseForUom("G"))
    assertEquals("MASS", unitBaseForUom("KG"))
    assertEquals("VOLUME", unitBaseForUom("ML"))
    assertEquals("VOLUME", unitBaseForUom("L"))
    assertEquals("COUNT", unitBaseForUom(null))
  }

  /** Past: pole gramáže je bez vybrané jednotky skryté, ale stav si drží, co uživatel zadal
   *  předtím. 60 s PCS uloží balení o 60 kusech, net_content_base má zůstat 1. */
  @Test
  fun dropsQuantityLeftOverFromBeforeTheUnitWasCleared() {
    assertEquals(
      VisibleNetContent("COUNT", null, "PCS", false),
      visibleNetContent(60.0, null, isVariableWeight = false),
    )
  }

  @Test
  fun dropsQuantityForVariableWeightGoodsAndSendsTheBaseUnit() {
    assertEquals(
      VisibleNetContent("MASS", null, "KG", true),
      visibleNetContent(60.0, "G", isVariableWeight = true),
    )
  }

  /** Formulář u váhového zboží jednotku neukazuje, takže rozlévané víno se nesmí při každé
   *  úpravě tiše překlopit z objemu na hmotnost. */
  @Test
  fun keepsAStoredVolumeBaseForVariableWeightGoods() {
    assertEquals(
      VisibleNetContent("VOLUME", null, "L", true),
      visibleNetContent(null, null, isVariableWeight = true, storedUnitBase = "VOLUME"),
    )
  }

  /**
   * Past, kvůli které clearNetContent vzniklo: netContentValue = null v patchi znamená
   * "nezměněno", takže server sáhne po staré gramáži a u kusového zboží ji spočítá jako počet
   * (250 g → 250 ks). Vyprázdnění se proto musí říct vlastním příznakem.
   */
  @Test
  fun asksServerToClearQuantityOnlyWhenTheFormNoLongerHasOne() {
    val defaults = ProductFormDefaults(
      name = "Rama Klasik", names = mapOf("cs" to "Rama Klasik"), brandName = "Rama",
      categoryId = "4", unitBase = "MASS", netContentValue = 250.0, netContentUom = "G",
      piecesInPack = null, isVariableWeight = false,
    )
    fun clearFor(value: Double?, uom: String?, defs: ProductFormDefaults) =
      buildUpdateProductInput(
        name = defs.name, nameLang = "cs", names = emptyList(), brandName = defs.brandName,
        categoryId = "4",
        netContent = visibleNetContent(value, uom, isVariableWeight = false),
        piecesInPack = null, defaults = defs,
      ).clearNetContent

    assertTrue(clearFor(null, null, defaults))
    assertFalse(clearFor(300.0, "G", defaults))
    assertFalse(clearFor(null, null, defaults.copy(
      unitBase = "COUNT", netContentValue = null, netContentUom = null,
    )))
  }

  @Test
  fun derivesTheUnitBaseAndPassesAVisibleQuantityThrough() {
    assertEquals(
      VisibleNetContent("MASS", 60.0, "G", false),
      visibleNetContent(60.0, "G", isVariableWeight = false),
    )
    assertEquals(
      VisibleNetContent("VOLUME", 500.0, "ML", false),
      visibleNetContent(500.0, "ML", isVariableWeight = false),
    )
  }
}
