/*
 * Copyright (c) 2024-2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.business.entities

/**
 * Метод платформы: глобальная функция либо метод типа.
 *
 * @property returnType тип возвращаемого значения (пустая строка, если не указан)
 * @property signatures варианты синтаксиса; пустой список означает, что справка синтаксиса не дала
 * @property isGlobal глобальная функция (вызывается без типа)
 */
data class MethodDefinition(
    override val name: String,
    override val nameEn: String = "",
    override val description: String = "",
    val returnType: String = "",
    val signatures: List<Signature> = emptyList(),
    val example: String? = null,
    val note: String? = null,
    val related: List<String> = emptyList(),
    val isGlobal: Boolean = false,
) : Definition {
    /** Первый вариант синтаксиса — для краткого ответа в списках. */
    fun primarySignature(): Signature? = signatures.firstOrNull()
}
