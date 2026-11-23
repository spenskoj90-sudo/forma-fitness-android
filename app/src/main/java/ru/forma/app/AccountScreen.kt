package ru.forma.app

import android.util.Patterns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

internal enum class AccountAction { SIGN_IN, REGISTER, REFRESH, RESEND, RESET_PASSWORD, SIGN_OUT }

internal data class AccountScreenState(
    val session: AccountSession = AccountSession.SIGNED_OUT,
    val working: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

internal data class AccountConsentDraft(
    val profileId: String,
    val profileName: String,
    val email: String,
    val lease: VerifiedSyncLease,
)

@Composable
internal fun AccountConsentDialog(
    draft: AccountConsentDraft,
    confirm: () -> Unit,
    dismiss: () -> Unit,
) = AlertDialog(
    onDismissRequest = dismiss,
    title = { Text("Связать профиль с аккаунтом?") },
    text = { Text("Профиль «${draft.profileName}», история тренировок и замеры будут связаны с ${draft.email}. Записи будут отправлены, когда облачная синхронизация доступна. Для доступа к профилю понадобится этот аккаунт.") },
    confirmButton = { TextButton(confirm) { Text("Согласен и связываю профиль") } },
    dismissButton = { TextButton(dismiss) { Text("Отмена") } },
)

@Composable
internal fun AccountScreen(
    state: AccountScreenState,
    onAction: (AccountAction, String, String) -> Unit,
    onBack: () -> Unit,
    openWorkouts: (() -> Unit)? = null,
    linkGuest: (() -> Unit)? = null,
) {
    val focus = LocalFocusManager.current
    // Credentials live only in the active screen and are cleared on submit.
    var email by remember(state.session) { mutableStateOf("") }
    var password by remember(state.session) { mutableStateOf("") }
    val validEmail = Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    fun submit(action: AccountAction) {
        focus.clearFocus(force = true)
        onAction(action, email, password)
        password = ""
    }
    BackHandler(onBack = onBack)
    Page("Аккаунт FORMA") {
        TextButton(onBack, Modifier.heightIn(min = 48.dp)) { Text("Назад") }
        Text("Профили, тренировки и замеры хранятся на этом устройстве. Для отправки в облако нужно отдельное согласие.")
        when (state.session) {
            AccountSession.SIGNED_OUT -> {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Электронная почта") },
                    singleLine = true,
                    enabled = !state.working,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль") },
                    singleLine = true,
                    enabled = !state.working,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                val canSubmit = !state.working && validEmail && password.isNotEmpty()
                Action("Войти", { submit(AccountAction.SIGN_IN) }, canSubmit)
                TextButton(
                    { submit(AccountAction.REGISTER) },
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = canSubmit,
                ) { Text("Создать аккаунт") }
                TextButton(
                    { submit(AccountAction.RESET_PASSWORD) },
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = !state.working && validEmail,
                ) { Text("Сбросить пароль") }
            }
            AccountSession.EMAIL_UNVERIFIED -> {
                Text("Открой письмо и подтверди адрес, затем проверь статус здесь.")
                Action("Проверить подтверждение", { submit(AccountAction.REFRESH) }, !state.working)
                TextButton(
                    { submit(AccountAction.RESEND) },
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = !state.working,
                ) { Text("Отправить письмо ещё раз") }
                Action("Выйти из аккаунта", { submit(AccountAction.SIGN_OUT) }, !state.working)
            }
            AccountSession.EMAIL_VERIFIED -> {
                Text("Почта подтверждена. Тренировки и замеры доступны в локальном профиле.")
                openWorkouts?.let { Action("Профили аккаунта", it, !state.working) }
                linkGuest?.let { Action("Связать выбранный профиль", it, !state.working) }
                Action("Выйти из аккаунта", { submit(AccountAction.SIGN_OUT) }, !state.working)
            }
        }
        if (state.working) CircularProgressIndicator(
            Modifier.semantics { contentDescription = "Выполняется запрос к аккаунту" }
        )
        state.message?.let {
            Text(
                it,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
