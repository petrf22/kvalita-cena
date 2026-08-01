package cz.kvalitacena.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.kvalitacena.AppContainer

@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
  val viewModel: LoginViewModel = viewModel(
    factory = viewModelFactory { initializer { LoginViewModel(AppContainer.authRepository) } },
  )

  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
  ) {
    Text("Přihlášení", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))

    viewModel.errorMessage?.let {
      Text(it, color = MaterialTheme.colorScheme.error)
      Spacer(Modifier.height(16.dp))
    }

    when (viewModel.step) {
      LoginStep.EMAIL -> {
        Text(
          "Pošleme ti na e-mail šestimístný kód, žádné heslo si nemusíš pamatovat.",
          style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
          value = viewModel.email,
          onValueChange = { viewModel.email = it },
          label = { Text("E-mail") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { viewModel.requestCode() }, modifier = Modifier.fillMaxWidth()) {
          if (viewModel.loading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
          else Text("Poslat kód")
        }
      }

      LoginStep.CODE -> {
        Text("Kód jsme poslali na ${viewModel.email}.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
          value = viewModel.code,
          onValueChange = { viewModel.code = it },
          label = { Text("Kód z e-mailu") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
          onClick = { viewModel.verifyCode(onSuccess = onLoggedIn) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          if (viewModel.loading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
          else Text("Přihlásit se")
        }
        TextButton(onClick = { viewModel.backToEmail() }, modifier = Modifier.fillMaxWidth()) {
          Text("Zadat jiný e-mail")
        }
      }
    }
  }
}
