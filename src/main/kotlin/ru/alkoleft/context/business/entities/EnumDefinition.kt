/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.business.entities

/**
 * Перечисление платформы и его значения.
 */
data class EnumDefinition(
    override val name: String,
    override val nameEn: String = "",
    override val description: String = "",
    val values: List<EnumValueDefinition> = emptyList(),
    val example: String? = null,
) : Definition {
    fun hasValues(): Boolean = values.isNotEmpty()
}

/**
 * Значение перечисления платформы.
 */
data class EnumValueDefinition(
    override val name: String,
    override val nameEn: String = "",
    override val description: String = "",
) : Definition
