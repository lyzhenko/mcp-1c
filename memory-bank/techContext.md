# Технический контекст mcp-bsl-context

## Стек

- **Язык:** Kotlin 2.1.20, Java 17.
- **Фреймворк:** Spring Boot 3.5.3.
- **MCP:** Spring AI MCP Server 1.0.0 (stdio и SSE).
- **JSON:** Jackson 2.15.2 + Kotlin Module.
- **Хранилище выгрузки:** SQLite через sqlite-jdbc.
- **Логирование:** Logback 1.5.18 + SLF4J.
- **Тесты:** JUnit 5, kotlin.test, AssertJ.
- **Сборка:** Gradle 8, Kotlin DSL, ktlint, jacoco, git-versioning.

## Слои

```
presentation/   PlatformContextMcpController, DumpMcpController (@Tool), JsonResponseService,
                DumpJsonService, ServerOptions, PlatformBenchmark
business/       Definition/Method/Property/Type/Enum; ContextSearchService; DumpService;
                PlatformStatusService; страницы
infrastructure/ hbk-парсер, PlatformLocator, PlatformCache, PlatformContextLoader/Storage,
                PlatformCatalog; dump-парсер, DumpIndexer/Index/Storage
```

Зависимости направлены внутрь: presentation знает business и инфраструктурные типы конфигурации,
business работает с неизменяемой моделью, infrastructure отвечает за файлы и разбор.

## Ключевые решения

### Модель платформы

`PlatformContextData` — неизменяемый снимок: глобальные методы и свойства, типы с методами,
свойствами и конструкторами, перечисления со значениями, метаданные источника. Собирается один раз
и публикуется через volatile-ссылку; чтение без блокировок.

### Загрузка

`PlatformContextStorage` стартует разбор в фоновом потоке из `@PostConstruct` (в stdio-режиме
MCP-сервер занимает основной поток раньше `ApplicationRunner`). Состояния `idle → loading → ready|failed`.
Инструменты ждут готовности не дольше `platform.wait-timeout-ms`; `status` не ждёт никогда.

### Кэш

`PlatformCache` хранит JSON в каталоге пользователя; ключ — путь, размер, время изменения `.hbk`,
язык и версия схемы. Запись атомарная. Схема поднимается при изменении модели или правил разбора.

### Поиск

`PlatformCatalog` строится один раз на модель и ранжирует линейным проходом: точное имя →
префикс → вхождение → английское имя → описание → все слова. Отдельно разрешается «Тип.Член».
Подсказки — по вхождению и расстоянию Левенштейна (≤ 2), плюс типы-владельцы членов.

### Ответы

`JsonResponseService` собирает JSON вручную (Jackson), ограничивает размер (`response-limit`,
по умолчанию 24 000), отдаёт `nextOffset`/`hasMore` и сокращает длинные строки с `truncated`.
`RawJsonResultConverter` отключает повторную сериализацию строки в Spring AI.

### Выгрузка конфигурации

`DumpIndexer` читает объектные `*.xml` и все `.bsl`, строит SQLite-индекс
(`DumpIndex.SCHEMA_VERSION` = 2) во временном файле и подменяет готовый. Владелец модуля
определяется по каталогу объекта, вид — по суффиксу пути. `BslModuleScanner` находит процедуры,
экспорт и границы; `DumpTextReader` снимает BOM, откатывается на windows-1251 и нормализует
переводы строк. `DumpStorage` открывает готовый индекс по пути и схеме или строит в фоне.
`dumpGrep` берёт список модулей из индекса, а не обходит 65 000 файлов.

## Инструменты

- `search(query, type?, limit?, offset?)`
- `info(name, type?)`
- `getMember(typeName, memberName)`
- `getMembers(typeName, limit?, offset?)`
- `getConstructors(typeName, limit?, offset?)`
- `dumpOpen(path)`
- `dumpSearch(query, kind?, limit?, offset?)`
- `dumpMetadata(ref, limit?)`
- `dumpCode(path?, id?, from?, to?)`
- `dumpGrep(pattern, regex?, ignoreCase?, extensions?, paths?, context?, limit?, deadlineMs?)`
- `dumpReload()`
- `status()`

Все возвращают JSON; ошибки — `error.code` + `suggestions`/`memberOwners`.

## Конфигурация

- `application.yml` — Spring, `platform.context.*` и `dump.*`.
- `logback-mcp.xml` — логи только в файл, stdout свободен для протокола.
- CLI (`ServerOptions`) переопределяет настройки через системные свойства.

## Тестирование

- Юнит-тесты: маппинг hbk → домен, каталог и ранжирование, кэш, локатор платформ, JSON-страницы,
  разбор CLI.
- Выгрузка: синтетический дамп в `src/test/resources/dump` — индекс, поиск, карточка, код, grep,
  переиспользование индекса.
- Интеграционные: `@SpringBootTest` на реальной справке установленной платформы (нужен
  `-PPLATFORM_PATH`), проверяют JSON-контракт, сигнатуры, параметры и конструкторы.
- Всего 107 тестов; в CI дополнительно ktlint и jacoco.

## Ограничения

- Разбирается только `shcntx_*.hbk`; язык запросов и описание встроенного языка не индексируются.
- Модель и индекс — снимки; после обновления платформы нужен перезапуск, после правок выгрузки —
  `dumpReload`.
- Графа вызовов и проверки BSL-кода пока нет.
- Разбор BSL-кода и выгрузки конфигурации не входит в задачи сервера.
