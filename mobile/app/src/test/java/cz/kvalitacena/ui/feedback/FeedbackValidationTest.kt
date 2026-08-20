package cz.kvalitacena.ui.feedback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackValidationTest {

  @Test
  fun blankMessageIsInvalid() {
    assertFalse(isFeedbackMessageValid(""))
    assertFalse(isFeedbackMessageValid("   "))
    assertTrue(isFeedbackMessageValid("appka mi spadla"))
  }

  @Test
  fun blankContactEmailIsValid() {
    assertTrue(isContactEmailValid(""))
    assertTrue(isContactEmailValid("   "))
  }

  @Test
  fun contactEmailMustLookLikeAnEmail() {
    assertTrue(isContactEmailValid("test@example.com"))
    assertFalse(isContactEmailValid("not-an-email"))
    assertFalse(isContactEmailValid("test@"))
  }

  @Test
  fun formIsValidOnlyWithMessageAndPlausibleEmail() {
    assertTrue(isFeedbackFormValid("test", ""))
    assertTrue(isFeedbackFormValid("test", "test@example.com"))
    assertFalse(isFeedbackFormValid("", ""))
    assertFalse(isFeedbackFormValid("test", "not-an-email"))
  }
}
