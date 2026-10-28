/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.infrastructure.platform

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformLocatorTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `выбирает самую свежую установленную версию`() {
        install("8.3.26.1234", "ru")
        install("8.3.27.2214", "ru")

        val located = assertNotNull(PlatformLocator().locate(PlatformOptions(path = root.toString())))

        assertEquals("8.3.27.2214", located.version)
        assertEquals("ru", located.locale)
        assertTrue(located.hbk.endsWith(Path.of("8.3.27.2214", "bin", "shcntx_ru.hbk")))
    }

    @Test
    fun `фильтр по версии берёт указанную версию`() {
        install("8.3.26.1234", "ru")
        install("8.3.27.2214", "ru")

        val located =
            assertNotNull(
                PlatformLocator().locate(
                    PlatformOptions(path = root.toString(), version = "8.3.26"),
                ),
            )

        assertEquals("8.3.26.1234", located.version)
    }

    @Test
    fun `отсутствующий язык подменяется русским`() {
        install("8.3.27.2214", "ru")

        val located =
            assertNotNull(
                PlatformLocator().locate(
                    PlatformOptions(path = root.toString(), locale = "en"),
                ),
            )

        assertEquals("ru", located.locale)
        assertTrue(located.localeFallback)
    }

    @Test
    fun `явный путь к файлу справки принимается`() {
        val hbk = install("8.3.27.2214", "ru")

        val located = assertNotNull(PlatformLocator().locate(PlatformOptions(path = hbk.toString())))

        assertEquals(hbk.toAbsolutePath(), located.hbk.toAbsolutePath())
        assertEquals("8.3.27.2214", located.version)
    }

    @Test
    fun `пустой каталог не даёт платформы`() {
        assertNull(PlatformLocator().locate(PlatformOptions(path = root.toString())))
    }

    @Test
    fun `installations перечисляет версии и языки`() {
        install("8.3.26.1234", "ru")
        install("8.3.27.2214", "ru")
        install("8.3.27.2214", "en")

        val installations = PlatformLocator().installations(PlatformOptions(path = root.toString()))

        assertEquals(2, installations.size)
        assertEquals("8.3.27.2214", installations.first().version)
        assertEquals(setOf("ru", "en"), installations.first().locales.keys)
    }

    private fun install(
        version: String,
        locale: String,
    ): Path {
        val bin = root.resolve(version).resolve("bin")
        Files.createDirectories(bin)
        val hbk = bin.resolve("shcntx_$locale.hbk")
        Files.writeString(hbk, "stub")
        return hbk
    }
}
