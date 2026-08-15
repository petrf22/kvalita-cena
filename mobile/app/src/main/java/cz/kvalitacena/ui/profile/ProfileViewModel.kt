package cz.kvalitacena.ui.profile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.R
import cz.kvalitacena.auth.AuthRepository
import cz.kvalitacena.network.GraphQlClient
import cz.kvalitacena.network.MediaClient
import cz.kvalitacena.network.Photo
import cz.kvalitacena.network.Profile
import cz.kvalitacena.network.ProfileFieldAudience
import cz.kvalitacena.network.UpdateProfileInput
import cz.kvalitacena.ui.common.ALLOWED_PHOTO_MIME_TYPES
import cz.kvalitacena.ui.common.MAX_PHOTO_BYTES
import cz.kvalitacena.ui.common.UiText
import cz.kvalitacena.ui.common.toUiText
import kotlinx.coroutines.launch

/**
 * Editace profilu přihlášeného uživatele — jméno, příjmení, přezdívka, telefon, kontaktní
 * e-mail (nepovinné, šifrované), avatar, viditelnost (docs/soukromi.md, "Profil uživatele a
 * viditelnost"). Výchozí viditelnost je ANONYMOUS; u PUBLIC/FRIENDS si uživatel po jednotlivých
 * polích vybere, co uvidí veřejnost a co jen (zatím neimplementovaní) přátelé.
 *
 * Přihlašovací e-mail je needitovatelný přímo tady — mění se přes samostatný OTP tok
 * ([AuthRepository.requestEmailChange]/[AuthRepository.confirmEmailChange]), aby si uživatel
 * překlepem nezamkl účet. Avatar jde přes REST multipart ([MediaClient.uploadAvatar]), zbytek
 * přes GraphQL (`updateProfile`/`deleteAvatar`) — stejné rozdělení jako u fotek zboží/obchodu.
 * Webový protějšek: frontend features/profile/profile-page.ts.
 */
class ProfileViewModel(
  private val graphQlClient: GraphQlClient,
  private val mediaClient: MediaClient,
  private val authRepository: AuthRepository,
) : ViewModel() {

  var loading by mutableStateOf(true)
    private set
  var loadError by mutableStateOf<UiText?>(null)
    private set

  var firstName by mutableStateOf("")
  var lastName by mutableStateOf("")
  var displayName by mutableStateOf("")
  var phone by mutableStateOf("")
  var contactEmail by mutableStateOf("")
  var loginEmail by mutableStateOf("")
    private set
  var visibility by mutableStateOf("ANONYMOUS")
  var avatar by mutableStateOf<Photo?>(null)
    private set

  var publicFields by mutableStateOf<Set<String>>(emptySet())
    private set
  var friendsFields by mutableStateOf<Set<String>>(emptySet())
    private set

  var saving by mutableStateOf(false)
    private set
  var saveError by mutableStateOf<UiText?>(null)
    private set
  var saveSuccess by mutableStateOf(false)
    private set

  var avatarUploading by mutableStateOf(false)
    private set
  var avatarError by mutableStateOf<UiText?>(null)
    private set

  // --- Změna přihlašovacího e-mailu (samostatný modal/tok) ---
  var emailChangeVisible by mutableStateOf(false)
    private set
  var emailChangeStep by mutableStateOf("email")
    private set
  var newEmail by mutableStateOf("")
  var emailChangeCode by mutableStateOf("")
  private var emailChangeChallengeUid: String? = null
  var emailChangeLoading by mutableStateOf(false)
    private set
  var emailChangeError by mutableStateOf<UiText?>(null)
    private set
  var emailChangeSuccess by mutableStateOf(false)
    private set

  init {
    load()
  }

  private fun load() {
    viewModelScope.launch {
      try {
        // Appka odpaluje obnovení přihlášení při startu bez čekání (T0 anonymní chod tím
        // není podmíněný, viz MainActivity.kt) — profil ale přihlášení vždy vyžaduje, takže
        // by se bez tohohle čekání mohl zeptat jako anonym dřív, než refresh doběhne.
        authRepository.awaitInitialRefresh()
        val viewer = graphQlClient.me()
        viewer?.profile?.let { applyProfile(it) }
        if (viewer?.profile == null) loadError = UiText.Res(R.string.profile_load_failed)
      } catch (e: Exception) {
        loadError = e.toUiText()
      } finally {
        loading = false
      }
    }
  }

  private fun applyProfile(profile: Profile) {
    firstName = profile.firstName.orEmpty()
    lastName = profile.lastName.orEmpty()
    phone = profile.phone.orEmpty()
    contactEmail = profile.contactEmail.orEmpty()
    loginEmail = profile.loginEmail
    visibility = profile.visibility
    avatar = profile.avatar
    publicFields = profile.visibleFields.filter { it.audience == "PUBLIC" }.map { it.field }.toSet()
    friendsFields = profile.visibleFields.filter { it.audience == "FRIENDS" }.map { it.field }.toSet()
  }

  fun isChecked(field: String, audience: String): Boolean =
    if (audience == "PUBLIC") field in publicFields else field in friendsFields

  fun toggleField(field: String, audience: String) {
    if (audience == "PUBLIC") {
      publicFields = if (field in publicFields) publicFields - field else publicFields + field
    } else {
      friendsFields = if (field in friendsFields) friendsFields - field else friendsFields + field
    }
  }

  fun save() {
    saving = true
    saveError = null
    saveSuccess = false
    val visibleFields = publicFields.map { ProfileFieldAudience(it, "PUBLIC") } +
      friendsFields.map { ProfileFieldAudience(it, "FRIENDS") }
    val input = UpdateProfileInput(
      firstName = firstName.trim().ifBlank { null },
      clearFirstName = firstName.trim().isEmpty(),
      lastName = lastName.trim().ifBlank { null },
      clearLastName = lastName.trim().isEmpty(),
      displayName = displayName.trim().ifBlank { null },
      clearDisplayName = displayName.trim().isEmpty(),
      phone = phone.trim().ifBlank { null },
      clearPhone = phone.trim().isEmpty(),
      contactEmail = contactEmail.trim().ifBlank { null },
      clearContactEmail = contactEmail.trim().isEmpty(),
      visibility = visibility,
      visibleFields = visibleFields,
    )
    viewModelScope.launch {
      try {
        val viewer = graphQlClient.updateProfile(input)
        viewer.profile?.let { applyProfile(it) }
        saveSuccess = true
      } catch (e: Exception) {
        saveError = e.toUiText()
      } finally {
        saving = false
      }
    }
  }

  fun uploadAvatar(context: Context, uri: Uri) {
    avatarError = null
    val mimeType = context.contentResolver.getType(uri)
    if (mimeType == null || mimeType !in ALLOWED_PHOTO_MIME_TYPES) {
      avatarError = UiText.Res(R.string.photo_unsupported_format)
      return
    }
    if (sizeOf(context, uri) > MAX_PHOTO_BYTES) {
      avatarError = UiText.Res(R.string.photo_too_large)
      return
    }
    avatarUploading = true
    viewModelScope.launch {
      try {
        avatar = mediaClient.uploadAvatar(context, uri)
      } catch (e: Exception) {
        avatarError = e.toUiText()
      } finally {
        avatarUploading = false
      }
    }
  }

  fun removeAvatar() {
    avatarError = null
    viewModelScope.launch {
      try {
        avatar = graphQlClient.deleteAvatar().profile?.avatar
      } catch (e: Exception) {
        avatarError = e.toUiText()
      }
    }
  }

  fun openEmailChangeModal() {
    newEmail = ""
    emailChangeCode = ""
    emailChangeChallengeUid = null
    emailChangeStep = "email"
    emailChangeError = null
    emailChangeSuccess = false
    emailChangeVisible = true
  }

  fun closeEmailChangeModal() {
    emailChangeVisible = false
  }

  fun requestEmailChangeCode() {
    val email = newEmail.trim()
    if (email.isBlank()) return
    emailChangeLoading = true
    emailChangeError = null
    viewModelScope.launch {
      try {
        val response = authRepository.requestEmailChange(email)
        emailChangeChallengeUid = response.challengeUid
        emailChangeStep = "code"
      } catch (e: Exception) {
        emailChangeError = e.toUiText()
      } finally {
        emailChangeLoading = false
      }
    }
  }

  fun confirmEmailChangeCode() {
    val challengeUid = emailChangeChallengeUid ?: return
    val email = newEmail.trim()
    val code = emailChangeCode.trim()
    if (code.isBlank()) return
    emailChangeLoading = true
    emailChangeError = null
    viewModelScope.launch {
      try {
        // token_version se na serveru inkrementoval (odhlásí ostatní zařízení) —
        // confirmEmailChange si hned poté sám obnoví access token (viz AuthRepository).
        authRepository.confirmEmailChange(challengeUid, code, email)
        emailChangeSuccess = true
        loginEmail = email
      } catch (e: Exception) {
        emailChangeError = e.toUiText()
      } finally {
        emailChangeLoading = false
      }
    }
  }
}

/** OpenableColumns.SIZE přes Cursor — spolehlivější než InputStream.available() u content:// URI (viz PhotoPicker.kt). */
private fun sizeOf(context: Context, uri: Uri): Long {
  context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
    if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
      return cursor.getLong(sizeIndex)
    }
  }
  return 0L
}
