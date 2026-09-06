package cz.kvalitacena.ui.product

import org.junit.Assert.assertEquals
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

  /** Nabídnout u hmotnosti litry by skončilo chybou UOM_MISMATCH až při uložení. */
  @Test
  fun offersOnlyUnitsTheServerAcceptsForTheGivenUnitBase() {
    assertEquals(listOf("G", "KG"), netContentUomOptions("MASS"))
    assertEquals(listOf("ML", "L"), netContentUomOptions("VOLUME"))
    assertEquals(listOf("PCS"), netContentUomOptions("COUNT"))
  }

  /** Výchozí je menší jednotka — na obalu bývá „60 g", ne „0,06 kg". */
  @Test
  fun keepsTheCurrentUnitWhileItFitsAndFallsBackToTheSmallerOne() {
    assertEquals("KG", netContentUomFor("MASS", "KG"))
    assertEquals("ML", netContentUomFor("VOLUME", "G"))
    assertEquals("G", netContentUomFor("MASS", null))
    assertEquals("PCS", netContentUomFor("COUNT", "KG"))
  }

  /** Past: pole gramáže je u kusového zboží skryté, ale stav si drží, co uživatel zadal ještě
   *  u hmotnosti. 60 s PCS uloží balení o 60 kusech, net_content_base má zůstat 1. */
  @Test
  fun dropsQuantityLeftOverFromBeforeTheSwitchToPieceGoods() {
    assertEquals(
      VisibleNetContent(null, "PCS", false),
      visibleNetContent("COUNT", 60.0, "G", isVariableWeight = true),
    )
  }

  @Test
  fun dropsQuantityForVariableWeightGoodsButKeepsTheUnit() {
    assertEquals(
      VisibleNetContent(null, "G", true),
      visibleNetContent("MASS", 60.0, "G", isVariableWeight = true),
    )
  }

  @Test
  fun passesVisibleQuantityThroughUntouchedAndRepairsAStaleUnit() {
    assertEquals(
      VisibleNetContent(60.0, "G", false),
      visibleNetContent("MASS", 60.0, "G", isVariableWeight = false),
    )
    assertEquals(
      VisibleNetContent(500.0, "ML", false),
      visibleNetContent("VOLUME", 500.0, "G", isVariableWeight = false),
    )
  }
}
