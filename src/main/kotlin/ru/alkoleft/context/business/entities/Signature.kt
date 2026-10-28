/*
 * Copyright (c) 2024-2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.business.entities

/**
 * Вариант синтаксиса метода или конструктора.
 *
 * @property name заголовок варианта из справки («Основная», «Вариант 1»)
 * @property syntax строка синтаксиса как в справке
 * @property description описание варианта
 * @property parameters параметры варианта
 */
data class Signature(
    val name: String = "",
    val syntax: String = "",
    val description: String = "",
    val parameters: List<ParameterDefinition> = emptyList(),
) {
    /** Сколько параметров обязательно. */
    fun requiredCount(): Int = parameters.count { it.required }

    /** Есть ли у варианта параметры. */
    fun hasParameters(): Boolean = parameters.isNotEmpty()
}
