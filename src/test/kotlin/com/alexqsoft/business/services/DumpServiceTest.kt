/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.business.services

import com.alexqsoft.exceptions.DefinitionNotFoundException
import com.alexqsoft.infrastructure.dump.DumpOptions
import com.alexqsoft.infrastructure.dump.DumpStorage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DumpServiceTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var storage: DumpStorage
    private lateinit var service: DumpService

    @BeforeEach
    fun setUp() {
        val dump = Path.of(javaClass.getResource("/dump")!!.toURI())
        storage = DumpStorage(DumpOptions(indexDir = directory.toString(), waitTimeoutMs = 60_000))
        storage.open(dump.toString())
        assertNotNull(storage.awaitReady())
        service = DumpService(storage)
    }

    @AfterEach
    fun tearDown() {
        storage.close()
    }

    @Test
    fun `поиск процедур по имени`() {
        val page = service.search("Сумма", kind = "routine")
        assertEquals(2, page.total)
        assertTrue(page.items.all { it.kind == "routine" })
        assertTrue(page.items.any { it.name == "СуммаСтрокой" })
        assertTrue(page.items.any { it.name == "СуммаЗаказа" })
    }

    @Test
    fun `поиск объектов и реквизитов`() {
        val page = service.search("Товар")
        assertTrue(page.total >= 3)
        assertTrue(page.items.any { it.kind == "Catalog" && it.name == "Товары" })
        assertTrue(page.items.any { it.kind == "field" && it.name == "Товар" })
    }

    @Test
    fun `фильтр по виду оставляет только реквизиты`() {
        val page = service.search("Артикул", kind = "field")
        assertEquals(1, page.total)
        assertEquals("Артикул", page.items.single().name)
        assertEquals("Catalog.Товары", page.items.single().owner)
    }

    @Test
    fun `карточка объекта содержит реквизиты табличные части формы и модули`() {
        val card = service.metadata("Catalog.Товары")

        assertEquals("Товары", card.obj.name)
        assertTrue(card.attributes.any { it.name == "Артикул" })
        val section = card.tabularSections.single()
        assertEquals("Строки", section.name)
        assertEquals(listOf("Товар"), section.fields.map { it.name })
        assertEquals(listOf("ФормаЭлемента"), card.forms)
        assertEquals(listOf("Заполнить"), card.commands)
        assertEquals(2, card.modules.size)
        assertTrue(card.modules.any { it.module.kind == "Form.ФормаЭлемента" })
    }

    @Test
    fun `карточка перечисления содержит значения`() {
        val card = service.metadata("Enum.СтатусыЗаказа")
        assertEquals(listOf("Новый", "Закрыт"), card.enumValues.map { it.name })
    }

    @Test
    fun `код модуля читается с номерами строк`() {
        val result = service.code("CommonModules/ОбщегоНазначения/Ext/Module.bsl", from = 1, to = 4)

        assertEquals("Module", result.moduleKind)
        assertEquals("CommonModule.ОбщегоНазначения", result.owner)
        assertTrue(result.text.contains("1  #Область"))
        assertTrue(result.text.contains("4  Функция СуммаСтрокой"))
        assertEquals(1, result.from)
        assertEquals(4, result.to)
    }

    @Test
    fun `grep находит строку с владельцем и процедурой`() {
        val result = service.grep("ЗначениеЗаполнено")

        assertEquals(1, result.hits.size)
        val hit = result.hits.single()
        assertEquals("Catalogs/Товары/Ext/ObjectModule.bsl", hit.path)
        assertEquals("Catalog.Товары", hit.owner)
        assertEquals("ПередЗаписью", hit.routine)
        assertTrue(result.complete)
    }

    @Test
    fun `grep понимает регулярное выражение`() {
        val result = service.grep("Функция .*Экспорт", regex = true)
        assertEquals(2, result.hits.size)
        assertTrue(result.hits.any { it.path.endsWith("CommonModules/ОбщегоНазначения/Ext/Module.bsl") })
    }

    @Test
    fun `карточка объекта содержит обращения из кода и запросов`() {
        val card = service.metadata("Catalog.Товары")
        val usages = assertNotNull(card.usages)
        assertTrue(usages.total > 0, "обращений нет: $usages")
        assertTrue(usages.byContext.containsKey("query"))
    }

    @Test
    fun `точки входа собираются из форм подписок и заданий`() {
        val page = service.entrypoints()
        assertEquals(4, page.total)
        assertEquals(2, page.counts["form"])
        assertEquals(1, page.counts["subscription"])
        assertEquals(1, page.counts["job"])

        val forms = service.entrypoints(kind = "form")
        assertEquals(2, forms.total)
        assertTrue(forms.items.all { it.kind == "form" && it.owner == "Catalog.Товары" })

        val byMetadata = service.entrypoints(metadata = "Catalog.Товары")
        assertEquals(3, byMetadata.total)
        assertTrue(byMetadata.items.any { it.kind == "subscription" && it.sources.contains("Catalog.Товары") })
    }

    @Test
    fun `неизвестный объект — доменная ошибка`() {
        assertFailsWith<DefinitionNotFoundException> { service.metadata("Catalog.НетТакого") }
    }
}
