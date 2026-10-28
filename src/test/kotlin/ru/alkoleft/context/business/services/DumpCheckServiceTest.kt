/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.business.services

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.alkoleft.context.business.entities.MethodDefinition
import ru.alkoleft.context.business.entities.ParameterDefinition
import ru.alkoleft.context.business.entities.PlatformContextData
import ru.alkoleft.context.business.entities.Signature
import ru.alkoleft.context.infrastructure.dump.DumpOptions
import ru.alkoleft.context.infrastructure.dump.DumpStorage
import ru.alkoleft.context.infrastructure.search.PlatformCatalog
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DumpCheckServiceTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var storage: DumpStorage
    private lateinit var service: DumpCheckService

    @BeforeEach
    fun setUp() {
        val dump = Path.of(javaClass.getResource("/dump")!!.toURI())
        storage = DumpStorage(DumpOptions(indexDir = directory.toString(), waitTimeoutMs = 60_000))
        storage.open(dump.toString())
        assertNotNull(storage.awaitReady())
        service = DumpCheckService(storage) { catalog() }
    }

    @AfterEach
    fun tearDown() {
        storage.close()
    }

    @Test
    fun `находит ошибки аргументов неизвестную процедуру и метаданные`() {
        val draft =
            """
            Процедура Моя(А, Б = 1)
            КонецПроцедуры

            Функция Плохая(Лишний)
                Моя(1, 2, 3);
                НетТакойПроцедуры();
                Справочники.НетТакого.Очистить();
                ТестовыйМетод(1, 2, 3);
                Лишняя = 1;
            КонецФункции
            """.trimIndent()

        val result = service.check(text = draft, path = "Catalogs/Товары/Ext/ObjectModule.bsl")
        val codes = result.problems.map { it.code }

        assertTrue("argument_count" in codes, "нет ошибки числа аргументов: $codes")
        assertTrue("unknown_procedure" in codes, "нет неизвестной процедуры: $codes")
        assertTrue("missing_metadata" in codes, "нет отсутствующего объекта: $codes")
        assertTrue("platform_arguments" in codes, "нет проверки аргументов платформы: $codes")
        assertTrue("function_without_return" in codes, "нет функции без возврата: $codes")
        assertTrue("unused_parameter" in codes, "нет неиспользуемого параметра: $codes")
        assertTrue(result.problems.any { it.line > 0 })
    }

    @Test
    fun `корректный модуль не даёт ошибок`() {
        val draft =
            """
            Процедура Тест(А)
                Значение = А;
                Сообщить(Значение);
            КонецПроцедуры
            """.trimIndent()

        val result = service.check(text = draft)

        assertTrue(result.problems.none { it.severity == "error" }, "ошибки: ${result.problems}")
    }

    @Test
    fun `без открытой выгрузки проверка всё равно работает`() {
        storage.close()
        val result =
            service.check(
                text =
                    """
                    Процедура Тест()
                        Сообщить("ok");
                    КонецПроцедуры
                    """.trimIndent(),
            )

        assertTrue(result.problems.none { it.severity == "error" })
        assertTrue(result.notes.any { it.contains("Выгрузка не открыта") })
    }

    private fun catalog(): PlatformCatalog =
        PlatformCatalog(
            PlatformContextData(
                methods =
                    listOf(
                        MethodDefinition(
                            name = "ТестовыйМетод",
                            signatures =
                                listOf(
                                    Signature(
                                        parameters =
                                            listOf(
                                                ParameterDefinition("А", required = true),
                                                ParameterDefinition("Б", required = false),
                                            ),
                                    ),
                                ),
                        ),
                        MethodDefinition(
                            name = "Сообщить",
                            signatures =
                                listOf(
                                    Signature(parameters = listOf(ParameterDefinition("Текст", required = true))),
                                ),
                        ),
                    ),
            ),
        )
}
