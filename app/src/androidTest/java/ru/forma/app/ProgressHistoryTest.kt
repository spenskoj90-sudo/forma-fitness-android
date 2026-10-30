package ru.forma.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.forma.domain.*

/** The history is never trimmed in Room; the UI reveals it in accessible windows. */
@RunWith(AndroidJUnit4::class)
class ProgressHistoryTest {
    @get:Rule val rule = createComposeRule()

    @Test fun largeOfflineHistoryPagesWithoutDroppingOldWorkoutsOrFakingBodyweightKg() {
        val bodyweight = Exercise("test_push", "Отжимания", Muscle.CHEST, Equipment.BODYWEIGHT, "Без боли")
        val workouts = (1..23).map { n ->
            val day = TrainingDay(1, "Тренировка $n", exercises = listOf(Prescription(bodyweight, 1, 12)))
            val at = n.toLong() * 1000L
            Workout("history-$n", day, at, at + 500L,
                listOf(SetResult(bodyweight.id, 12, 0.0, 7, false, at)), Phase.DONE)
        }
        val profile = Profile(name = "Тест", history = workouts)
        rule.setContent { MaterialTheme { ProgressScreen(profile) } }

        rule.onNodeWithText("Последняя сохранённая тренировка").assertExists()
        rule.onNodeWithText("Записанные подходы: 1 · активное время: 0 мин 0 сек").assertExists()
        rule.onNodeWithText("Показано 10 из 23 сохранённых тренировок.").assertExists()
        rule.onNodeWithText("Тренировка 23").assertExists()
        rule.onNodeWithText("Тренировка 1").assertDoesNotExist()
        rule.onNodeWithText("Собственный вес · максимум: 12 повторений").assertExists()

        rule.onNodeWithText("Показать ещё тренировки").performScrollTo().performClick()
        rule.onNodeWithText("Показано 20 из 23 сохранённых тренировок.").assertExists()
        rule.onNodeWithText("Тренировка 1").assertDoesNotExist()

        rule.onNodeWithText("Показать ещё тренировки").performScrollTo().performClick()
        rule.onNodeWithText("Показано 23 из 23 сохранённых тренировок.").assertExists()
        rule.onNodeWithText("Тренировка 1").assertExists()
        rule.onNodeWithText("Показать ещё тренировки").assertDoesNotExist()
        rule.onNodeWithText("0.0 кг дополнительного веса", substring = true).assertDoesNotExist()
    }
}
