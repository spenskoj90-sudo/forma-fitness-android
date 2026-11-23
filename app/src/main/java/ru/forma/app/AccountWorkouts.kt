package ru.forma.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class Section { PROFILES, TRAINING, HISTORY }

@Composable
internal fun ColumnScope.AccountWorkouts(
    scope: AccountWorkoutScope,
    onClose: (String) -> Unit,
) = key(scope.id) {
    val app = LocalContext.current.applicationContext as FormaApp
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    val factory = remember {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FormaViewModel(app, scope) as T
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val model: FormaViewModel = viewModel(viewModelStoreOwner = owner, factory = factory)
    val profiles by model.profileSnapshot.collectAsState()
    val settings by model.settingsSnapshot.collectAsState()
    val current by model.current.collectAsState()
    val error by model.error.collectAsState()
    val notice by model.notice.collectAsState()
    var section by remember { mutableStateOf(Section.PROFILES) }
    fun back() {
        if (current?.active != null || section == Section.PROFILES) onClose(scope.id)
        else section = Section.PROFILES
    }
    BackHandler(onBack = ::back)
    TextButton(::back, Modifier.heightIn(min = 48.dp)) {
        Text(if (current?.active != null) "Закрыть профили аккаунта" else "Назад")
    }
    error?.let {
        Panel("Не удалось выполнить действие") {
            Text(it)
            TextButton({ model.error.value = null }) { Text("Понятно") }
        }
    }
    notice?.let {
        Panel("Данные сохранены") {
            Text(it)
            TextButton({ model.notice.value = null }) { Text("Понятно") }
        }
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        val profile = current
        val active = profile?.active
        when {
            app.resolveWorkoutRepository(scope.id) == null -> Unit
            !canRenderProfileUi(profiles, settings, profile) -> Text(
                if (profiles.status == LocalLoadStatus.FAILED || settings.status == LocalLoadStatus.FAILED)
                    "Профили аккаунта недоступны."
                else "Загрузка профилей аккаунта…"
            )
            active != null -> Runner(active, model) { section = Section.HISTORY }
            profile != null && section == Section.TRAINING -> TrainingScreen(profile, settings.settings.trainingPlace, model)
            profile != null && section == Section.HISTORY -> ProgressScreen(profile)
            else -> Page("Профили аккаунта") {
                if (profile == null) Text("На этом устройстве ещё нет профилей, связанных с аккаунтом.")
                profiles.profiles.forEach { candidate ->
                    OutlinedButton(
                        { model.select(candidate.id) },
                        Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(if (candidate.id == profile?.id) "✓ ${candidate.name}" else candidate.name) }
                }
                if (profile != null) {
                    Action("Тренировки", { section = Section.TRAINING })
                    Action("История и прогресс", { section = Section.HISTORY })
                    EncryptedBackupPanel(model, selectedOnly = true)
                }
                if (model.cloudSyncAvailable) CloudSyncPanel(model)
            }
        }
    }
}
