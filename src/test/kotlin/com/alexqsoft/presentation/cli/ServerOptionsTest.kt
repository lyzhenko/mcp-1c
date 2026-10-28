/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.presentation.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerOptionsTest {
    @Test
    fun `разбирает путь к платформе и режим`() {
        val parsed =
            ServerOptions.parse(
                arrayOf(
                    "--platform-path",
                    "C:\\Program Files\\1cv8\\8.3.27.2214",
                    "--locale",
                    "en",
                    "--mode",
                    "sse",
                    "--port",
                    "9000",
                    "--no-cache",
                    "--lazy",
                ),
            )

        val options = assertNotNull(parsed.options)
        assertEquals("C:\\Program Files\\1cv8\\8.3.27.2214", options.platformPath)
        assertEquals("en", options.locale)
        assertEquals(ServerMode.SSE, options.mode)
        assertEquals(9000, options.port)
        assertEquals(false, options.cacheEnabled)
        assertEquals(false, options.warmup)
    }

    @Test
    fun `принимает значение после знака равенства`() {
        val parsed = ServerOptions.parse(arrayOf("--platform-root=C:\\1cv8", "--version=8.3.27"))

        val options = assertNotNull(parsed.options)
        assertEquals(listOf("C:\\1cv8"), options.platformRoots)
        assertEquals("8.3.27", options.version)
    }

    @Test
    fun `ключ benchmark включает замеры`() {
        val options = assertNotNull(ServerOptions.parse(arrayOf("--benchmark")).options)
        assertTrue(options.benchmark)
    }

    @Test
    fun `неизвестный ключ возвращает ошибку`() {
        val parsed = ServerOptions.parse(arrayOf("--нет-такого-ключа"))
        assertNull(parsed.options)
        assertTrue(parsed.error?.contains("Неизвестный ключ") == true, parsed.error)
    }

    @Test
    fun `help не требует остальных аргументов`() {
        val parsed = ServerOptions.parse(arrayOf("--help"))
        assertTrue(parsed.help)
        assertNull(parsed.options)
    }

    @Test
    fun `неверный порт возвращает ошибку`() {
        val parsed = ServerOptions.parse(arrayOf("--port", "70000"))
        assertNull(parsed.options)
        assertTrue(parsed.error?.contains("порт") == true, parsed.error)
    }
}
