package cz.kvalitacena.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptPolicyTest {

  @Test
  fun vypadekSiteAServeruSeOpakuje() {
    assertEquals(UploadFailureAction.RETRY, uploadFailureAction(0, null))
    assertEquals(UploadFailureAction.RETRY, uploadFailureAction(503, null))
    assertEquals(UploadFailureAction.RETRY, uploadFailureAction(500, "INTERNAL"))
    assertEquals(UploadFailureAction.RETRY, uploadFailureAction(429, "TOO_MANY_REQUESTS"))
  }

  @Test
  fun chybejiciSouhlasCekaNaUzivatele() {
    assertEquals(UploadFailureAction.NEEDS_CONSENT, uploadFailureAction(400, CODE_CONSENT_REQUIRED))
  }

  @Test
  fun chybaKlientaSeNeopakuje() {
    assertEquals(UploadFailureAction.FAIL, uploadFailureAction(401, CODE_REQUIRES_LOGIN))
    assertEquals(UploadFailureAction.FAIL, uploadFailureAction(400, "RECEIPT_TOO_MANY_IMAGES"))
    assertEquals(UploadFailureAction.FAIL, uploadFailureAction(429, "RECEIPT_DAILY_LIMIT"))
    assertEquals(UploadFailureAction.FAIL, uploadFailureAction(413, null))
  }

  @Test
  fun souhlasPriPrvnimOdeslaniAPoZvyseniVerze() {
    assertTrue(consentRequired(acceptedVersion = 0, serverVersion = 1))
    assertFalse(consentRequired(acceptedVersion = 1, serverVersion = 1))
    assertTrue(consentRequired(acceptedVersion = 1, serverVersion = 2))
    assertFalse(consentRequired(acceptedVersion = 2, serverVersion = 1))
  }

  @Test
  fun notifikaceJenPriNarustu() {
    assertTrue(shouldNotify(0, 1))
    assertFalse(shouldNotify(1, 1))
    assertFalse(shouldNotify(2, 1))
    assertTrue(shouldNotify(1, 3))
  }

  @Test
  fun trasaZNotifikace() {
    assertEquals("receipt/42", notificationRoute(listOf("42")))
    assertEquals("receipts", notificationRoute(listOf("1", "2")))
    assertEquals("receipts", notificationRoute(emptyList()))
    assertTrue(isAllowedNotificationRoute("receipts"))
    assertTrue(isAllowedNotificationRoute("receipt/17"))
    assertFalse(isAllowedNotificationRoute("profile"))
    assertFalse(isAllowedNotificationRoute("receipt/17/../profile"))
  }
}
