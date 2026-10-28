# Возможности и инструменты

Все инструменты возвращают JSON. Ошибки приходят ответом с `error.code`, а не исключением протокола.

## search

Поиск по справке: глобальные методы и свойства, типы, перечисления, значения перечислений и члены
типов. Ранжирование: точное имя → префикс → вхождение в имя → английское имя → описание →
все слова запроса. Запись «Тип.Член» (`Массив.Добавить`) разрешается точно.

Аргументы:

- `query` — имя или его часть; можно «Тип.Член».
- `type` — `method`, `property`, `type`, `enum`; не задан — все виды.
- `limit` — 1–50, по умолчанию 10.
- `offset` — сколько совпадений пропустить.

Ответ:

```json
{
  "query": "СтрНайти",
  "total": 6,
  "returned": 1,
  "nextOffset": 1,
  "hasMore": true,
  "results": [
    {
      "kind": "method",
      "name": "СтрНайти",
      "nameEn": "StrFind",
      "description": "Находит первое вхождение искомой строки…"
    },
    {
      "kind": "method",
      "name": "Добавить",
      "owner": "Массив",
      "description": "Добавляет элемент в конец массива."
    }
  ]
}
```

Если совпадений нет, в ответе есть `suggestions` (похожие имена) и `memberOwners`
(типы, у которых есть элемент с таким именем).

## info

Точная карточка. Для метода — `signatures` (синтаксис, параметры с типами и обязательностью,
описание варианта), `returnType`, `example`, `note`, `related`. Для типа — `counts`, до 40 членов
и конструкторы (если членов больше, подсказка вызывает `getMembers`). Для перечисления — значения.
Для значения перечисления — имя и описание.

Аргументы: `name`, `type` (если имя неоднозначно).

```json
{
  "found": true,
  "kind": "method",
  "name": "СтрНайти",
  "nameEn": "StrFind",
  "global": true,
  "returnType": "Число",
  "description": "Находит первое вхождение искомой строки…",
  "signatures": [
    {
      "syntax": "СтрНайти(<Строка>, <ПодстрокаПоиска>, <НаправлениеПоиска>)",
      "parameters": [
        {"name": "Строка", "type": "Строка", "required": true, "description": "Исходная строка."},
        {"name": "ПодстрокаПоиска", "type": "Строка", "required": true},
        {"name": "НаправлениеПоиска", "type": "НаправлениеПоиска", "required": false}
      ]
    }
  ]
}
```

## getMember

Метод, свойство или значение перечисления конкретного типа. Аргументы: `typeName`, `memberName`.

```json
{
  "found": true,
  "kind": "method",
  "name": "Добавить",
  "signatures": [
    {"syntax": "Добавить(<Значение>)", "parameters": [{"name": "Значение", "type": "Произвольный", "required": false}]}
  ]
}
```

## getMembers

Состав типа: методы и свойства (для перечисления — значения). Аргументы: `typeName`,
`limit` (1–500, по умолчанию 100), `offset`.

Ответ — страница: `type`, `total`, `offset`, `returned`, `nextOffset`, `hasMore`, `results`.
У методов в `signatures` есть синтаксис и параметры.

## getConstructors

Конструкторы типа: `name` (заголовок варианта), `syntax`, `parameters`, `description`.
Аргументы: `typeName`, `limit` (1–100, по умолчанию 20), `offset`.

Если раздела конструкторов в справке нет, `total` равен 0, а `hint` объясняет, что объект создаётся
методами или фабрикой.

## status

Состояние сервера без ожидания разбора:

```json
{
  "state": "ready",
  "server": {"name": "mcp-bsl-context", "version": "0.4.0", "tools": ["search", "…", "status"]},
  "platform": {
    "version": "8.3.27.2214",
    "locale": "ru",
    "path": "C:\\Program Files\\1cv8\\8.3.27.2214\\bin\\shcntx_ru.hbk",
    "sourceBytes": 40763557,
    "loadedAt": "2026-10-28T10:23:03Z",
    "parseMillis": 2100,
    "fromCache": false,
    "loadMillis": 2100
  },
  "catalog": {"types": 1739, "methods": 500, "properties": 100, "enums": 681, "total": 3020},
  "cache": {"enabled": true, "dir": "C:\\Users\\…\\mcp-bsl-context", "file": "…json", "sizeBytes": 17321169},
  "availablePlatforms": [{"version": "8.3.27.2214", "path": "…\\bin", "locales": ["root", "ru"]}],
  "options": {"locale": "ru", "version": null, "waitTimeoutMs": 25000, "responseLimit": 24000}
}
```

`state`: `idle` (разбор не начат), `loading`, `ready`, `failed`.

## Выгрузка конфигурации

Инструменты с префиксом `dump` работают после `--dump <каталог>` или `dumpOpen`.

### dumpOpen

Открывает выгрузку и строит или переиспользует индекс. Аргумент: `path` — каталог с `Configuration.xml`.
Возвращает состояние индекса (как `status`, блок `dump`).

```json
{
  "state": "ready",
  "path": "D:\\dump\\1c_files",
  "info": {
    "configuration": "УправлениеНебольшойФирмой",
    "version": "3.0.12.261",
    "objects": 16256,
    "fields": 67054,
    "modules": 14737,
    "routines": 256048,
    "buildMillis": 15233
  }
}
```

### dumpSearch

Поиск по объектам, реквизитам и процедурам. Аргументы: `query`, `kind`
(`object`, `routine`, `field` или вид объекта: `Catalog`, `Document`, `CommonModule`),
`limit` (1–100), `offset`.

```json
{
  "query": "Номенклатура",
  "total": 984,
  "returned": 3,
  "nextOffset": 3,
  "hasMore": true,
  "results": [
    {"kind": "Catalog", "name": "Номенклатура", "synonym": "Номенклатура", "path": "Catalogs/Номенклатура.xml", "id": 465},
    {"kind": "field", "name": "Номенклатура", "owner": "AccumulationRegister.ВыпускПродукции", "detail": "Dimension"}
  ]
}
```

### dumpMetadata

Карточка объекта: `ref` (`Catalog.Номенклатура`) или имя; `limit` — сколько реквизитов показать (1–500).
Возвращает `attributes`, `tabularSections` с вложенными реквизитами, `enumValues`, `forms`, `commands`,
`templates`, `modules` с процедурами, `counts` и `usages`.

`usages` — кто читает объект:

```json
{
  "usages": {
    "total": 686,
    "byContext": {"code": 423, "query": 263},
    "readers": [
      {"module": "Catalogs/Номенклатура/Ext/ObjectModule.bsl", "routine": "ПередЗаписью",
       "context": "code", "line": 120}
    ]
  }
}
```

Ссылки собираются из кода (`Справочники.Товары`) и текстов запросов (`ИЗ Справочник.Товары`).

### dumpCode

Текст модуля с номерами строк. Аргументы: `path` (путь модуля из карточки) или `id`, `from`, `to`.
По умолчанию — до 2000 строк; продолжение через `from`.

```json
{
  "path": "CommonModules/ОбщегоНазначения/Ext/Module.bsl",
  "moduleKind": "Module",
  "owner": "CommonModule.ОбщегоНазначения",
  "totalLines": 11081,
  "from": 1,
  "to": 10,
  "text": "    1  // ...\n    9  #Область ПрограммныйИнтерфейс"
}
```

### dumpGrep

Поиск по тексту файлов. Аргументы: `pattern`, `regex`, `ignoreCase`, `extensions` (по умолчанию `bsl`),
`paths`, `context` (0–5), `limit` (1–200), `deadlineMs` (по умолчанию 8000). Список модулей берётся
из индекса, поэтому поиск не обходит всю выгрузку. В каждом совпадении — `owner` и `routine`.
`complete: false` означает, что поиск остановлен по лимиту или времени.

### dumpEntrypoints

Точки входа: обработчики форм, подписки на события, регламентные задания, HTTP- и веб-сервисы.
Аргументы: `kind` (`form`, `subscription`, `job`, `http`, `web`), `metadata` (`Catalog.Номенклатура`),
`limit` (1–1000). В ответе — `kind`, `name`, `owner`, `handler`, `event`, `path`, `sources`
и счётчики по видам.

```json
{
  "kind": "form",
  "metadata": "Catalog.Номенклатура",
  "total": 3,
  "counts": {"form": 52370, "subscription": 454, "job": 189, "http": 17, "web": 18},
  "results": [
    {"kind": "form", "name": "ФормаЭлемента", "owner": "Catalog.Номенклатура",
     "handler": "ПриСозданииНаСервере", "event": "OnCreateAtServer",
     "path": "Catalogs/Номенклатура/Forms/ФормаЭлемента/Ext/Form.xml"}
  ]
}
```

### dumpCheck

Проверка модуля или черновика без запуска 1С. Аргументы: `path` (модуль из выгрузки),
`id` (идентификатор модуля) или `text` (черновик). Проверяет:

- неизвестные процедуры — если загружена справка платформы (иначе в `notes` будет оговорка);
- число аргументов у своих процедур, методов общих модулей и глобальных методов платформы;
- отсутствующие объекты метаданных (`Справочники.НетТакого`) с подсказками;
- неиспользуемые переменные и параметры (для модулей форм параметры не проверяются);
- функцию без `Возврат`; дубли имён процедур.

Ответ: `routines`, `calls`, `metadataAccesses`, `problems` (`line`, `severity`, `code`, `message`,
`hint`), `counts`, `notes`. Проверки консервативные: предупреждение лучше ложной ошибки.

### dumpReload

Перестроить индекс после изменения файлов на диске.

## Ограничение размера и продолжение

Ответ ограничен `MCP_RESPONSE_LIMIT` (по умолчанию 24 000 знаков). Если он не влезает, сервер
уменьшает страницу и отдаёт `nextOffset`; JSON остаётся целым. Если не влезает даже один элемент,
длинные строки сокращаются, а в ответе появляется `"truncated": true` и подсказка сузить запрос.

## Ошибки

```json
{
  "error": {
    "code": "member_not_found",
    "message": "Элемент «Добавить2» в типе «Массив» не найден",
    "suggestions": ["Добавить"]
  }
}
```

| Код | Когда |
|---|---|
| `invalid_request` | пустой запрос, неизвестный `type`, неверный `limit` |
| `not_found` | имя не найдено |
| `type_not_found` | тип не найден (в `getMember`, `getMembers`, `getConstructors`) |
| `member_not_found` | член типа не найден |
| `platform_not_ready` | разбор ещё идёт или упал; в сообщении — причина |
| `platform_load_failed` | ошибка разбора `.hbk` |
| `dump_not_open` | выгрузка не открыта: нет `--dump` и не вызывался `dumpOpen` |
| `dump_not_ready` | индекс выгрузки ещё строится или построить не удалось |
| `internal` | непредвиденная ошибка (подробности — в логе сервера) |
