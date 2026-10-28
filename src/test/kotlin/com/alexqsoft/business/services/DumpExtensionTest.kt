/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.business.services

import com.alexqsoft.infrastructure.dump.DumpOptions
import com.alexqsoft.infrastructure.dump.DumpStorage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DumpExtensionTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var storage: DumpStorage

    @AfterEach
    fun tearDown() {
        if (::storage.isInitialized) storage.close()
    }

    @Test
    fun `расширение перекрывает модуль базы`() {
        val base = Path.of(javaClass.getResource("/dump")!!.toURI())
        val extension = directory.resolve("extension")
        val module = extension.resolve("Catalogs/Товары/Ext/ObjectModule.bsl")
        Files.createDirectories(module.parent)
        Files.writeString(module, "Процедура Переопределено()\nКонецПроцедуры\n")

        storage =
            DumpStorage(
                DumpOptions(
                    indexDir = directory.resolve("index").toString(),
                    waitTimeoutMs = 60_000,
                ),
            )
        storage.open("$base;$extension")
        assertNotNull(storage.awaitReady())

        val service = DumpService(storage)
        val code = service.code("Catalogs/Товары/Ext/ObjectModule.bsl")
        assertTrue(code.text.contains("Переопределено"), code.text)
        assertTrue(storage.currentPath()!!.contains(";"))
    }
}
