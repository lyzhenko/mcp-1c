/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.business.entities

/**
 * Объект метаданных из выгрузки конфигурации.
 *
 * @property kind вид объекта: `Catalog`, `Document`, `CommonModule`, …
 * @property path путь XML-файла относительно корня выгрузки
 */
data class DumpObject(
    val id: Long = 0,
    val kind: String,
    val name: String,
    val synonym: String = "",
    val comment: String = "",
    val path: String,
) {
    /** Ссылка вида `Catalog.АвансовыйОтчет`. */
    fun ref(): String = "$kind.$name"
}

/**
 * Реквизит, измерение, ресурс, табличная часть или значение перечисления.
 *
 * @property fieldKind `Attribute`, `Dimension`, `Resource`, `TabularSection`, `EnumValue`, `Form`, `Command`, `Template`
 * @property tabular имя табличной части, если реквизит вложен в неё
 */
data class DumpField(
    val id: Long = 0,
    val objectId: Long,
    val name: String,
    val synonym: String = "",
    val type: String = "",
    val fieldKind: String,
    val tabular: String? = null,
)

/** Файл модуля BSL, привязанный к объекту метаданных. */
data class DumpModule(
    val id: Long = 0,
    val objectId: Long?,
    val path: String,
    val kind: String,
    val lines: Int,
    val bytes: Long,
)

/** Процедура или функция внутри модуля. */
data class DumpRoutine(
    val id: Long = 0,
    val moduleId: Long,
    val name: String,
    val kind: String,
    val export: Boolean,
    val parameters: String,
    val startLine: Int,
    val endLine: Int,
)

/** Итоги построения индекса выгрузки. */
data class DumpInfo(
    val path: String = "",
    val configuration: String = "",
    val version: String = "",
    val schema: Int = 0,
    val objects: Int = 0,
    val fields: Int = 0,
    val modules: Int = 0,
    val routines: Int = 0,
    val indexedAt: Long = 0,
    val buildMillis: Long = 0,
    val indexBytes: Long = 0,
)
