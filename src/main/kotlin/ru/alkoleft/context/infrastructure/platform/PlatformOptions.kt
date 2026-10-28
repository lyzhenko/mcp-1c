/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.infrastructure.platform

/**
 * Настройки поиска и разбора справочных файлов платформы.
 *
 * @property path явный путь: каталог платформы, каталог версии или сам файл `.hbk`
 * @property roots дополнительные корни поиска установленных платформ
 * @property version нужная версия (точная или префикс, например `8.3.27`); null — самая свежая
 * @property locale язык справки (`ru`, `en`)
 * @property cacheDir каталог дискового кэша разбора; null — каталог по умолчанию
 * @property cacheEnabled читать и писать кэш
 * @property refresh игнорировать готовый кэш и разобрать `.hbk` заново
 * @property warmup начинать разбор в фоне при старте, не дожидаясь первого запроса
 * @property responseLimit предел размера ответа инструмента в символах
 * @property waitTimeoutMs сколько инструмент ждёт готовности разбора, прежде чем ответить «загружается»
 */
data class PlatformOptions(
    val path: String? = null,
    val roots: List<String> = emptyList(),
    val version: String? = null,
    val locale: String = "ru",
    val cacheDir: String? = null,
    val cacheEnabled: Boolean = true,
    val refresh: Boolean = false,
    val warmup: Boolean = true,
    val responseLimit: Int = 24_000,
    val waitTimeoutMs: Long = 25_000,
) {
    fun normalizedLocale(): String = locale.trim().lowercase().ifBlank { "ru" }

    fun normalizedVersion(): String? = version?.trim()?.takeIf { it.isNotEmpty() }
}
