/*
 * Copyright (c) 2024-2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.business.entities

/**
 * Определение параметра метода или конструктора.
 *
 * @property required параметр обязателен
 * @property defaultValue значение по умолчанию, если справка его называет
 */
data class ParameterDefinition(
    val name: String,
    val type: String = "",
    val description: String = "",
    val required: Boolean = false,
    val defaultValue: String? = null,
)
