/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.infrastructure.dump

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DumpIndexerTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var dump: Path
    private lateinit var indexFile: Path

    @BeforeEach
    fun setUp() {
        dump = Path.of(javaClass.getResource("/dump")!!.toURI())
        indexFile = directory.resolve("dump.db")
    }

    @Test
    fun `индекс содержит объекты поля модули и процедуры`() {
        val info = DumpIndexer(parallelism = 2).build(dump, indexFile)

        assertEquals("ТестоваяКонфигурация", info.configuration)
        assertEquals("1.0.0.1", info.version)
        assertEquals(4, info.objects)
        assertEquals(8, info.fields)
        assertEquals(4, info.modules)
        assertEquals(5, info.routines)
        assertTrue(info.indexBytes > 0)

        DumpIndex(indexFile).use { index ->
            val catalog = assertNotNull(index.objectByRef("Catalog.Товары"))
            assertEquals("Товары", catalog.synonym)
            assertEquals("Справочник товаров", catalog.comment)

            val fields = index.fields(catalog.id)
            assertTrue(fields.any { it.name == "Артикул" && it.type == "xs:string" })
            assertTrue(fields.any { it.name == "Строки" && it.fieldKind == "TabularSection" })
            assertTrue(fields.any { it.name == "Товар" && it.tabular == "Строки" })
            assertTrue(fields.any { it.name == "ФормаЭлемента" && it.fieldKind == "Form" })
            assertTrue(fields.any { it.name == "Заполнить" && it.fieldKind == "Command" })

            val modules = index.modules(catalog.id)
            assertEquals(2, modules.size)
            assertTrue(modules.any { it.kind == "ObjectModule" })
            assertTrue(modules.any { it.kind == "Form.ФормаЭлемента" })

            val routines = index.findRoutines("СуммаСтрокой", 10)
            assertEquals(1, routines.size)
            assertEquals("CommonModule", routines.single().ownerKind)
            assertEquals("function", routines.single().routine.kind)
            assertTrue(routines.single().routine.export)
        }
    }

    @Test
    fun `границы процедуры находятся по строке`() {
        DumpIndexer(parallelism = 2).build(dump, indexFile)
        DumpIndex(indexFile).use { index ->
            val routine = assertNotNull(index.routineAt("Catalogs/Товары/Ext/ObjectModule.bsl", 2))
            assertEquals("ПередЗаписью", routine.name)
            assertEquals(1, routine.startLine)
            assertEquals(5, routine.endLine)
        }
    }

    @Test
    fun `enum-значения попадают в поля`() {
        DumpIndexer(parallelism = 2).build(dump, indexFile)
        DumpIndex(indexFile).use { index ->
            val enum = assertNotNull(index.objectByRef("Enum.СтатусыЗаказа"))
            val values = index.fields(enum.id).filter { it.fieldKind == "EnumValue" }.map { it.name }
            assertEquals(listOf("Новый", "Закрыт"), values)
        }
    }
}
