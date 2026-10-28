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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DumpGraphServiceTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var storage: DumpStorage
    private lateinit var service: DumpGraphService

    @BeforeEach
    fun setUp() {
        val dump = Path.of(javaClass.getResource("/dump")!!.toURI())
        storage = DumpStorage(DumpOptions(indexDir = directory.toString(), waitTimeoutMs = 60_000))
        storage.open(dump.toString())
        assertNotNull(storage.awaitReady())
        service = DumpGraphService(storage)
    }

    @AfterEach
    fun tearDown() {
        storage.close()
    }

    @Test
    fun `карточка процедуры и связи`() {
        val module = assertNotNull(service.node("Catalog.Товары"))
        assertEquals("Catalog", module.kind)
        assertNotNull(module.usages)

        val caller =
            service
                .types("Catalogs/Товары/Ext/ObjectModule.bsl")
                .routines
                .first { it.name == "ПередЗаписью" }
        assertTrue(caller.calls.any { it.name == "СуммаСтрокой" && it.qualifier == "ОбщегоНазначения" })
    }

    @Test
    fun `входящие и исходящие вызовы`() {
        val neighbors = service.neighbors("routine:1")
        assertEquals("routine", neighbors.node.kind)
        assertTrue(neighbors.counts.getValue("outgoing") >= 0)
    }
}
