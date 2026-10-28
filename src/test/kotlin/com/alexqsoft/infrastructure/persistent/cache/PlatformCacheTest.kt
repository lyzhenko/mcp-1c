/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.infrastructure.persistent.cache

import com.alexqsoft.business.entities.MethodDefinition
import com.alexqsoft.business.entities.PlatformContextData
import com.alexqsoft.business.entities.PlatformSourceInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformCacheTest {
    @TempDir
    lateinit var directory: Path

    private val key =
        PlatformCache.Key(
            source = Path.of("C:/platform/8.3.27.2214/bin/shcntx_ru.hbk"),
            size = 1024,
            modified = 1_700_000_000_000,
            locale = "ru",
            version = "8.3.27.2214",
        )

    private val data =
        PlatformContextData(
            methods = listOf(MethodDefinition("СтрНайти", description = "ищет")),
            info = PlatformSourceInfo(version = "8.3.27.2214", locale = "ru", path = key.source.toString()),
        )

    @Test
    fun `сохранённая модель читается обратно`() {
        val cache = PlatformCache(directory)
        cache.save(key, data)

        val loaded = assertNotNull(cache.load(key))
        assertEquals(1, loaded.methods.size)
        assertEquals("СтрНайти", loaded.methods.single().name)
        assertEquals("8.3.27.2214", loaded.info.version)
        assertTrue(cache.fileFor(key).toFile().exists())
    }

    @Test
    fun `изменение файла справки инвалидирует кэш`() {
        val cache = PlatformCache(directory)
        cache.save(key, data)

        assertNull(cache.load(key.copy(size = 2048)))
        assertNull(cache.load(key.copy(modified = 1_700_000_000_001)))
        assertNull(cache.load(key.copy(locale = "en")))
    }

    @Test
    fun `другая схема кэша не читается`() {
        PlatformCache(directory, schema = 1).save(key, data)

        assertNull(PlatformCache(directory, schema = 2).load(key))
    }

    @Test
    fun `битый файл кэша не роняет загрузку`() {
        val cache = PlatformCache(directory)
        val file = cache.fileFor(key)
        file.toFile().writeText("{ это не json")

        assertNull(cache.load(key))
    }
}
