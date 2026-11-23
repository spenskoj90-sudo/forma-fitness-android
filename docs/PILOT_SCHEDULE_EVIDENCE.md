# PILOT · подтверждение настоящего расписания и целостности очереди

Точная защищённая база до этого изменения: `a01e495c3552bad107f43a14e2193955850db0ce`. Рабочие ветки, защищённый `main` и разрешения GitHub не изменяются этим документом. Инженерный PR проходит обязательный Android/backend CI и отдельные Python-тесты.

## Что проверяется автоматически

Только на событии `schedule` после существующих шагов выбора/восстановления/запуска очереди выполняется `scripts/ai-pilot/verify_schedule_evidence.py`, после чего `forma-schedule-evidence.json` загружается в тот же семидневный артефакт `forma-ai-queue-RUN_ID-ATTEMPT`. При неполном checkpoint, отсутствии ожидаемого патча, чужой базе, неверном имени пакета, неоконченной проверке модели, неверном пути, неожиданном файле патча или изменённом хеше скрипт отклоняет результат и записывает `ARTIFACT_INTEGRITY_FAIL`. Неудачный результат не превращается в PASS через `if: always()`.

Поля: `workflow_run_id`, `source_repository`, `exact_checkout_sha`, `event_claimed_by_runner`, `package`, `task_type`, `task_ids`, `open_pr_count_at_selection`, SHA-256 **сырых** `forma-scheduled-plan.json`/`queue.json` и каждого ожидаемого `*.patch`. Все записи исходной очереди должны иметь `status/checks/review=PASS`. Для `smoke` `task_type=synthetic-smoke` — это **не продуктовый патч**. В receipt всегда `actual_github_run_independently_verified=false` и `publisher_authorized=false`: файл сам по себе не доказывает, что GitHub фактически запустил расписание.

## Независимое подтверждение после запуска

1. По GitHub API получить run (не из текста модели), убедиться, что workflow `FORMA AI work queue`, `event=schedule`, `head_branch=main`, `head_sha` равен наблюдаемому защищённому `main` на нужной ревизии, `status=completed`, `conclusion=success`; URL и `run_attempt` записать в журнал #532.
2. Получить список артефактов именно этого run и `run_attempt`; найти `forma-ai-queue-RUN_ID-ATTEMPT`, проверить срок, метаданные и отсутствие неожиданных входных файлов.
3. Без исполнения ZIP-содержимого извлечь `forma-scheduled-plan.json`, `queue.json`, соответствующие `*.patch` и `forma-schedule-evidence.json` в защищённый временный каталог; сверить SHA-256 с receipt; проверить `event_claimed_by_runner`, `workflow_run_id`, `exact_checkout_sha`, `package` и метку `ARTIFACT_INTEGRITY_PASS`.
4. Независимо проверить каждую заявленную модельную запись, происхождение runner, корректность подмены/применения патча `git apply --check` на exact SHA и обязательные Kotlin/Android/backend тесты. **PASS evidence** не равен PASS обязательного CI продуктового PR, подписанному APK или настоящей публикации.

Перед появлением фактического `schedule` на актуальном `main` этап имеет статус **NOT_RUN**, независимо от числа зелёных PR/ручных dispatch workflow. GitHub Actions cron не гарантирует запуск точно в минуту и может пропустить отдельные события.

## Локальные проверки без сети

```bash
python3 -m unittest discover -s scripts/ai-pilot -p test_schedule_evidence.py -v
python3 -m unittest discover -s scripts/ai-pilot -p test_scheduled_select.py -v
```

Тесты не запускают модель, не вызывают GitHub, не используют секреты. Сама настоящая очередная модельная задача остаётся в `run-queue.sh`; при недоступности Интернета локальные ранее проверенные модели/зависимости работают, но проверка авторитетного события GitHub и автоматический PR ждут восстановления GitHub.

**Отдельно не сделано и не объявляется сделанным:** долговременная удалённая контрольная точка (локальный Git-CAS в нынешнем сквозном тесте жил только в синтетическом окружении), новый свободный продуктовый task и доверенный GitHub draft PR publisher. Нужен будущий строго ограниченный `contents:write` / `pull-requests:write` вне model job и независимый ревью-процесс. Существующие продуктовые задания `workout-date` и `backup-replay` заняты PR #21 и #15, дублирование запрещено.

[Существующий журнал PROJECTS #532](https://github.com/spenskoj90-sudo/alpha-0/issues/532).
