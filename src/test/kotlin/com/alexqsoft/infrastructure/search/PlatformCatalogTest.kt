/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.infrastructure.search

import com.alexqsoft.business.entities.EnumDefinition
import com.alexqsoft.business.entities.EnumValueDefinition
import com.alexqsoft.business.entities.MethodDefinition
import com.alexqsoft.business.entities.ParameterDefinition
import com.alexqsoft.business.entities.PlatformContextData
import com.alexqsoft.business.entities.PlatformTypeDefinition
import com.alexqsoft.business.entities.PropertyDefinition
import com.alexqsoft.business.entities.Signature
import com.alexqsoft.business.valueobjects.ApiType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformCatalogTest {
    private val catalog = PlatformCatalog(sampleData())

    @Test
    fun `точное имя ранжируется выше вхождения`() {
        val page = catalog.search("СтрНайти")
        val first = page.items.first()
        assertEquals("СтрНайти", first.definition.name)
    }

    @Test
    fun `поиск идёт по описанию`() {
        val page = catalog.search("подстроку")
        assertTrue(page.items.any { it.definition.name == "СтрНайти" }, "нет совпадения по описанию: ${page.items}")
    }

    @Test
    fun `член типа находится по записи Тип Член`() {
        val page = catalog.search("Массив.Добавить")
        val hit = page.items.first()
        assertEquals("Добавить", hit.definition.name)
        assertEquals("Массив", hit.owner)
    }

    @Test
    fun `у члена типа в результатах есть владелец`() {
        val page = catalog.search("Добавить")
        val hit = page.items.first()
        assertEquals("Добавить", hit.definition.name)
        assertEquals("Массив", hit.owner)
    }

    @Test
    fun `фильтр по виду оставляет только этот вид`() {
        val methods = catalog.search("Добавить", ApiType.METHOD)
        assertTrue(methods.items.isNotEmpty())
        assertTrue(methods.items.all { it.definition is MethodDefinition })

        val types = catalog.search("Массив", ApiType.TYPE)
        assertTrue(types.items.all { it.definition is PlatformTypeDefinition })
    }

    @Test
    fun `значения перечисления находятся с владельцем`() {
        val page = catalog.search("Приход")
        val hit = page.items.first()
        assertEquals("Приход", hit.definition.name)
        assertEquals("ВидДвижения", hit.owner)
    }

    @Test
    fun `ошибка в имени даёт подсказки`() {
        val page = catalog.search("СтрНайди")
        assertTrue(page.suggestions.any { it == "СтрНайти" }, "нет подсказки: ${page.suggestions}")
    }

    @Test
    fun `memberOwners перечисляет типы с таким членом`() {
        assertTrue(catalog.memberOwners("Добавить").contains("Массив.Добавить"))
    }

    @Test
    fun `составной запрос находит тип`() {
        assertEquals("Массив", catalog.findType("массив")?.name)
        val movements = catalog.findEnum("ВидДвижения")
        assertEquals("Приход", movements?.values?.first()?.name)
    }

    private fun sampleData(): PlatformContextData =
        PlatformContextData(
            methods =
                listOf(
                    method("СтрНайти", "StrFind", "Находит подстроку в строке"),
                    method("Записать", "Write", "Записывает данные"),
                ),
            properties = listOf(PropertyDefinition(name = "РабочаяДата", description = "дата")),
            types =
                listOf(
                    PlatformTypeDefinition(
                        name = "Массив",
                        description = "коллекция значений",
                        methods =
                            listOf(
                                method(
                                    name = "Добавить",
                                    description = "добавляет значение",
                                    signatures =
                                        listOf(
                                            Signature(
                                                syntax = "Добавить(<Значение>)",
                                                parameters = listOf(ParameterDefinition("Значение", "Произвольный")),
                                            ),
                                        ),
                                ),
                                method("Количество", "Count", "сколько элементов"),
                            ),
                    ),
                    PlatformTypeDefinition(
                        name = "Строка",
                        description = "строка",
                        methods = listOf(method("Длина", "Length", "длина строки")),
                    ),
                ),
            enums =
                listOf(
                    EnumDefinition(
                        name = "ВидДвижения",
                        values =
                            listOf(
                                EnumValueDefinition("Приход", "Receipt", "приход"),
                                EnumValueDefinition("Расход", "Expense", "расход"),
                            ),
                    ),
                ),
        )

    private fun method(
        name: String,
        nameEn: String = "",
        description: String = "",
        signatures: List<Signature> = emptyList(),
    ) = MethodDefinition(name = name, nameEn = nameEn, description = description, signatures = signatures)
}
