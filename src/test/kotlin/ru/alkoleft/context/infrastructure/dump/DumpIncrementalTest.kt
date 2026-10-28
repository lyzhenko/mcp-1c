/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.infrastructure.dump

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DumpIncrementalTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `частично обновляет и удаляет модуль`() {
        val source = Path.of(javaClass.getResource("/dump")!!.toURI())
        val dump = directory.resolve("dump")
        copyDirectory(source, dump)
        val indexFile = directory.resolve("index.db")
        DumpIndexer(parallelism = 2).build(dump, indexFile)

        val modulePath = "Catalogs/Товары/Ext/ObjectModule.bsl"
        val module = dump.resolve(modulePath)
        Files.writeString(
            module,
            """
            Процедура ПередЗаписью(Отказ)
                Добавлено();
            КонецПроцедуры

            Процедура Добавлено()
            КонецПроцедуры
            """.trimIndent(),
        )

        val updated = DumpIndexer(parallelism = 2).updateModules(indexFile, listOf(dump), setOf(modulePath))
        assertTrue(updated == 1)

        DumpIndex(indexFile).use { index ->
            assertNotNull(index.moduleByPath(modulePath))
            assertTrue(index.routines(index.moduleByPath(modulePath)!!.id).any { it.name == "Добавлено" })
        }

        Files.delete(module)
        val deleted = DumpIndexer(parallelism = 2).updateModules(indexFile, listOf(dump), emptySet(), setOf(modulePath))
        assertTrue(deleted == 0)
        DumpIndex(indexFile).use { index -> assertNull(index.moduleByPath(modulePath)) }
    }

    private fun copyDirectory(
        source: Path,
        target: Path,
    ) {
        Files.walk(source).use { stream ->
            stream.forEach { file ->
                val relative = source.relativize(file)
                val destination = target.resolve(relative)
                if (Files.isDirectory(file)) {
                    destination.createDirectories()
                } else {
                    destination.parent?.createDirectories()
                    file.copyTo(destination)
                }
            }
        }
    }
}
