package ru.forma.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

internal data class CloudRestoreUi(
    val busy: Boolean = false,
    val profiles: List<CloudProfileChoice>? = null,
    val summary: LocalSyncSummary? = null,
    val message: String? = null,
)

@Composable
internal fun CloudSyncPanel(model: FormaViewModel) {
    val ui by model.cloudRestoreUi.collectAsState()
    var selected by remember { mutableStateOf<CloudProfileChoice?>(null) }
    LaunchedEffect(model) { model.refreshCloudStatus() }

    Panel("Синхронизация аккаунта") {
        ui.summary?.let { Text(it.accessibleMessage()) }
        Action("Поставить очередь на отправку", model::scheduleCloudSync, enabled = !ui.busy)
        TextButton(model::refreshCloudStatus, enabled = !ui.busy) { Text("Обновить состояние очереди") }
        Action("Показать облачные профили", model::listCloudProfiles, enabled = !ui.busy)
        ui.profiles?.let { profiles ->
            if (profiles.isEmpty()) Text("В облаке пока нет профилей этого аккаунта.")
            profiles.forEach { profile ->
                Action("Восстановить «${profile.name}»", { selected = profile }, enabled = !ui.busy)
            }
        }
        if (ui.busy) Text("Выполняется запрос…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        ui.message?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
    selected?.let { profile ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Восстановить «${profile.name}»?") },
            text = {
                Text("Профиль, тренировки и замеры будут сохранены на этом устройстве и связаны с текущим аккаунтом. Новые изменения будут поставлены в очередь синхронизации. При конфликте текущая запись сохранится.")
            },
            confirmButton = {
                TextButton({
                    selected = null
                    model.restoreCloudProfile(profile.id)
                }) { Text("Согласен и восстанавливаю") }
            },
            dismissButton = { TextButton({ selected = null }) { Text("Отмена") } },
        )
    }
}
