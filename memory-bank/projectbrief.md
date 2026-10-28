# mcp-bsl-context — краткое описание проекта

## Что это

MCP-сервер (Model Context Protocol) для 1С:Предприятие. Две возможности:

1. Справка по API платформы из установленного синтакс-помощника (`shcntx_*.hbk`).
2. Разбор выгрузки конфигурации («Выгрузить конфигурацию в файлы»): метаданные, модули, процедуры,
   поиск и чтение кода.

## Возможности

- 14 инструментов: `search`, `info`, `getMember`, `getMembers`, `getConstructors`, `status`,
  `dumpOpen`, `dumpSearch`, `dumpMetadata`, `dumpCode`, `dumpGrep`, `dumpEntrypoints`,
  `dumpCheck`, `dumpReload`.
- Структурная модель справки: сигнатуры, параметры, возвращаемые типы, примеры, конструкторы,
  значения перечислений.
- SQLite-индекс выгрузки: объекты, реквизиты, табличные части, формы, модули, процедуры.
- Ссылки на метаданные из кода и запросов (`usages`), точки входа (формы, подписки, задания,
  сервисы), проверка модуля/черновика.
- Поиск с ранжированием и подсказками; grep по модулям с владельцем и процедурой.
- Поиск установленных платформ: версии, язык `ru`/`en`, стандартные каталоги ОС.
- Дисковый кэш разбора, фоновая загрузка, `platform_not_ready`/`dump_not_ready`.
- JSON-ответы с `total`/`returned`/`nextOffset`/`hasMore` и пределом 24 000 знаков.
- Транспорты stdio и SSE.

## Архитектура

- Kotlin 2.1, Java 17, Spring Boot 3.5, Spring AI MCP Server 1.0, Jackson, Logback, sqlite-jdbc.
- Слои: `presentation` (MCP, JSON, CLI, benchmark) → `business` (домен и сервисы) →
  `infrastructure` (hbk-парсер, локатор, кэш, каталог; dump-парсер и SQLite-индекс).
- Модель платформы неизменяемая (`PlatformContextData`), публикуется после фоновой загрузки.

## Ключевые компоненты

- `PlatformLocator` — находит установленные платформы и выбирает версию/язык.
- `PlatformContextLoader` — кэш или разбор `.hbk`, маппинг в домен.
- `PlatformContextStorage` — состояния `idle/loading/ready/failed`, ожидание готовности.
- `PlatformCatalog` — ранжирование, подсказки, точные карточки.
- `JsonResponseService`, `DumpJsonService` — JSON и ограничение размера ответа.
- `DumpIndexer`, `DumpIndex`, `DumpStorage` — SQLite-индекс, ссылки, точки входа.
- `BslParser`, `DumpCheckService` — разбор модуля и проверки черновика.
- `PlatformBenchmark` — замеры разбора, кэша и поиска.

## Проверка

`./gradlew test -PPLATFORM_PATH=<путь>` — 114 тестов, включая интеграционные на реальной справке
и тесты выгрузки на синтетическом дампе; `./gradlew ktlintCheck`; `--benchmark` для замеров.
