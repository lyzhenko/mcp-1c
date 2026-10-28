/*
 * Copyright (c) 2024-2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.business.entities

/**
 * Тип платформы: его методы, свойства и способы создания.
 */
data class PlatformTypeDefinition(
    override val name: String,
    override val nameEn: String = "",
    override val description: String = "",
    val methods: List<MethodDefinition> = emptyList(),
    val properties: List<PropertyDefinition> = emptyList(),
    val constructors: List<Signature> = emptyList(),
    val example: String? = null,
    val note: String? = null,
    val related: List<String> = emptyList(),
) : Definition {
    /** Все члены типа: сначала методы, затем свойства (оба списка уже отсортированы справкой). */
    fun members(): List<Definition> = methods + properties

    /** Всего членов типа. */
    fun memberCount(): Int = methods.size + properties.size

    fun hasMethods(): Boolean = methods.isNotEmpty()

    fun hasProperties(): Boolean = properties.isNotEmpty()
}
