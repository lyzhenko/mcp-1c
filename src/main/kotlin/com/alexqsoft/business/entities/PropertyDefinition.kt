/*
 * Copyright (c) 2024-2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.business.entities

/**
 * Свойство платформы: глобальное свойство либо свойство типа.
 *
 * @property propertyType тип значения свойства
 * @property isReadOnly свойство доступно только для чтения
 */
data class PropertyDefinition(
    override val name: String,
    override val nameEn: String = "",
    override val description: String = "",
    val propertyType: String = "",
    val isReadOnly: Boolean = false,
    val note: String? = null,
    val related: List<String> = emptyList(),
) : Definition
