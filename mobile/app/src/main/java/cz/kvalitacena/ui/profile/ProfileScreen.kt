package cz.kvalitacena.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import cz.kvalitacena.AppContainer
import cz.kvalitacena.R
import cz.kvalitacena.ui.common.SingleLineTextField

/** Pořadí řádků v tabulce viditelnosti — nezávislé na pořadí, v jakém přijdou ze serveru. */
private val VISIBILITY_FIELDS = listOf(
  "FIRST_NAME" to R.string.profile_field_first_name,
  "LAST_NAME" to R.string.profile_field_last_name,
  "DISPLAY_NAME" to R.string.profile_field_display_name,
  "PHONE" to R.string.profile_field_phone,
  "CONTACT_EMAIL" to R.string.profile_field_contact_email,
  "AVATAR" to R.string.profile_field_avatar,
)

/**
 * Editace profilu — jméno, příjmení, přezdívka, telefon, kontaktní e-mail, avatar, viditelnost
 * (docs/soukromi.md, "Profil uživatele a viditelnost"). Přístupná ze záložky Účet
 * (`AccountScreen`, tlačítko "Upravit profil"). Webový protějšek: frontend
 * features/profile/profile-page.ts.
 */
@Composable
fun ProfileScreen(onDone: () -> Unit) {
  val viewModel: ProfileViewModel = viewModel(
    factory = viewModelFactory {
      initializer { ProfileViewModel(AppContainer.graphQlClient, AppContainer.mediaClient, AppContainer.authRepository) }
    },
  )
  val context = LocalContext.current

  val avatarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
    uri?.let { viewModel.uploadAvatar(context, it) }
  }

  if (viewModel.loading) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    return
  }

  Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
    Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.headlineSmall)
    Gap()

    viewModel.loadError?.let {
      Text(it.asString(), color = MaterialTheme.colorScheme.error)
      Gap()
    }

    // --- Avatar ---
    Text(stringResource(R.string.profile_avatar_title), style = MaterialTheme.typography.titleMedium)
    Gap()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      Box(
        modifier = Modifier.size(72.dp).clip(CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        val avatar = viewModel.avatar
        if (avatar != null) {
          AsyncImage(model = avatar.thumbUrl(), contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
          Icon(
            painter = painterResource(R.drawable.ic_tab_account),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
      Column {
        OutlinedButton(
          onClick = { avatarLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
          enabled = !viewModel.avatarUploading,
        ) {
          if (viewModel.avatarUploading) CircularProgressIndicator(modifier = Modifier.size(18.dp))
          else Text(stringResource(R.string.profile_avatar_upload))
        }
        if (viewModel.avatar != null) {
          TextButton(onClick = { viewModel.removeAvatar() }) {
            Text(stringResource(R.string.profile_avatar_remove))
          }
        }
      }
    }
    Text(
      stringResource(R.string.profile_avatar_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    viewModel.avatarError?.let {
      Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    Gap()
    HorizontalDivider()
    Gap()

    // --- Textové údaje ---
    SingleLineTextField(
      value = viewModel.firstName,
      onValueChange = { viewModel.firstName = it },
      label = stringResource(R.string.profile_first_name_label),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()
    SingleLineTextField(
      value = viewModel.lastName,
      onValueChange = { viewModel.lastName = it },
      label = stringResource(R.string.profile_last_name_label),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()
    SingleLineTextField(
      value = viewModel.displayName,
      onValueChange = { viewModel.displayName = it },
      label = stringResource(R.string.profile_display_name_label),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()
    SingleLineTextField(
      value = viewModel.phone,
      onValueChange = { viewModel.phone = it },
      label = stringResource(R.string.profile_phone_label),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
      modifier = Modifier.fillMaxWidth(),
    )
    Gap()
    SingleLineTextField(
      value = viewModel.contactEmail,
      onValueChange = { viewModel.contactEmail = it },
      label = stringResource(R.string.profile_contact_email_label),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
      modifier = Modifier.fillMaxWidth(),
    )
    Text(
      stringResource(R.string.profile_encrypted_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Gap()
    HorizontalDivider()
    Gap()

    // --- Přihlašovací e-mail ---
    Text(stringResource(R.string.profile_login_email_label), style = MaterialTheme.typography.titleMedium)
    Text(viewModel.loginEmail, style = MaterialTheme.typography.bodyMedium)
    Gap()
    OutlinedButton(onClick = { viewModel.openEmailChangeModal() }) {
      Text(stringResource(R.string.profile_change_email_button))
    }
    Gap()
    HorizontalDivider()
    Gap()

    // --- Viditelnost ---
    Text(stringResource(R.string.profile_visibility_title), style = MaterialTheme.typography.titleMedium)
    Gap()
    VisibilityOption("ANONYMOUS", R.string.profile_visibility_anonymous, viewModel.visibility) { viewModel.visibility = it }
    VisibilityOption("PUBLIC", R.string.profile_visibility_public, viewModel.visibility) { viewModel.visibility = it }
    VisibilityOption("FRIENDS", R.string.profile_visibility_friends, viewModel.visibility) { viewModel.visibility = it }

    if (viewModel.visibility != "ANONYMOUS") {
      Gap()
      Text(stringResource(R.string.profile_fields_table_title), style = MaterialTheme.typography.titleSmall)
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("", modifier = Modifier.weight(1f))
        Text(
          stringResource(R.string.profile_fields_public_column),
          style = MaterialTheme.typography.labelMedium,
          modifier = Modifier.weight(0.6f),
        )
        Text(
          stringResource(R.string.profile_fields_friends_column),
          style = MaterialTheme.typography.labelMedium,
          modifier = Modifier.weight(0.6f),
        )
      }
      VISIBILITY_FIELDS.forEach { (field, labelRes) ->
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(stringResource(labelRes), modifier = Modifier.weight(1f))
          Checkbox(
            checked = viewModel.isChecked(field, "PUBLIC"),
            onCheckedChange = { viewModel.toggleField(field, "PUBLIC") },
            modifier = Modifier.weight(0.6f),
          )
          Checkbox(
            checked = viewModel.isChecked(field, "FRIENDS"),
            onCheckedChange = { viewModel.toggleField(field, "FRIENDS") },
            modifier = Modifier.weight(0.6f),
          )
        }
      }
      Text(
        stringResource(R.string.profile_fields_friends_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Gap()

    viewModel.saveError?.let {
      Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      Gap()
    }
    if (viewModel.saveSuccess) {
      Text(
        stringResource(R.string.profile_save_success),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodySmall,
      )
      Gap()
    }
    Button(onClick = { viewModel.save() }, enabled = !viewModel.saving, modifier = Modifier.fillMaxWidth()) {
      if (viewModel.saving) CircularProgressIndicator(modifier = Modifier.size(20.dp))
      else Text(stringResource(R.string.profile_save_button))
    }
    Gap()

    HorizontalDivider()
    Gap()
    Text(stringResource(R.string.profile_links_title), style = MaterialTheme.typography.titleMedium)
    Gap()
    listOf(
      R.string.profile_links_friends,
      R.string.profile_links_system_rating,
      R.string.profile_links_friends_trust,
      R.string.profile_links_stats,
    ).forEach { labelRes ->
      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(stringResource(labelRes))
        AssistChip(onClick = {}, enabled = false, label = { Text(stringResource(R.string.profile_links_coming_soon)) })
      }
    }
    Gap()
    OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
      Text(stringResource(R.string.common_back))
    }
  }

  if (viewModel.emailChangeVisible) {
    EmailChangeDialog(viewModel)
  }
}

@Composable
private fun VisibilityOption(value: String, labelRes: Int, selected: String, onSelect: (String) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    RadioButton(selected = selected == value, onClick = { onSelect(value) })
    Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun EmailChangeDialog(viewModel: ProfileViewModel) {
  AlertDialog(
    onDismissRequest = { viewModel.closeEmailChangeModal() },
    title = { Text(stringResource(R.string.profile_email_change_title)) },
    text = {
      Column {
        if (viewModel.emailChangeSuccess) {
          Text(stringResource(R.string.profile_email_change_success))
          return@Column
        }
        viewModel.emailChangeError?.let {
          Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          Gap()
        }
        if (viewModel.emailChangeStep == "email") {
          SingleLineTextField(
            value = viewModel.newEmail,
            onValueChange = { viewModel.newEmail = it },
            label = stringResource(R.string.profile_email_change_new_email_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
          )
        } else {
          Text(stringResource(R.string.profile_email_change_code_hint, viewModel.newEmail))
          Gap()
          SingleLineTextField(
            value = viewModel.emailChangeCode,
            onValueChange = { viewModel.emailChangeCode = it },
            label = stringResource(R.string.profile_email_change_code_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    },
    confirmButton = {
      if (viewModel.emailChangeSuccess) {
        TextButton(onClick = { viewModel.closeEmailChangeModal() }) { Text(stringResource(R.string.common_back)) }
      } else if (viewModel.emailChangeStep == "email") {
        TextButton(
          onClick = { viewModel.requestEmailChangeCode() },
          enabled = viewModel.newEmail.isNotBlank() && !viewModel.emailChangeLoading,
        ) {
          if (viewModel.emailChangeLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp))
          else Text(stringResource(R.string.profile_email_change_send_code))
        }
      } else {
        TextButton(
          onClick = { viewModel.confirmEmailChangeCode() },
          enabled = viewModel.emailChangeCode.isNotBlank() && !viewModel.emailChangeLoading,
        ) {
          if (viewModel.emailChangeLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp))
          else Text(stringResource(R.string.profile_email_change_confirm))
        }
      }
    },
    dismissButton = {
      if (!viewModel.emailChangeSuccess) {
        TextButton(onClick = { viewModel.closeEmailChangeModal() }) { Text(stringResource(R.string.common_cancel)) }
      }
    },
  )
}

@Composable
private fun Gap() {
  androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
}
