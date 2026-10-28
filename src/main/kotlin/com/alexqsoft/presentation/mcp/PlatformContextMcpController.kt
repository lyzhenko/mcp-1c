/*
 * Copyright (c) 2024-2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.presentation.mcp

import com.alexqsoft.business.services.ContextSearchService
import com.alexqsoft.business.services.DumpService
import com.alexqsoft.business.services.PlatformStatusService
import com.alexqsoft.exceptions.DomainException
import com.alexqsoft.exceptions.InvalidSearchQueryException
import com.alexqsoft.exceptions.PlatformContextLoadException
import com.alexqsoft.exceptions.PlatformNotReadyException
import com.alexqsoft.exceptions.PlatformTypeNotFoundException
import com.alexqsoft.exceptions.TypeMemberNotFoundException
import com.alexqsoft.presentation.formatters.DumpJsonService
import com.alexqsoft.presentation.formatters.JsonResponseService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * MCP-инструменты справки по API платформы 1С.
 *
 * Все инструменты возвращают JSON. Ошибки не выбрасываются наружу протокола, а приходят
 * ответом с `error.code` и подсказками: агенту проще исправить запрос, чем разбирать текст
 * исключения. Если модель платформы ещё грузится, ответ честно говорит об этом (`platform_not_ready`).
 */
@Service
class PlatformContextMcpController(
    private val searchService: ContextSearchService,
    private val statusService: PlatformStatusService,
    private val formatter: JsonResponseService,
    private val dumpService: DumpService,
    private val dumpFormatter: DumpJsonService,
) {
    private val serverVersion: String =
        PlatformContextMcpController::class.java.`package`?.implementationVersion ?: "dev"

    @Tool(
        name = "search",
        resultConverter = RawJsonResultConverter::class,
        description =
            "Поиск по справке платформы 1С: глобальные методы и свойства, типы, перечисления " +
                "и члены типов. Возвращает совпадения с владельцем (owner) и коротким описанием. " +
                "Полная карточка — info, член конкретного типа — getMember.",
    )
    fun search(
        @ToolParam(
            description =
                "Имя или его часть: «СтрНайти», «ТаблицаЗначений», «РежимСовместимости». " +
                    "Можно писать «Тип.Член»: «Массив.Добавить».",
        )
        query: String,
        @ToolParam(
            description = "Вид элемента: method, property, type, enum. Не задан — искать по всем видам.",
            required = false,
        )
        type: String? = null,
        @ToolParam(description = "Сколько результатов вернуть: 1–50, по умолчанию 10.", required = false)
        limit: Int? = null,
        @ToolParam(description = "Сколько первых совпадений пропустить — для следующей страницы.", required = false)
        offset: Int? = null,
    ): String =
        respond("search", query) {
            formatter.search(searchService.search(query, type, limit, offset))
        }

    @Tool(
        name = "info",
        resultConverter = RawJsonResultConverter::class,
        description =
            "Точная карточка элемента API: сигнатуры с параметрами, возвращаемый тип, примеры " +
                "и примечания. Для типа — состав и счётчики, для перечисления — значения.",
    )
    fun getInfo(
        @ToolParam(description = "Точное имя элемента: «НайтиПоСсылке», «Строка», «Проведен». Можно «Тип.Член».")
        name: String,
        @ToolParam(
            description = "Вид элемента, если имя неоднозначно: method, property, type, enum.",
            required = false,
        )
        type: String? = null,
    ): String =
        respond("info", name) {
            formatter.info(searchService.info(name, type))
        }

    @Tool(
        name = "getMember",
        resultConverter = RawJsonResultConverter::class,
        description =
            "Метод, свойство или значение перечисления конкретного типа: синтаксис, параметры, " +
                "описание и примечания. Пример: typeName=\"Массив\", memberName=\"Добавить\".",
    )
    fun getMember(
        @ToolParam(description = "Имя типа: «ТаблицаЗначений», «Массив», «Структура».")
        typeName: String,
        @ToolParam(description = "Имя метода, свойства или значения перечисления: «Добавить», «Колонки».")
        memberName: String,
    ): String =
        respond("getMember", memberName) {
            formatter.definition(searchService.member(typeName, memberName))
        }

    @Tool(
        name = "getMembers",
        resultConverter = RawJsonResultConverter::class,
        description =
            "Состав типа: методы и свойства (у перечисления — значения). Возвращает страницу; " +
                "у методов есть синтаксис и параметры. Для большого типа используйте offset.",
    )
    fun getMembers(
        @ToolParam(description = "Имя типа: «ТаблицаЗначений», «Массив», «Запрос».")
        typeName: String,
        @ToolParam(description = "Сколько членов вернуть: 1–500, по умолчанию 100.", required = false)
        limit: Int? = null,
        @ToolParam(description = "Сколько членов пропустить — для следующей страницы.", required = false)
        offset: Int? = null,
    ): String =
        respond("getMembers", typeName) {
            formatter.members(searchService.members(typeName, limit, offset))
        }

    @Tool(
        name = "getConstructors",
        resultConverter = RawJsonResultConverter::class,
        description =
            "Конструкторы типа: варианты синтаксиса «Новый <Тип>(...)» с параметрами и описанием. " +
                "Если раздела нет, ответ объясняет, как объект создаётся вместо конструктора.",
    )
    fun getConstructors(
        @ToolParam(description = "Имя типа: «Структура», «ТаблицаЗначений», «Массив».")
        typeName: String,
        @ToolParam(description = "Сколько конструкторов вернуть: 1–100, по умолчанию 20.", required = false)
        limit: Int? = null,
        @ToolParam(description = "Сколько конструкторов пропустить — для следующей страницы.", required = false)
        offset: Int? = null,
    ): String =
        respond("getConstructors", typeName) {
            formatter.constructors(searchService.constructors(typeName, limit, offset))
        }

    @Tool(
        name = "status",
        resultConverter = RawJsonResultConverter::class,
        description =
            "Состояние сервера: загружена ли справка платформы, какая версия и язык, сколько " +
                "элементов в каталоге, включён ли кэш и какие платформы установлены. Не ждёт разбора.",
    )
    fun status(): String =
        respond("status") {
            formatter.status(
                statusService.status(),
                serverVersion,
                McpToolCatalog.TOOLS,
                dump = dumpFormatter.status(dumpService.status()),
            )
        }

    private fun respond(
        tool: String,
        hintName: String? = null,
        block: () -> String,
    ): String =
        try {
            block()
        } catch (error: Throwable) {
            failure(tool, error, hintName)
        }

    private fun failure(
        tool: String,
        error: Throwable,
        hintName: String?,
    ): String {
        val suggestions =
            hintName
                ?.takeIf { it.isNotBlank() }
                ?.let { searchService.suggest(it) }
                .orEmpty()
        return when (error) {
            is PlatformNotReadyException ->
                formatter.error("platform_not_ready", error.message ?: "Справка платформы ещё загружается")

            is PlatformContextLoadException ->
                formatter.error("platform_load_failed", error.message ?: "Не удалось загрузить справку платформы")

            is PlatformTypeNotFoundException ->
                formatter.error("type_not_found", error.message ?: "Тип не найден", suggestions = suggestions)

            is TypeMemberNotFoundException ->
                formatter.error("member_not_found", error.message ?: "Элемент типа не найден", suggestions = suggestions)

            is InvalidSearchQueryException ->
                formatter.error("invalid_request", error.message ?: "Невалидный запрос")

            is DomainException ->
                formatter.error("error", error.message ?: "Ошибка запроса", suggestions = suggestions)

            else -> {
                logger.error(error) { "Ошибка инструмента $tool" }
                formatter.error(
                    "internal",
                    "Внутренняя ошибка сервера: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }
}
