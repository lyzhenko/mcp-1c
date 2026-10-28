/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.business.services

import com.alexqsoft.business.entities.Definition
import com.alexqsoft.business.entities.Signature

/**
 * Страница членов типа: методы и свойства (или значения перечисления).
 */
data class MembersPage(
    val typeName: String,
    val total: Int,
    val offset: Int,
    val limit: Int,
    val items: List<Definition>,
)

/**
 * Страница конструкторов типа.
 */
data class ConstructorsPage(
    val typeName: String,
    val total: Int,
    val offset: Int,
    val limit: Int,
    val items: List<Signature>,
)

/**
 * Результат точного поиска: найденные элементы или подсказки, если ничего не нашлось.
 */
data class InfoResult(
    val items: List<Definition>,
    val suggestions: List<String> = emptyList(),
    val memberOwners: List<String> = emptyList(),
)
