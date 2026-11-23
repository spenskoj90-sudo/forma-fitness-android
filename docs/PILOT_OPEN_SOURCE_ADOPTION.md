# PILOT — выбор открытых инструментов и 3D-ветка FORMA (14.11.2026)

Это документ выбора инструментов, а не подтверждение их развёртывания, техник упражнений, подписанного релиза или физической приёмки.

## Состав решений и лицензии

| Upstream | Что включает | Решение |
|---|---|---|
| [github/gh-aw](https://github.com/github/gh-aw) (MIT) | GitHub Actions, декларативный агент, изолированные AI-движки Copilot/Claude/Codex/Pi, MCP, безопасные отдельные операции записи | Заимствовать **разделение агента и проверяющего writer**, без установки и API-ключей. Версии 0.83.3–0.85.3 отозваны из-за уязвимости |
| [Aider-AI/aider](https://github.com/Aider-AI/aider) (Apache-2.0) | Локальные и облачные LLM, индекс ключевых символов и зависимостей Git, тесты, lint, цикл автоматического исправления и Git-коммиты | Заимствовать **repo-map и repair loop**; отдельный запуск со старым Qwen только в песочнице, не на main |
| [OpenHands/OpenHands](https://github.com/OpenHands/OpenHands) (MIT) | Agent Canvas UI, [SDK/Agent Server](https://github.com/OpenHands/software-agent-sdk), [automation](https://github.com/OpenHands/automation) (cron/webhooks/run history), [extensions](https://github.com/OpenHands/extensions), GitHub/Slack/Linear, ACP | Отложить полную платформу: тяжёлые Docker/Node/долгосрочная среда и модельный бюджет; перенять durable checkpoint и разделение модулей |
| [penpot/penpot](https://github.com/penpot/penpot) (MPL-2.0) | Компоненты, design tokens, SVG/CSS/HTML, MCP, API, плагины, SaaS/self-host | Кандидат вместо Figma, но не запускать собственный платный сервер |
| [google/filament](https://github.com/google/filament) (Apache-2.0) | Нативный Android PBR 3D-рендерер, GLB/glTF loader, освещение, материалы | Основной кандидат рендера, **не внедрён** в приложение до отдельного Android SDK/Kotlin/CPU/GPU spike |
| [blender/blender](https://github.com/blender/blender) (GPL-3.0-or-later) | Создание собственных объёмных моделей, процедурная генерация, анимация, экспорт GLB | Основной кандидат 3D-художника. Лицензия Blender не делает автоматически созданные модели GPL |
| [KhronosGroup/glTF-Validator](https://github.com/KhronosGroup/glTF-Validator) | Проверка glTF 2.0 JSON, BIN, анимаций, текстур и расширений | Подключить позже как строго фиксированный по хешу независимый validator; до включения проверить лицензию конкретного пакета |
| [sceneview/sceneview](https://github.com/sceneview/sceneview) (Apache-2.0) | Jetpack Compose wrapper Filament, ARCore, glTF, MCP | Отложить: свежая 4.x требует более новую Android-сборочную цепочку, чем текущие compileSdk 35/Kotlin 2.0.21/JVM 17 |

Figma и Lovable **не используются как обязательные исполнители**. Их кредиты, тарифы, ежедневные лимиты и облачные контуры не гарантируют безлимитной работы. Penpot при self-host требует постоянных вычислительных ресурсов; для текущего PILOT лучше Compose code-first и Git design tokens, а для моделей Blender → glTF/GLB → Filament.

## Агентские роли, а не количество работающих серверов

- Координатор: план задач, GitHub Actions, точные HEAD/base SHA, остановка/возобновление.
- Единственный writer: Qwen2.5-Coder-3B с Qwen3-1.7B на резерве, одна изолированная ветка.
- Read-only reviewer: другая локальная модель плюс независимые тесты.
- UI/UX: Compose design-system и Android accessibility, без зависимости от Figma.
- 3D content: будущие Blender scripts, локальные оригинальные GLB и Filament.
- Безопасность/техника: компилятор, asset provenance, экспертное подтверждение упражнений, подписание и физическая приёмка **за пределами агента**.

Запрос на draft PR может идти через проверенный publisher, но текущим агентам **не выдаются** GitHub write-token, ключи подписи, production или права на merge. Это основная идея safe outputs от gh-aw. Независимое разрешение на повышенные привилегии требуется отдельно.

## Первый реализованный визуальный барьер

Файл visualization/manifest.json ведёт реестр оригинальных 3D-моделей. scripts/visual_asset_gate.py сверяет записи с реальным каталогом упражнений, путь только внутри app/src/main/assets/forma3d, отсутствие symlink и дубликатов, SHA-256, лимит размера, бинарный GLB v2, согласованность JSON/BIN и отсутствие любых внешних ресурсов URI. Он **не скачивает, не исполняет и не публикует** содержимое моделей.

Статусы candidate и reviewed разграничены. Reviewed требует указания рецензента и ссылки на [Issue #9](https://github.com/spenskoj90-sudo/forma-fitness-android/issues/9), но наличие ссылки **не является** медицинским подтверждением или независимым аудитом: в выходном отчёте есть только reviewed_claims, а техника и реальные устройства помечены как непроверенные. Полный glTF-Khronos validator, профессиональная оценка техники, скриншоты и runtime на Infinix/Poco — отдельные gates; опция --require-complete сознательно остаётся блокирующей до них.

На момент введения манифест **пуст: 0/37**. Успешный audit PASS означает только корректность ограниченного контракта, не готовые анимации. Настоящий выпуск по-прежнему регулируется [INTEGRATED_ACCEPTANCE.md](INTEGRATED_ACCEPTANCE.md), а чекпоинт находится в [PROJECTS #532](https://github.com/spenskoj90-sudo/alpha-0/issues/532).

GitHub Actions на обычных GitHub-hosted runner в публичном репозитории предоставляет бесплатные минуты согласно документации GitHub, но не безлимитный гарантированный постоянный компьютер: есть SLA/квоты/правила и best-effort cron. Расходы и новые сервисы отдельно согласуются.
