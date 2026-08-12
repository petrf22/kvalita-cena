package cz.kvalitacena.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Appka narazila na [cz.kvalitacena.network.ClientVersionInterceptor] (starý APK v terénu po
 * zamrznutí GraphQL kontraktu, docs/vydani.md) — nastavuje se ze síťové vrstvy, čte se
 * v `MainActivity`, aby zablokovala celou appku srozumitelnou obrazovkou místo toho, aby každá
 * obrazovka zvlášť hlásila nesrozumitelnou chybu. `mutableStateOf`, ne `StateFlow` — stejný vzor
 * jako `DisplayCurrencyStore`, Compose snapshoty zvládají zápis z jiného vlákna bez dalšího mostu.
 */
object UpdateRequiredState {
  var required: Boolean by mutableStateOf(false)
}
