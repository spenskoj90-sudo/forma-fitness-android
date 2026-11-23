# PILOT — разрешённые инструменты и безопасная маршрутизация (14.11.2026)

## Что действительно работает

Канонический реестр: [scripts/ai-pilot/tool_catalog.json](../scripts/ai-pilot/tool_catalog.json). В нём 34 проверенных по официальным GitHub-репозиториям решения, `stage` и отдельные требования. Только `active` означает **ранее использованный компонент**, но даже он не получает права установки или исполнения через реестр. `evaluate` — кандидат на изолированный опыт; `defer` — откладывается. Реестр **не устанавливает**, не скачивает, не запускает сторонний код и не авторизует расходы.

Текущие `active`: существующая FORMA AI-очередь (два локальных Qwen и независимые проверки), `ggml-org/llama.cpp` для loopback-инференса и `actions/runner` для GitHub Actions. Это **не 3 новых агента**, а проверенные существующие исполнительные компоненты. Сервисы и инструменты со статусом `evaluate` **ещё не подключены**.

Маршрутизатор `scripts/pilot_tool_router.py` принимает логическую роль, возвращает только `ROUTABLE` / `BLOCKED`, без исполнения. Жёсткий allowlist запрещает самопроизвольное продвижение внешнего GitHub-пакета в `active`, добавление произвольного `command`, API-ключей, privileged writer, production, расходов и подписи:

```bash
python3 scripts/pilot_tool_router.py --validate
python3 scripts/pilot_tool_router.py --role author
python3 scripts/pilot_tool_router.py --role security --candidates
python3 scripts/pilot_tool_router.py --role design --candidates
python3 -m unittest discover -s scripts -p 'test_pilot_tool_router.py' -v
```

Задачи `author/review/coordination/qa/inference` могут маршрутизироваться к уже существующим компонентам. `design/asset3d/render3d/security` пока **BLOCKED** как самостоятельные исполнители: кандидат указан, но он не развёрнут, не проверен как runtime и не получил разрешение. `release` и `production` намеренно отсутствуют в автономных ролях.

Тесты маршрутизатора запускаются существующей Android `verify` командой `python3 -m unittest discover -s scripts -p 'test*.py' -v`; Workflow API/permissions не меняются. Это тест **конфигурации**, не тест действительного исполнения кандидата. Отдельный [PILOT open-source adoption ADR](PILOT_OPEN_SOURCE_ADOPTION.md) в Draft PR #24 описывает проверенный визуальный контур Blender → GLB → Filament; до его слияния основная ветка не содержит этот документ.

## Выбор по полезности — не устанавливать всё подряд

| Приоритет | Решение | Польза | Требование до подключения |
|---|---|---|---|
| P0 | Текущая очередь + llama.cpp | Бесплатный CPU inference, тесты, checkpoint, без сторонних API | Стабильный реальный schedule и доверенный канал отдельных draft PR |
| P1 | [Zizmor](https://github.com/zizmorcore/zizmor), [Actionlint](https://github.com/rhysd/actionlint) | Защита GitHub Actions, workflow-ошибки и риск разрешений | Пин версии/хеша и изолированное тестирование на workflow |
| P1 | [OSV-Scanner](https://github.com/google/osv-scanner), [Gitleaks](https://github.com/gitleaks/gitleaks) | Уязвимости зависимостей и утечки секретов | Проверенная локальная установка и логи без значений секретов |
| P1 | [Aider](https://github.com/Aider-AI/aider) | Repo-map и цикл тест → исправление для Qwen | Тест без авто-коммита, cloud provider и секретов на временной ветке |
| P1 | [Maestro](https://github.com/mobile-dev-inc/Maestro), [Roborazzi](https://github.com/takahirom/roborazzi) | UI/screenshot регрессия Android, агентный MCP | Точная совместимость SDK/Kotlin, эмулятор; потом Infinix/Poco |
| P1 | [Blender](https://github.com/blender/blender), [glTF-Validator](https://github.com/KhronosGroup/glTF-Validator), [Filament](https://github.com/google/filament) | Оригинальный 3D-ассет → формат/лицензия/техника → APK-рендер | Изолированный Blender-экспорт, Khronos validation, Android GPU spike и экспертная проверка техники |
| P2 | [gh-aw](https://github.com/github/gh-aw), [LangGraph](https://github.com/langchain-ai/langgraph) | Разделение read-only агента и safe outputs, durable DAG | Безопасная версия/изолированный writer; не выдавать постоянных токенов |
| P2 | [OpenHands SDK](https://github.com/OpenHands/software-agent-sdk), [smolagents](https://github.com/huggingface/smolagents) | Более сложные задачи и дополнительные роли | Бенчмарк качества на наших Qwen, временная рабочая директория, лимит CPU/RAM |
| P2 | [Penpot](https://github.com/penpot/penpot) | Design tokens, компоненты, альтернатива Figma | Проверить конкретные бесплатные квоты и доступ; не делать критической зависимостью |
| Defer | Полный OpenHands, Renovate, SceneView, Godot | Большие сервисы/агенты и 3D | Сейчас больше рисков, хостинга и миграций, чем пользы |

**Проверки лицензии:** GitHub API показывает MIT для gh-aw/OpenHands/LangGraph/llama.cpp, Apache-2.0 для Aider/Filament/glTF-Validator/Maestro/Zizmor/OSV, MPL-2.0 для Penpot, AGPL-3.0 для Renovate. У GitHub-зеркала Blender `NOASSERTION`; отдельно сверять официальные GPL-условия Blender, не переносить лицензию инструмента автоматически на созданные пользователем объекты. Проверка SPDX и условий **каждой импортируемой зависимости** остаётся gate.

Некоммерческая цель не освобождает от лицензий; `$0` означает отсутствие обязательной покупки API, но GitHub-hosted Actions — не гарантированный непрерывный компьютер, а сторонние облачные SaaS могут вводить квоты, требования аккаунта, egress и платные функции. GitHub `schedule` может пропускать события.

## Границы исполнения CHAT / Work

**CHAT (сейчас):** работа с GitHub-кодом, готовые открытые библиотеки как справочные источники, PR с точными SHA, Python/Kotlin-проверки, репозиторный CI, журнал PROJECTS #532. Один writer на ветку. Не переписывать PR #23 и #24, пока они ждут обычной приёмки.

**Work (когда действительно понадобится):** длительные изолированные установки/сборки новых CLI и их бенчмарки против локальных моделей, интерактивная проверка Penpot/дизайн-систем, оригинальные Blender→GLB/Filament рендеры с доступом к полноценному SDK, защищённое включение разработанного publisher после отдельного решения о правах. Пользовательская приёмка на Infinix/Poco и custodian постоянного ключа **не заменяются Work** и требуют отдельных доказательств/согласований.

**Условие успеха FORMA:** подписанный постоянным ключом первый APK, подписанное обновление с большим versionCode на обеих физических моделях, сохранённая Room-история и проверяемое восстановление. Source/CI/Python PASS это не подтверждают.
