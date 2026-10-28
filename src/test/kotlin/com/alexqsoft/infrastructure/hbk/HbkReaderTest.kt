/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.infrastructure.hbk

import com.alexqsoft.infrastructure.hbk.reader.HbkContentReader
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class HbkReaderTest {
    @Test
    fun read() {
        val platformPath = System.getProperty("platform.context.path")
        val reader = HbkContentReader()
        reader.read(platformHbk(platformPath)) {
            println("Success")
        }
    }
}

/**
 * Справка лежит в `bin` каталога версии. Тест принимает и каталог версии, и сам `bin`,
 * и прямой путь к `.hbk` — так его можно запускать на разных машинах и в CI.
 */
internal fun platformHbk(platformPath: String): Path {
    val base = Path.of(platformPath)
    if (Files.isRegularFile(base)) return base
    val direct = base.resolve("shcntx_ru.hbk")
    if (Files.isRegularFile(direct)) return direct
    return base.resolve("bin").resolve("shcntx_ru.hbk")
}
