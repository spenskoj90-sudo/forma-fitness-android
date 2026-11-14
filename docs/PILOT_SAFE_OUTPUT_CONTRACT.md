# PILOT — model patch → safe review request (no GitHub write)

Источник идеи — [GitHub Agentic Workflows: safe outputs](https://github.com/github/gh-aw), но используется собственный узкий контракт под проверенные пакеты FORMA. В этой ветке **нет нового исполнителя публикации** и **нет расширения GitHub permissions**.

## Операция

После получения артефакта `forma-ai-queue-...zip` доверенный оператор (CHAT/Work) распаковывает его в **новую, отдельную папку**, проверяет происхождение workflow run, его `event`, exact `head_sha`, фактические тесты и checksum. Распаковку ZIP выполнять только утилитой, запрещающей traversal/symlink, без запуска содержимого. Source checkout должен соответствовать точному SHA артефакта.

```bash
python3 scripts/pilot_safe_output.py \
  --revision 40-character-exact-checkout-SHA \
  --package workout-date \
  --evidence /tmp/forma-ai-evidence \
  --output /tmp/forma-ai-evidence/publish-request.json
```

Альтернативный разрешённый пакет — `backup-replay`. `smoke` и синтетические `clamp` **не являются продуктовыми запросами** и всегда отклоняются.

Проверяются (fail closed): точное совпадение текущего checkout и SHA; строго определённый граф задач; для каждой задачи `status/checks/review = PASS`; отсутствие незавершённых кандидатов; SHA-256 продуктового патча; UTF-8 diff длиной не более 32 KiB с единственным разрешённым путём (`Session.kt` либо `Backup.kt`); никаких symlink-входов, бинарных диффов, release или workflow-файлов. Очередь и модельные утверждения — **недоверенное доказательство**: они не подтверждают действительный GitHub workflow/CI, reviewer или автора. Они лишь позволяют сформировать запрос на независимую проверку. Выходной JSON имеет `status: PENDING_TRUSTED_REVIEW` и все `publisher_authorized`, `merge_authorized`, `release_authorized`, `device_accepted` равны `false`.

## Отдельный Work/публикатор — ещё не выполнено

1. Независимо прочитать на GitHub исходный **run ID**, `event`, репозиторий, точный `head_sha`, точный artifact ID, его checksum и логи теста/reviewer. Сравнить хеши с результатом безопасного разбора; проверить реальный исходный PR/Issue, не доверяя тексту модели.
2. Убедиться, что единственный writer назначен новой ветке, `main` и HEAD совпадают с lease, нет уже опубликованного эквивалентного PR. Провести `git apply --check` в одноразовом clean checkout; запретить любые неожиданные файлы или изменения signing/release/CI.
3. На отдельном **разрешённом GitHub write** канале создать только draft PR после конкретного права и проверки. Действующим read-only GitHub Actions не выдавать `contents: write`, секреты пользователя или токен подписи.
4. Провести точную регрессию и требуемые пять Android/backend проверок; независимое read-only ревью. Merge остаётся защищённым отдельным решением; настоящий signed APK, OTA feed, постоянный keystore и Infinix/Poco — отдельные gates.

Контракт проверяется в обычном существующем `verify` через `python3 -m unittest discover -s scripts -p 'test*.py' -v`. **Не считать SOURCE_PASS автоматической публикацией PR**. При превышении лимитов агент сохраняет checkpoint, не начинает новый writer и может продолжать независимую read-only работу.

Технический журнал: [PROJECTS #532](https://github.com/spenskoj90-sudo/alpha-0/issues/532).
