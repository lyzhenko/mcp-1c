/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.infrastructure.persistent.storage

import org.junit.jupiter.api.Test
import ru.alkoleft.context.infrastructure.hbk.models.ConstructorInfo
import ru.alkoleft.context.infrastructure.hbk.models.EnumInfo
import ru.alkoleft.context.infrastructure.hbk.models.EnumValueInfo
import ru.alkoleft.context.infrastructure.hbk.models.MethodInfo
import ru.alkoleft.context.infrastructure.hbk.models.MethodParameterInfo
import ru.alkoleft.context.infrastructure.hbk.models.MethodSignatureInfo
import ru.alkoleft.context.infrastructure.hbk.models.ObjectInfo
import ru.alkoleft.context.infrastructure.hbk.models.PropertyInfo
import ru.alkoleft.context.infrastructure.hbk.models.RelatedObject
import ru.alkoleft.context.infrastructure.hbk.models.ValueInfo
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Маппинг обязан сохранять всё, что разобрано из справки: сигнатуры, параметры, конструкторы
 * и значения перечислений. Раньше эти поля терялись, и MCP-ответы приходили без синтаксиса.
 */
class MapperTest {
    @Test
    fun `метод сохраняет сигнатуры параметры и возвращаемый тип`() {
        val info =
            MethodInfo(
                nameRu = "ТестовыйМетод",
                nameEn = "TestMethod",
                signatures =
                    listOf(
                        MethodSignatureInfo(
                            name = "Основная",
                            syntax = "ТестовыйМетод(<Первый>, <Второй>)",
                            parameters =
                                listOf(
                                    MethodParameterInfo("Первый", "Строка", isOptional = false, description = "первый"),
                                    MethodParameterInfo("Второй", "Число", isOptional = true, description = "второй"),
                                ),
                            description = "вариант",
                        ),
                    ),
                example = "ТестовыйМетод(\"а\")",
                note = "примечание",
                relatedObjects = listOf(RelatedObject("Массив", "#")),
                description = " описание ",
                returnValue = ValueInfo("Булево", "результат"),
            )

        val method = info.toEntity(isGlobal = true)

        assertEquals("ТестовыйМетод", method.name)
        assertEquals("TestMethod", method.nameEn)
        assertEquals("Булево", method.returnType)
        assertTrue(method.isGlobal)
        assertEquals(1, method.signatures.size)
        val signature = method.signatures.single()
        assertEquals("ТестовыйМетод(<Первый>, <Второй>)", signature.syntax)
        assertEquals(2, signature.parameters.size)
        assertTrue(signature.parameters[0].required)
        assertFalse(signature.parameters[1].required)
        assertEquals("Строка", signature.parameters[0].type)
        assertEquals("примечание", method.note)
        assertEquals(listOf("Массив"), method.related)
    }

    @Test
    fun `тип сохраняет конструкторы и членов`() {
        val info =
            ObjectInfo(
                nameRu = "Массив",
                nameEn = "Array",
                description = "массив",
                example = null,
                note = null,
                relatedObjects = null,
                properties = listOf(PropertyInfo("Количество", "Count", "сколько", true, "Число", null, null)),
                methods = emptyList(),
                constructors =
                    listOf(
                        ConstructorInfo(
                            name = "Массив",
                            syntax = "Новый Массив(<Количество>)",
                            parameters = listOf(MethodParameterInfo("Количество", "Число", true, "размер")),
                            description = "создаёт массив",
                            example = null,
                            note = null,
                            relatedObjects = null,
                        ),
                    ),
            )

        val type = info.toEntity()

        assertEquals("Массив", type.name)
        assertEquals(1, type.properties.size)
        assertEquals(1, type.constructors.size)
        val constructor = type.constructors.single()
        assertEquals("Новый Массив(<Количество>)", constructor.syntax)
        assertEquals(1, constructor.parameters.size)
    }

    @Test
    fun `перечисление сохраняет значения`() {
        val info =
            EnumInfo(
                nameRu = "ВидДвижения",
                nameEn = "MovementKind",
                description = "вид",
                relatedObjects = null,
                values =
                    mutableListOf(
                        EnumValueInfo("Приход", "Receipt", "приход", null),
                        EnumValueInfo("Расход", "Expense", "расход", null),
                    ),
                example = null,
            )

        val enum = info.toEntity()

        assertEquals("ВидДвижения", enum.name)
        assertEquals(2, enum.values.size)
        assertEquals("Приход", enum.values[0].name)
        assertEquals("Receipt", enum.values[0].nameEn)
        assertEquals("Расход", enum.values[1].name)
    }
}
