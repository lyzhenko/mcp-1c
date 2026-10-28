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
import ru.alkoleft.context.infrastructure.dump.DumpOptions
import ru.alkoleft.context.infrastructure.dump.DumpStorage
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DumpSimilarServiceTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var storage: DumpStorage
    private lateinit var service: DumpSimilarService

    @BeforeEach
    fun setUp() {
        val dump = Path.of(javaClass.getResource("/dump")!!.toURI())
        storage = DumpStorage(DumpOptions(indexDir = directory.toString(), waitTimeoutMs = 60_000))
        storage.open(dump.toString())
        assertNotNull(storage.awaitReady())
        service = DumpSimilarService(storage)
    }

    @AfterEach
    fun tearDown() {
        storage.close()
    }

    @Test
    fun `находит процедуру по общим метаданным`() {
        val page =
            service.similar(
                text =
                    """
                    Процедура Тест()
                        Справочники.Товары.Очистить();
                    КонецПроцедуры
                    """.trimIndent(),
            )

        assertTrue(page.items.any { it.name == "ЗаполнитьТовары" }, "результаты: ${page.items}")
    }
}
