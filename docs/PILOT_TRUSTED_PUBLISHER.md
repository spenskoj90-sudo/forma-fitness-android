# PILOT · доверенный публикатор draft PR

**Разрешение владельца (14.11.2026):** выделить отдельный недоступный локальным моделям GitHub Actions job с `actions:read`, `contents:write`, `pull-requests:write` для точного task checkpoint и **одного draft PR**. Права на merge, release, production, постоянные ключи, личные данные, реальные B2-секреты и расходы **не выдавались**.

## Граница исполнения

Существующая `.github/workflows/ai-work-queue.yml` остаётся read-only; Qwen и llama.cpp никогда не видят токен записи. Новая `.github/workflows/pilot-trusted-publisher.yml` активируется только по событию `workflow_run.completed` от *существующей* очереди, но её первый job отсекает всё, кроме фактического `schedule + success`. PR-проверки самого публикатора — отдельное задание с `contents:read`, никакой публикации.

**Inspector** получает только `actions:read` и `contents:read`: сверяет API run ID, attempt, repository, workflow ID/path, `event=schedule`, статус, `head_sha` с текущим protected `main`; читает строго один artifact по run/attempt; проверяет архив до распаковки (размер/хеш, уникальность, запрет traversal, symlink, лишних файлов); *независимо* пересчитывает `forma-schedule-evidence.json` и проверяет SHA-256 всех патчей, метаданные очереди и разрешённый Kotlin-файл. На `smoke` возвращает `SKIP_SYNTHETIC`. Для занятой задачи #21 или #15 возвращает `SKIP_EXISTING_PR`. Для свободной одобренной задачи применяет diff и запускает `./gradlew :domain:test` **без write-токена**.

Только если всё прошло, **publisher** получает на время одного job `actions:read` + `contents:write` + `pull-requests:write`. Он заново проверяет GitHub API и artifact, сверяет digest manifest из предыдущего job, убеждается, что protected `main` не менялся, и повторно проверяет единственный разрешённый diff. Код модели **не запускается** в привилегированном процессе. Для имитации применения используется `git apply` к одному разрешённому текстовому файлу во временном каталоге; результат записывается через GitHub Git Data API. Ветка строго `pilot/task-TASK_ID`, статус хранится в отдельной `pilot-state/TASK_ID`, с `pilot-state.json` и `pilot-patch.diff`. Состояние не зависит от 7-дневного срока хранения Actions-artifacts.

Разделение записей на `READY` → создание task branch → **ровно один draft PR** → `PUBLISHED` позволяет продолжить после прерывания между этими стадиями. Повтор проверяет существующую ветку, exact base SHA, единственный изменённый путь, commit SHA и уже созданный PR. `concurrency` сериализует привилегированного writer. Git API update checkpoint допускает только fast-forward (`force:false`); внешнее изменение ref вызывает отказ. Нельзя представлять это как полноценную межрепозиторную блокировку: доступ администратора или иной внешний writer может изменить refs. В журнале назначается один writer.

**Проверка после публикации:** созданный draft PR отдельно проходит обязательный Android `verify`, API 26/30/34, backend-security. До этого `required_pr_checks=NOT_RUN`. Публикатор **не выполняет merge** и не выдаёт source/domain-test PASS за приёмку телефонов Infinix/Poco. Release signing — отдельная приёмка.

## Текущее ограничение

На текущем `main` оба одобренных продуктовых пакета уже имеют PR: `workout-date` → #21, `backup-replay` → #15. Публикатор обязан **отказать** в повторе. `smoke` — синтетическая задача без продуктового PR. Поэтому до первого настоящего успешного `schedule` с новой свободной предварительно одобренной задачей **реальная автоматическая публикация останется NOT_RUN**, даже если все тесты публикатора PASS. Включение прав — не разрешение на создание дубликатов или выдумывание задач.

Если repo settings GitHub Actions запрещают `GITHUB_TOKEN` создавать pull requests, понадобится отдельно разрешить **Allow GitHub Actions to create and approve pull requests** в настройках репозитория (разрешать нужно только создание; approval не использовать). Это не заменяет защиту `main`, ограничения job и проверки. Само изменение настройки здесь **не выполнено**. Токен краткоживущий, никакого постоянного ключа или нового сервиса не требуется.

## Проверка

```bash
python3 -m unittest discover -s scripts/ai-pilot -p test_trusted_publisher.py -v
```

Все контракты тестируются на in-memory fake GitHub Git Data API (включая прерывание после checkpoint, после task-ветки, после создания draft до обновления checkpoint). Это **симуляция API**, а не доказательство реального `contents:write`/PR. Авторитетный GitHub `workflow_run`, проверка скачивания архива и разрешения нового job могут подтвердиться только следующим настоящим событием на merged `main`. Для проверки реальной автономии необходима также свободная одобренная задача. Не вызывать publisher с модельного runner или непривилегированного workflow_dispatch.

Журнал: [PROJECTS #532](https://github.com/spenskoj90-sudo/alpha-0/issues/532).
