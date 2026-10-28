/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.presentation.mcp

import com.alexqsoft.business.services.DumpCheckService
import com.alexqsoft.business.services.DumpConventionsService
import com.alexqsoft.business.services.DumpGraphService
import com.alexqsoft.business.services.DumpRightsService
import com.alexqsoft.business.services.DumpService
import com.alexqsoft.business.services.DumpSimilarService
import com.alexqsoft.exceptions.DefinitionNotFoundException
import com.alexqsoft.exceptions.DomainException
import com.alexqsoft.exceptions.DumpNotOpenException
import com.alexqsoft.exceptions.DumpNotReadyException
import com.alexqsoft.exceptions.InvalidSearchQueryException
import com.alexqsoft.presentation.formatters.DumpJsonService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Инструменты разбора выгрузки конфигурации 1С.
 *
 * Работают только с выгрузкой («Выгрузить конфигурацию в файлы»), которую открывает `dumpOpen`
 * или ключ `--dump`. Поиск, карточка объекта, код модулей и grep отвечают JSON; ошибки приходят
 * ответом с `error.code`, как и у инструментов справки.
 */
@Service
class DumpMcpController(
    private val dumpService: DumpService,
    private val checkService: DumpCheckService,
    private val graphService: DumpGraphService,
    private val rightsService: DumpRightsService,
    private val similarService: DumpSimilarService,
    private val conventionsService: DumpConventionsService,
    private val formatter: DumpJsonService,
) {
    @Tool(
        name = "dumpOpen",
        description =
            "Открыть выгрузку конфигурации 1С (формат «Выгрузить конфигурацию в файлы») и построить " +
                "индекс. Повторный вызов с тем же путём переиспользует готовый индекс. Возвращает " +
                "состояние индекса: объекты, модули, процедуры, время сборки.",
        resultConverter = RawJsonResultConverter::class,
    )
    fun dumpOpen(
        @ToolParam(description = "Каталог выгрузки, в котором лежит Configuration.xml.")
        path: String,
    ): String =
        respond("dumpOpen") {
            formatter.statusJson(dumpService.open(path))
        }

    @Tool(
        name = "dumpSearch",
        description =
            "Поиск по выгрузке: объекты метаданных, их реквизиты и процедуры модулей. " +
                "Возвращает имя, владельца (owner) и путь; карточка объекта — dumpMetadata.",
        resultConverter = RawJsonResultConverter::class,
    )
    fun dumpSearch(
        @ToolParam(description = "Имя или его часть: «РеализацияТоваровУслуг», «Сумма», «ПередЗаписью».")
        query: String,
        @ToolParam(
            description =
                "Что искать: object — все объекты, routine — процедуры, field — реквизиты, " +
                    "или вид объекта: Catalog, Document, CommonModule и т. п.",
            required = false,
        )
        kind: String? = null,
        @ToolParam(description = "Сколько результатов вернуть: 1–100, по умолчанию 20.", required = false)
        limit: Int? = null,
        @ToolParam(description = "Сколько совпадений пропустить — для следующей страницы.", required = false)
        offset: Int? = null,
    ): String =
        respond("dumpSearch") {
            formatter.search(dumpService.search(query, kind, limit, offset))
        }

    @Tool(
        name = "dumpMetadata",
        description =
            "Карточка объекта метаданных: реквизиты, табличные части с реквизитами, формы, команды, " +
                "значения перечисления и модули с процедурами.",
        resultConverter = RawJsonResultConverter::class,
    )
    fun dumpMetadata(
        @ToolParam(description = "Ссылка «Catalog.РеализацияТоваровУслуг» или имя объекта.")
        ref: String,
        @ToolParam(description = "Сколько реквизитов показать: 1–500, по умолчанию 200.", required = false)
        limit: Int? = null,
    ): String =
        respond("dumpMetadata") {
            formatter.metadata(dumpService.metadata(ref, limit))
        }

    @Tool(
        name = "dumpCode",
        description =
            "Текст модуля BSL с номерами строк. Укажите path (путь модуля из dumpMetadata) " +
                "или id модуля. Продолжение — from/to.",
        resultConverter = RawJsonResultConverter::class,
    )
    fun dumpCode(
        @ToolParam(
            description = "Путь модуля относительно выгрузки, например CommonModules/ОбщегоНазначения/Ext/Module.bsl.",
            required = false,
        )
        path: String? = null,
        @ToolParam(description = "Идентификатор модуля из dumpMetadata, если путь неизвестен.", required = false)
        id: Long? = null,
        @ToolParam(description = "Первая строка, по умолчанию 1.", required = false)
        from: Int? = null,
        @ToolParam(description = "Последняя строка; по умолчанию до 2000 строк от from.", required = false)
        to: Int? = null,
    ): String =
        respond("dumpCode") {
            formatter.code(dumpService.code(path, id, from, to))
        }

    @Tool(
        name = "dumpGrep",
        description =
            "Поиск по тексту файлов выгрузки: подстрока или регулярное выражение, с объектом-владельцем " +
                "и процедурой. По умолчанию ищет только в модулях .bsl и ограничен по времени.",
        resultConverter = RawJsonResultConverter::class,
    )
    fun dumpGrep(
        @ToolParam(description = "Шаблон: подстрока или регулярное выражение при regex=true.")
        pattern: String,
        @ToolParam(description = "Считать pattern регулярным выражением.", required = false)
        regex: Boolean? = null,
        @ToolParam(description = "Не учитывать регистр (по умолчанию да).", required = false)
        ignoreCase: Boolean? = null,
        @ToolParam(description = "Расширения файлов через запятую: bsl, xml. По умолчанию bsl.", required = false)
        extensions: String? = null,
        @ToolParam(description = "Ограничить поиск путями внутри выгрузки, например Catalogs/ (через запятую).", required = false)
        paths: String? = null,
        @ToolParam(description = "Сколько строк контекста вокруг совпадения: 0–5.", required = false)
        context: Int? = null,
        @ToolParam(description = "Сколько совпадений вернуть: 1–200, по умолчанию 50.", required = false)
        limit: Int? = null,
        @ToolParam(description = "Бюджет поиска в миллисекундах: 100–120000, по умолчанию 8000.", required = false)
        deadlineMs: Long? = null,
    ): String =
        respond("dumpGrep") {
            formatter.grep(
                dumpService.grep(
                    pattern = pattern,
                    regex = regex ?: false,
                    ignoreCase = ignoreCase ?: true,
                    extensions = extensions?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() },
                    paths = paths?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() },
                    context = context ?: 0,
                    limit = limit ?: 50,
                    deadlineMs = deadlineMs ?: 8_000,
                ),
            )
        }

    @Tool(
        name = "dumpEntrypoints",
        description =
            "Точки входа конфигурации: обработчики форм, подписки на события, регламентные задания, " +
                "HTTP- и веб-сервисы. Возвращает обработчик и объект-владелец.",
        resultConverter = RawJsonResultConverter::class,
    )
    fun dumpEntrypoints(
        @ToolParam(
            description = "Вид: form, subscription, job, http, web. Не задан — все виды.",
            required = false,
        )
        kind: String? = null,
        @ToolParam(description = "Оставить точки входа объекта: Catalog.Номенклатура.", required = false)
        metadata: String? = null,
        @ToolParam(description = "Сколько записей вернуть: 1–1000, по умолчанию 200.", required = false)
        limit: Int? = null,
    ): String =
        respond("dumpEntrypoints") {
            formatter.entrypoints(dumpService.entrypoints(kind, metadata, limit))
        }

    @Tool(
        name = "dumpCheck",
        description =
            "Проверка модуля BSL или черновика без запуска 1С: неизвестные процедуры, число аргументов " +
                "у своих процедур, общих модулей и методов платформы, отсутствующие метаданные, " +
                "неиспользуемые переменные и параметры, функция без «Возврат».",
        resultConverter = RawJsonResultConverter::class,
    )
    fun dumpCheck(
        @ToolParam(description = "Путь модуля в выгрузке; можно не указывать, если передан text.", required = false)
        path: String? = null,
        @ToolParam(description = "Идентификатор модуля из dumpMetadata.", required = false)
        id: Long? = null,
        @ToolParam(description = "Текст черновика: проверяется без файла в выгрузке.", required = false)
        text: String? = null,
    ): String =
        respond("dumpCheck") {
            formatter.check(checkService.check(path, id, text))
        }

    @Tool(
        name = "dumpNode",
        description =
            "Карточка узла графа: процедура (routine:<id>), модуль (module:<id>), объект метаданных " +
                "(Catalog.Номенклатура) или внешний вызов (external:Имя).",
        resultConverter = RawJsonResultConverter::class,
    )
    fun dumpNode(
        @ToolParam(description = "Идентификатор узла: routine:123, module:45, Catalog.Номенклатура.")
        id: String,
    ): String =
        respond("dumpNode") {
            formatter.node(graphService.node(id))
        }

    @Tool(
        name = "dumpNeighbors",
        description =
            "Связи узла: входящие и исходящие вызовы, обращения к метаданным. " +
                "direction — in, out, both; kinds — calls, metadata.",
        resultConverter = RawJsonResultConverter::class,
    )
    fun dumpNeighbors(
        @ToolParam(description = "Идентификатор узла: routine:123, module:45, Catalog.Номенклатура.")
        id: String,
        @ToolParam(description = "Направление: in, out, both (по умолчанию both).", required = false)
        direction: String? = null,
        @ToolParam(description = "Виды связей через запятую: calls, metadata.", required = false)
        kinds: String? = null,
        @ToolParam(description = "Сколько связей вернуть: 1–500, по умолчанию 100.", required = false)
        limit: Int? = null,
    ): String =
        respond("dumpNeighbors") {
            formatter.neighbors(
                graphService.neighbors(
                    id = id,
                    direction = direction,
                    kinds = kinds?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() },
                    limit = limit,
                ),
            )
        }

    @Tool(
        name = "dumpTypes",
        description =
            "Таблица символов модуля: параметры, локальные переменные с простым выводом типа и вызовы. " +
                "Аргументы: path или id модуля, либо text черновика.",
        resultConverter = RawJsonResultConverter::class,
    )
    fun dumpTypes(
        @ToolParam(description = "Путь модуля в выгрузке.", required = false)
        path: String? = null,
        @ToolParam(description = "Идентификатор модуля из dumpMetadata.", required = false)
        id: Long? = null,
        @ToolParam(description = "Текст черновика.", required = false)
        text: String? = null,
    ): String =
        respond("dumpTypes") {
            formatter.types(graphService.types(path, id, text))
        }

    @Tool(
        name = "dumpRights",
        description =
            "Права ролей и условия RLS: role — имя роли, metadata — Catalog.Номенклатура, " +
                "condition — подстрока условия ограничения.",
        resultConverter = RawJsonResultConverter::class,
    )
    fun dumpRights(
        @ToolParam(description = "Имя роли; не задано — все роли.", required = false)
        role: String? = null,
        @ToolParam(description = "Объект метаданных: Catalog.Номенклатура.", required = false)
        metadata: String? = null,
        @ToolParam(description = "Подстрока условия RLS.", required = false)
        condition: String? = null,
        @ToolParam(description = "Сколько прав вернуть: 1–1000, по умолчанию 200.", required = false)
        limit: Int? = null,
    ): String =
        respond("dumpRights") {
            formatter.rights(rightsService.rights(role, metadata, condition, limit))
        }

    @Tool(
        name = "dumpSimilar",
        description =
            "Похожие процедуры по общим вызовам и объектам метаданных. Источник — path/id модуля " +
                "или text черновика; routine — имя процедуры внутри модуля.",
        resultConverter = RawJsonResultConverter::class,
    )
    fun dumpSimilar(
        @ToolParam(description = "Путь модуля в выгрузке.", required = false)
        path: String? = null,
        @ToolParam(description = "Идентификатор модуля из dumpMetadata.", required = false)
        id: Long? = null,
        @ToolParam(description = "Текст черновика.", required = false)
        text: String? = null,
        @ToolParam(description = "Имя процедуры в модуле.", required = false)
        routine: String? = null,
        @ToolParam(description = "Сколько результатов вернуть: 1–100, по умолчанию 20.", required = false)
        limit: Int? = null,
    ): String =
        respond("dumpSimilar") {
            formatter.similar(similarService.similar(path, id, text, routine, limit))
        }

    @Tool(
        name = "dumpConventions",
        description =
            "Проверка конвенций кода 1С: «Сообщить» на сервере, запрос/запись в цикле, " +
                "«Попытка» без «Исключение», «ВызватьИсключение» без текста, TODO.",
        resultConverter = RawJsonResultConverter::class,
    )
    fun dumpConventions(
        @ToolParam(description = "Путь модуля в выгрузке.", required = false)
        path: String? = null,
        @ToolParam(description = "Идентификатор модуля из dumpMetadata.", required = false)
        id: Long? = null,
        @ToolParam(description = "Текст черновика.", required = false)
        text: String? = null,
    ): String =
        respond("dumpConventions") {
            formatter.conventions(conventionsService.conventions(path, id, text))
        }

    @Tool(
        name = "dumpReload",
        description =
            "Перестроить индекс выгрузки: вызовите после изменения файлов на диске. " +
                "Возвращает состояние индекса и время сборки.",
        resultConverter = RawJsonResultConverter::class,
    )
    fun dumpReload(): String =
        respond("dumpReload") {
            formatter.statusJson(dumpService.reload())
        }

    private fun respond(
        tool: String,
        block: () -> String,
    ): String =
        try {
            block()
        } catch (error: Throwable) {
            failure(tool, error)
        }

    private fun failure(
        tool: String,
        error: Throwable,
    ): String =
        when (error) {
            is DumpNotOpenException -> formatter.error("dump_not_open", error.message ?: "Выгрузка не открыта")

            is DumpNotReadyException -> formatter.error("dump_not_ready", error.message ?: "Индекс выгрузки не готов")

            is DefinitionNotFoundException -> formatter.error("not_found", error.message ?: "Объект не найден")

            is InvalidSearchQueryException -> formatter.error("invalid_request", error.message ?: "Невалидный запрос")

            is DomainException -> formatter.error("error", error.message ?: "Ошибка запроса")

            else -> {
                logger.error(error) { "Ошибка инструмента $tool" }
                formatter.error(
                    "internal",
                    "Внутренняя ошибка сервера: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
}
