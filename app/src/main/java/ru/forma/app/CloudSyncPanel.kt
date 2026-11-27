package ru.forma.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import ru.forma.domain.Equipment
import ru.forma.domain.Experience
import ru.forma.domain.Goal
import ru.forma.domain.Muscle
import ru.forma.domain.measurementLabels

internal data class CloudRestoreUi(
    val busy: Boolean = false,
    val profiles: List<CloudProfileChoice>? = null,
    val summary: LocalSyncSummary? = null,
    val reviews: List<SyncReviewRow>? = null,
    val preview: CloudConflictPreview? = null,
    val archives: List<SyncResolutionArchiveRow>? = null,
    val message: String? = null,
)

@Composable
internal fun CloudSyncPanel(model: FormaViewModel) {
    val ui by model.cloudRestoreUi.collectAsState()
    var selected by remember { mutableStateOf<CloudProfileChoice?>(null) }
    var archiveId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(model) { model.refreshCloudStatus() }

    Panel("Синхронизация аккаунта") {
        ui.summary?.let { Text(it.accessibleMessage()) }
        Action("Поставить очередь на отправку", model::scheduleCloudSync, enabled = !ui.busy)
        Action("Получить изменения из облака", model::scheduleCloudReconciliation, enabled = !ui.busy)
        TextButton(model::refreshCloudStatus, enabled = !ui.busy) { Text("Обновить состояние очереди") }
        TextButton(model::listCloudConflicts, enabled = !ui.busy) { Text("Показать конфликты") }
        ui.reviews?.let { records ->
            if (records.isEmpty()) Text("Записей для ручного разбора нет.")
            if (records.size == 50) Text("Показаны первые 50 конфликтов.")
            records.forEachIndexed { index, key ->
                TextButton({ model.previewCloudConflict(key) }, enabled = !ui.busy) {
                    Text("${recordLabel(key.kind)} · ${index + 1}")
                }
            }
        }
        TextButton(model::listCloudArchives, enabled = !ui.busy) { Text("История выбора версий") }
        ui.archives?.let { records ->
            if (records.isEmpty()) Text("История выбора версий пока пуста.")
            if (records.size == 30) Text("Показаны последние 30 решений.")
            records.forEach { record ->
                TextButton({ archiveId = record.id }, enabled = !ui.busy) {
                    Text("${recordLabel(record.kind)} · ${dateLabel(record.savedAtMs)}")
                }
            }
        }
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
    ui.preview?.let { preview ->
        var choice by remember(preview) { mutableStateOf<CloudConflictChoice?>(null) }
        AlertDialog(
            onDismissRequest = { if (!ui.busy) model.closeCloudConflict() },
            title = { Text("${recordLabel(preview.key.kind)} · ${preview.profileName}") },
            text = {
                ConflictVersions(
                    preview.key.kind, preview.localSnapshot, preview.cloud?.snapshot,
                    preview.cloud?.tombstone == true, preview.blockedReason ?: ui.message,
                    if (choice == null) null else
                        "Обе показанные версии останутся в истории выбора на этом устройстве.",
                )
            },
            confirmButton = {
                val selectedChoice = choice
                if (selectedChoice == null) {
                    Column {
                        if (preview.canKeepDevice) TextButton(
                            { choice = CloudConflictChoice.DEVICE }, enabled = !ui.busy,
                        ) { Text("Оставить версию устройства") }
                        if (preview.canUseCloud) TextButton(
                            { choice = CloudConflictChoice.CLOUD }, enabled = !ui.busy,
                        ) { Text("Принять облачную версию") }
                    }
                } else TextButton({ model.resolveCloudConflict(selectedChoice) }, enabled = !ui.busy) {
                    Text(if (selectedChoice == CloudConflictChoice.DEVICE)
                        "Подтвердить версию устройства" else "Подтвердить облачную версию")
                }
            },
            dismissButton = {
                TextButton({ if (choice != null) choice = null else model.closeCloudConflict() }, enabled = !ui.busy) {
                    Text(if (choice == null) "Закрыть" else "Назад")
                }
            },
        )
    }
    ui.archives?.firstOrNull { it.id == archiveId }?.let { archived ->
        AlertDialog(
            onDismissRequest = { archiveId = null },
            title = { Text("История выбора · ${dateLabel(archived.savedAtMs)}") },
            text = {
                ConflictVersions(archived.kind, archived.localSnapshot, archived.cloudSnapshot, archived.cloudDeleted,
                    if (archived.choice == CloudConflictChoice.CLOUD.name) "Была выбрана облачная версия."
                    else "Была выбрана версия устройства.")
            },
            confirmButton = { TextButton({ archiveId = null }) { Text("Закрыть") } },
        )
    }
}

@Composable
private fun ConflictVersions(
    kind: String, local: String?, cloud: String?, deleted: Boolean, note: String?, confirmation: String? = null,
) {
    val localText = remember(kind, local) { local?.let { snapshotLabel(kind, it) } ?: "Записи нет." }
    val cloudText = remember(kind, cloud, deleted) {
        when {
            cloud == null -> "Версия недоступна."
            deleted -> "Запись помечена как удалённая.\n" + snapshotLabel(kind, cloud)
            else -> snapshotLabel(kind, cloud)
        }
    }
    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), Arrangement.spacedBy(12.dp)) {
        Text("Версия устройства\n" + localText)
        Text("Облачная версия\n" + cloudText)
        note?.let { Text(it) }
        confirmation?.let { Text(it) }
    }
}

private fun recordLabel(kind: String) = when (kind) {
    "profile" -> "Профиль"
    "measurement" -> "Замер"
    else -> "Тренировка"
}

private fun snapshotLabel(kind: String, snapshot: String): String = runCatching {
    when (kind) {
        "profile" -> with(restoreJson.decodeFromString<CloudProfilePayload>(snapshot)) {
            val goals = listOfNotNull(primary, secondary).joinToString(", ") { Goal.valueOf(it).label }
            val gear = equipment.joinToString(", ") { Equipment.valueOf(it).label }.ifEmpty { "—" }
            val muscles = priorities.joinToString(", ") { Muscle.valueOf(it).label }.ifEmpty { "—" }
            val weekdays = days.joinToString(", ") { listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")[it - 1] }
            listOf(name, "$age лет · $heightCm см · $weightKg кг", Experience.valueOf(experience).label,
                "Цели — $goals", "Оборудование — $gear", "Приоритеты — $muscles",
                "Дни — ${weekdays.ifEmpty { "—" }}", "Восстановление — $recovery из 5",
                limitations).filter { it.isNotBlank() }.joinToString("\n")
        }
        "measurement" -> with(restoreJson.decodeFromString<CloudMeasurementPayload>(snapshot)) {
            (listOf(dateLabel(dateMs)) + values.map { "${measurementLabels[it.key] ?: it.key} — ${it.value}" }).joinToString("\n")
        }
        else -> with(restoreJson.decodeFromString<CloudWorkoutPayload>(snapshot)) {
            val exerciseNames = day.exercises.associate { it.exercise.id to it.exercise.name }
            val detail = sets.map { set ->
                val exercise = exerciseNames[set.exerciseId] ?: set.exerciseId
                "$exercise · ${set.reps} повт. · ${set.weight} кг · усилие ${set.effort}/10" +
                    if (set.pain) " · отмечена боль" else ""
            }
            val completion = if (status == "DONE") "Завершена" else "Остановлена"
            (listOf(day.name, dateLabel(startedMs),
                "$completion · ${activeSeconds / 60} мин · подходов — ${sets.size}") + detail).joinToString("\n")
        }
    }
}.getOrDefault("Описание версии недоступно.")
