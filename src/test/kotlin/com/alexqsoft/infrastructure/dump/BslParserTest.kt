/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.infrastructure.dump

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BslParserTest {
    @Test
    fun `находит процедуры параметры вызовы и обращения`() {
        val text =
            """
            #Область ПрограммныйИнтерфейс

            Функция Сумма(А, Б = 0) Экспорт
                Результат = А + Б;
                Если Результат > 0 Тогда
                    Сообщить(Результат);
                КонецЕсли;
                Возврат Результат;
            КонецФункции

            Процедура Заполнить(Товары)
                Запрос = Новый Запрос;
                Запрос.Текст = "ВЫБРАТЬ * ИЗ Справочник.Товары";
                Справочники.Товары.Очистить();
            КонецПроцедуры

            #КонецОбласти
            """.trimIndent()

        val parsed = BslParser.parse(text)

        assertEquals(2, parsed.routines.size)
        val function = parsed.routines.first { it.name == "Сумма" }
        assertEquals("function", function.kind)
        assertEquals(2, function.parameters.size)
        assertTrue(function.parameters[1].hasDefault)
        assertEquals(1, function.requiredParameters())
        assertTrue(function.export)
        assertTrue(function.hasReturn)
        assertTrue(function.calls.any { it.name == "Сообщить" })

        val procedure = parsed.routines.first { it.name == "Заполнить" }
        assertEquals("procedure", procedure.kind)
        assertTrue(procedure.calls.any { it.name == "Запрос" && it.qualifier == null && it.arguments == 0 })
        assertTrue(procedure.metadataRefs.any { it.collection == "Справочники" && it.objectName == "Товары" })
        assertEquals(1, procedure.metadataRefs.count { it.context == "query" && it.objectName == "Товары" })
        assertEquals("ПрограммныйИнтерфейс", function.region)
    }

    @Test
    fun `находит неиспользуемые параметры и функцию без возврата`() {
        val text =
            """
            Функция БезВозврата(Аргумент)
                Лишняя = 42;
            КонецФункции
            """.trimIndent()

        val routine = BslParser.parse(text).routines.single()

        assertTrue(routine.unused.any { it.name == "Аргумент" && it.kind == "parameter" })
        assertTrue(routine.unused.any { it.name == "Лишняя" && it.kind == "local" })
        assertTrue(!routine.hasReturn)
    }
}
