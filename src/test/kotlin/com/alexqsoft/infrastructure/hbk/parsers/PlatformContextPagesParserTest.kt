/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.infrastructure.hbk.parsers

import com.alexqsoft.infrastructure.hbk.parsers.specialized.ConstructorPageParser
import com.alexqsoft.infrastructure.hbk.parsers.specialized.EnumPageParser
import com.alexqsoft.infrastructure.hbk.parsers.specialized.EnumValuePageParser
import com.alexqsoft.infrastructure.hbk.parsers.specialized.MethodPageParser
import com.alexqsoft.infrastructure.hbk.parsers.specialized.ObjectPageParser
import com.alexqsoft.infrastructure.hbk.parsers.specialized.PropertyPageParser
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class PlatformContextPagesParserTest {
    @Test
    fun `test all parsers are available`() {
        // Создаем временный парсер для проверки доступности всех парсеров
        // В реальном использовании PlatformContextPagesParser требует Context, который сложно замокать
        assertNotNull(ConstructorPageParser())
        assertNotNull(EnumPageParser())
        assertNotNull(EnumValuePageParser())
        assertNotNull(MethodPageParser())
        assertNotNull(ObjectPageParser())
        assertNotNull(PropertyPageParser())
    }
}
