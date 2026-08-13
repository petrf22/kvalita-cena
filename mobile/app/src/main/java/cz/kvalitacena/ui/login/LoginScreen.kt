package cz.kvalitacena.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.kvalitacena.AppContainer
import cz.kvalitacena.R
import cz.kvalitacena.ui.common.SingleLineTextField

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onOpenTerms: () -> Unit = {}, onOpenPrivacy: () -> Unit = {}) {
  val viewModel: LoginViewModel = viewModel(
    factory = viewModelFactory { initializer { LoginViewModel(AppContainer.authRepository) } },
  )

  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
  ) {
    Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))

    viewModel.errorMessage?.let {
      Text(it.asString(), color = MaterialTheme.colorScheme.error)
      Spacer(Modifier.height(16.dp))
    }

    when (viewModel.step) {
      LoginStep.EMAIL -> {
        Text(stringResource(R.string.login_otp_hint), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        SingleLineTextField(
          value = viewModel.email,
          onValueChange = { viewModel.email = it },
          label = stringResource(R.string.login_email_label),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { viewModel.requestCode() }),
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Top) {
          Checkbox(
            checked = viewModel.consentAccepted,
            onCheckedChange = { viewModel.consentAccepted = it },
          )
          Text(
            stringResource(R.string.login_consent_prefix) + " " +
              stringResource(R.string.login_consent_terms_link) + " " +
              stringResource(R.string.login_consent_and) + " " +
              stringResource(R.string.login_consent_privacy_link) + ".",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 14.dp),
          )
        }
        Row {
          TextButton(onClick = onOpenTerms) { Text(stringResource(R.string.login_consent_terms_link)) }
          TextButton(onClick = onOpenPrivacy) { Text(stringResource(R.string.login_consent_privacy_link)) }
        }
        Spacer(Modifier.height(8.dp))
        Button(
          onClick = { viewModel.requestCode() },
          enabled = viewModel.consentAccepted,
          modifier = Modifier.fillMaxWidth(),
        ) {
          if (viewModel.loading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
          else Text(stringResource(R.string.login_send_code))
        }
      }

      LoginStep.CODE -> {
        Text(
          stringResource(R.string.login_code_hint, viewModel.email),
          style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        SingleLineTextField(
          value = viewModel.code,
          onValueChange = { viewModel.code = it },
          label = stringResource(R.string.login_code_label),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { viewModel.verifyCode(onSuccess = onLoggedIn) }),
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
          onClick = { viewModel.verifyCode(onSuccess = onLoggedIn) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          if (viewModel.loading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
          else Text(stringResource(R.string.login_sign_in))
        }
        TextButton(onClick = { viewModel.backToEmail() }, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.login_change_email))
        }
      }
    }
  }
}
