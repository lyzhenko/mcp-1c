/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.presentation.formatters

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import ru.alkoleft.context.business.entities.MethodDefinition
import ru.alkoleft.context.business.entities.PlatformContextData
import ru.alkoleft.context.business.entities.PlatformSourceInfo
import ru.alkoleft.context.business.entities.PlatformTypeDefinition
import ru.alkoleft.context.business.services.PlatformStatus
import ru.alkoleft.context.infrastructure.platform.PlatformOptions
import ru.alkoleft.context.infrastructure.search.SearchHit
import ru.alkoleft.context.infrastructure.search.SearchPage
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonResponseServiceTest {
    private val mapper = jacksonObjectMapper()

    private fun json(text: String): JsonNode {
        assertTrue(text.startsWith("{"), "ответ не JSON: $text")
        return mapper.readTree(text)
    }

    @Test
    fun `поиск отдаёт страницу и курсор продолжения`() {
        val service = JsonResponseService()
        val items = listOf(hit("Первый"), hit("Второй"), hit("Третий"))

        val node = json(service.search(page(items, limit = 2)))

        assertEquals(3, node["total"].asInt())
        assertEquals(2, node["returned"].asInt())
        assertEquals(2, node["nextOffset"].asInt())
        assertTrue(node["hasMore"].asBoolean())
        assertEquals("method", node["results"].first()["kind"].asText())
    }

    @Test
    fun `длинный ответ сокращается и помечается truncated`() {
        val service = JsonResponseService(maxChars = 700)
        val long = "описание ".repeat(500)
        val items = listOf(hit("МетодОдин", description = long), hit("МетодДва", description = long))

        val text = service.search(page(items, limit = 2))
        val node = json(text)

        assertTrue(text.length <= 700, "ответ длиннее лимита: ${text.length}")
        assertTrue(node["truncated"].asBoolean())
    }

    @Test
    fun `пустой поиск возвращает подсказки`() {
        val service = JsonResponseService()
        val node =
            json(
                service.search(
                    page(
                        items = emptyList(),
                        total = 0,
                        suggestions = listOf("СтрНайти"),
                        memberOwners = listOf("Массив.Добавить"),
                    ),
                ),
            )

        assertEquals(0, node["total"].asInt())
        assertEquals("СтрНайти", node["suggestions"].first().asText())
        assertEquals("Массив.Добавить", node["memberOwners"].first().asText())
    }

    @Test
    fun `ошибка содержит код и подсказки`() {
        val service = JsonResponseService()
        val node = json(service.error("not_found", "нет такого", suggestions = listOf("СтрНайти")))

        assertEquals("not_found", node["error"]["code"].asText())
        assertEquals("СтрНайти", node["error"]["suggestions"].first().asText())
    }

    @Test
    fun `status описывает каталог и платформу`() {
        val service = JsonResponseService()
        val data =
            PlatformContextData(
                methods = listOf(MethodDefinition("СтрНайти")),
                types = listOf(PlatformTypeDefinition("Массив")),
                info = PlatformSourceInfo(version = "8.3.27.2214", locale = "ru", path = "shcntx_ru.hbk"),
            )
        val status =
            PlatformStatus(
                state = "ready",
                error = null,
                elapsedMs = 0,
                data = data,
                fromCache = true,
                loadMillis = 5,
                cacheFile = null,
                cacheDir = Path.of("cache"),
                cacheEnabled = true,
                available = emptyList(),
                options = PlatformOptions(),
            )

        val node = json(service.status(status, serverVersion = "test", tools = listOf("search", "status")))

        assertEquals("ready", node["state"].asText())
        assertEquals("8.3.27.2214", node["platform"]["version"].asText())
        assertEquals(1, node["catalog"]["types"].asInt())
        assertTrue(node["server"]["tools"].any { it.asText() == "status" })
    }

    private fun hit(
        name: String,
        description: String = "описание",
    ): SearchHit = SearchHit(MethodDefinition(name = name, description = description))

    private fun page(
        items: List<SearchHit>,
        limit: Int = 10,
        total: Int = items.size,
        suggestions: List<String> = emptyList(),
        memberOwners: List<String> = emptyList(),
    ): SearchPage =
        SearchPage(
            query = "запрос",
            type = null,
            total = total,
            offset = 0,
            limit = limit,
            // Сервис отдаёт уже нарезанную страницу: форматтер только сокращает её по размеру.
            items = items.take(limit),
            suggestions = suggestions,
            memberOwners = memberOwners,
        )
}
