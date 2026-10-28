/*
 * Copyright (c) 2024-2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.business.entities

/**
 * Доменная сущность: элемент API платформы 1С.
 *
 * Имена хранятся в двух написаниях: `name` — русское (так элемент называется в конфигураторе),
 * `nameEn` — английское (по нему тоже ищут).
 */
sealed interface Definition {
    val name: String

    val nameEn: String

    val description: String
}
