# Установка и запуск

## Требования

- Java 21+.
- Установленная платформа 1С:Предприятие 8.3 (нужен `bin/shcntx_ru.hbk`; для английской справки —
  `shcntx_en.hbk`, если она есть в установке).

## Сборка

```bash
./gradlew clean bootJar
# build/libs/mcp-bsl-context-<версия>.jar
```

`clean` нужен, чтобы в `build/libs` не накапливались jar прошлых версий: имя содержит версию и
коммит, поэтому после смены ветки файлов становится несколько, а маска `*.jar` подставляет их все.

## Запуск

**bash (Linux, macOS, Git Bash):**

```bash
jar=$(ls -t build/libs/mcp-bsl-context-*.jar | head -1)

# платформа в стандартном каталоге — путь можно не указывать
java -Dfile.encoding=UTF-8 -jar "$jar"

# явный путь: каталог установки, каталог версии или сам .hbk
java -Dfile.encoding=UTF-8 -jar "$jar" \
  --platform-path "/opt/1cv8/x86_64/8.3.27.2214"

# конкретная версия и язык
java -Dfile.encoding=UTF-8 -jar "$jar" --version 8.3.27 --locale ru
```

**PowerShell (Windows):**

```powershell
$jar = (Get-ChildItem build/libs/mcp-bsl-context-*.jar |
        Sort-Object LastWriteTime | Select-Object -Last 1).FullName

# платформа в стандартном каталоге — путь можно не указывать
java "-Dfile.encoding=UTF-8" -jar $jar

# явный путь: каталог установки, каталог версии или сам .hbk
java "-Dfile.encoding=UTF-8" -jar $jar `
  --platform-path "C:\Program Files\1cv8\8.3.27.2214"

# конкретная версия и язык
java "-Dfile.encoding=UTF-8" -jar $jar --version 8.3.27 --locale ru
```

В PowerShell ключ `-D...` обязательно в кавычках: литеральный аргумент вида `-имя.имя` оболочка
разбирает на два (`-Dfile` и `.encoding=UTF-8`), и Java сообщает
`Could not find or load main class .encoding=UTF-8`. Продолжение строки — обратный апостроф
(`` ` ``), а не `\`: с `\` PowerShell выдаёт `Отсутствует выражение после унарного оператора "--"`.
Маску `*.jar` PowerShell в аргументах внешних программ не раскрывает — подставляйте конкретный путь,
как в сниппете выше.

В режиме stdio процесс запускает MCP-клиент и stdout принадлежит протоколу, поэтому при ручном
запуске сервер просто ждёт JSON-RPC на stdin. Для проверки используйте `--help` или `--benchmark`.

## Параметры

| Ключ | Значение |
|---|---|
| `-p, --platform-path` | каталог установки, каталог версии или файл `shcntx_*.hbk` |
| `--platform-root` | дополнительный корень поиска платформ (ключ можно повторять) |
| `--version` | версия: точная (`8.3.27.2214`) или префикс (`8.3.27`) |
| `--locale` | `ru` (по умолчанию) или `en` |
| `--cache-dir` | каталог кэша разбора |
| `--no-cache` | не читать и не писать кэш |
| `--refresh` | пересобрать кэш из `.hbk` |
| `--lazy` | начать разбор при первом запросе, а не при старте |
| `--dump` | каталог выгрузки конфигурации |
| `--dump-index-dir` | каталог SQLite-индексов выгрузки |
| `-m, --mode` | `stdio` (по умолчанию) или `sse` |
| `--port` | порт SSE (по умолчанию 8080) |
| `-v, --verbose` | отладочное логирование |
| `--benchmark` | замеры разбора, кэша и поиска, затем выход |
| `-h, --help` | справка |

## Переменные окружения

| Переменная | Значение |
|---|---|
| `PLATFORM_CONTEXT_PATH` | путь к платформе |
| `PLATFORM_ROOT` | корень поиска платформ |
| `PLATFORM_VERSION` | нужная версия |
| `PLATFORM_LOCALE` | язык справки |
| `PLATFORM_CACHE_DIR` | каталог кэша |
| `PLATFORM_CACHE_ENABLED` | `false` отключает кэш |
| `PLATFORM_REFRESH` | `true` — пересобрать кэш |
| `PLATFORM_WARMUP` | `false` — не разбирать при старте |
| `MCP_RESPONSE_LIMIT` | предел ответа в символах (по умолчанию 24000) |
| `PLATFORM_WAIT_TIMEOUT_MS` | ожидание готовности справки (по умолчанию 25000) |
| `DUMP_PATH` | каталог выгрузки (аналог `--dump`) |
| `DUMP_INDEX_DIR` | каталог индексов выгрузки |
| `DUMP_WARMUP` | `false` — не строить индекс при старте |
| `DUMP_WAIT_TIMEOUT_MS` | ожидание готовности индекса (по умолчанию 60000) |
| `DUMP_WATCH_SECONDS` | проверять изменения выгрузки каждые N секунд (0 — выключено) |
| `LOG_FILE` | файл лога (по умолчанию `mcp-server.log`) |

`DUMP_PATH` (и `--dump`) принимает несколько каталогов через `;`: первый — база, следующие —
расширения. Файл расширения перекрывает файл базы с тем же относительным путём.
`DUMP_WATCH_SECONDS` сверяет `ConfigDumpInfo.xml` и mtime `.bsl`: изменившиеся модули
перечитываются частично, изменения метаданных, форм и ролей перестраивают индекс целиком.

Пример:

```bash
PLATFORM_CONTEXT_PATH="/opt/1cv8/x86_64/8.3.27.2214" \
PLATFORM_LOCALE=ru \
LOG_FILE="/tmp/mcp-server.log" \
java -jar build/libs/mcp-bsl-context.jar
```

## Кэш

Разобранная модель кэшируется в JSON:

- Windows: `%LOCALAPPDATA%\mcp-bsl-context`
- Linux: `~/.cache/mcp-bsl-context`
- macOS: `~/Library/Caches/mcp-bsl-context`

Кэш привязан к пути, размеру и времени изменения `.hbk`, языку и версии схемы. После обновления
платформы он пересобирается сам; `--refresh` форсирует пересборку, `--no-cache` отключает кэш.
В `status` видно файл и его размер.

## Индекс выгрузки

Индекс выгрузки — отдельная SQLite-база, файлы выгрузки не меняются:

- Windows: `%LOCALAPPDATA%\mcp-bsl-context\dumps\dump-<hash>.db`
- Linux/macOS: в каталоге кэша пользователя, подкаталог `dumps`

Путь задаётся `--dump-index-dir` или `DUMP_INDEX_DIR`. Индекс строится при первом открытии выгрузки
(6,1 ГБ — около 28 секунд), затем открывается за доли секунды. Схема версионирована: при обновлении
сервера индекс пересобирается сам. После правок файлов на диске вызовите `dumpReload`.

## SSE

```bash
java -jar build/libs/mcp-bsl-context.jar --mode sse --port 9000 --platform-path "/opt/1cv8/x86_64/8.3.27.2214"
```

Подключение: `http://localhost:9000/mcp/sse`. Подробнее — [SSE_USAGE.md](SSE_USAGE.md).

## Логирование

- Файл `mcp-server.log` с ротацией (10 МБ, 5 файлов); путь — `LOG_FILE`.
- stdout в stdio-режиме целиком принадлежит протоколу, поэтому логи в него не пишутся.
- `--verbose` включает DEBUG.

## Диагностика

### Сервер не находит платформу

1. Проверьте, что в каталоге версии есть `bin/shcntx_ru.hbk` (или `shcntx_en.hbk`).
2. Укажите путь явно: `--platform-path "<каталог версии>"`.
3. `status` показывает `availablePlatforms` — какие версии и языки нашлись.

### Инструменты отвечают `platform_not_ready` или `dump_not_ready`

Справка разбирается около 2 секунд (из кэша — доли секунды), индекс выгрузки 6,1 ГБ — около
28 секунд. Если состояние `failed`, причина указана в `status.error`; чаще всего это неверный путь
или повреждённый файл.

### `dump_not_open`

Выгрузка не задана: передайте `--dump "<каталог>"` или вызовите `dumpOpen` с путём.

### Ответ обрезан

Увеличьте `MCP_RESPONSE_LIMIT` или используйте `offset`/`limit`. Поле `nextOffset` показывает,
с какого места продолжить.

### SSE не подключается

Проверьте `--mode sse`, порт и firewall. Порт по умолчанию — 8080.

## Совместимость

- MCP-протокол: 2024-11-05 (Spring AI MCP Server 1.0.0).
- Java 21+.
- ОС: Windows, Linux, macOS (пути поиска платформы различаются).
- Транспорты: stdio, HTTP/SSE.
