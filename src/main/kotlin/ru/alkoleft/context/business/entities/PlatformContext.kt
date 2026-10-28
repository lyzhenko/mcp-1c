/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.business.entities

/**
 * Всё, что сервер знает о платформе: разобранная справка и откуда она взялась.
 *
 * Модель неизменяемая: она одинаково годится и для разбора `.hbk`, и для чтения из кэша.
 */
data class PlatformContextData(
    val methods: List<MethodDefinition> = emptyList(),
    val properties: List<PropertyDefinition> = emptyList(),
    val types: List<PlatformTypeDefinition> = emptyList(),
    val enums: List<EnumDefinition> = emptyList(),
    val info: PlatformSourceInfo = PlatformSourceInfo(),
) {
    /** Сколько всего сущностей в справочнике. */
    fun totalDefinitions(): Int = methods.size + properties.size + types.size + enums.size
}

/**
 * Откуда взята модель платформы и сколько стоил её разбор.
 *
 * @property version версия платформы (каталог установки), «unknown» если определить не удалось
 * @property locale язык справочных файлов (`ru`, `en`)
 * @property path файл `.hbk`, из которого построена модель
 * @property sizeBytes размер `.hbk` — по нему кэш понимает, что файл подменили
 * @property modifiedAt время изменения `.hbk`
 * @property loadedAt когда модель построена (эпоха, мс)
 * @property parseMillis сколько занял разбор (0 при чтении из кэша)
 */
data class PlatformSourceInfo(
    val version: String = "unknown",
    val locale: String = "ru",
    val path: String = "",
    val sizeBytes: Long = 0,
    val modifiedAt: Long = 0,
    val loadedAt: Long = 0,
    val parseMillis: Long = 0,
)
