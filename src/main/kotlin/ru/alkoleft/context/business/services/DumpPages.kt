/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.business.services

import ru.alkoleft.context.business.entities.DumpField
import ru.alkoleft.context.business.entities.DumpModule
import ru.alkoleft.context.business.entities.DumpObject
import ru.alkoleft.context.business.entities.DumpRoutine
import ru.alkoleft.context.infrastructure.dump.UsageSummary

/** Совпадение поиска по выгрузке. */
data class DumpSearchItem(
    val kind: String,
    val name: String,
    val synonym: String = "",
    val owner: String? = null,
    val path: String? = null,
    val detail: String? = null,
    val id: Long = 0,
)

/** Страница поиска по выгрузке. */
data class DumpSearchPage(
    val query: String,
    val kind: String?,
    val total: Int,
    val offset: Int,
    val limit: Int,
    val items: List<DumpSearchItem>,
    val suggestions: List<String> = emptyList(),
)

/** Табличная часть с реквизитами. */
data class DumpTabularSection(
    val name: String,
    val synonym: String,
    val fields: List<DumpField>,
)

/** Модуль в карточке объекта. */
data class DumpModuleCard(
    val module: DumpModule,
    val routines: List<DumpRoutine>,
    val routinesTotal: Int,
)

/** Карточка объекта метаданных. */
data class DumpMetadataCard(
    val obj: DumpObject,
    val attributes: List<DumpField>,
    val tabularSections: List<DumpTabularSection>,
    val enumValues: List<DumpField>,
    val forms: List<String>,
    val commands: List<String>,
    val templates: List<String>,
    val modules: List<DumpModuleCard>,
    val totalFields: Int,
    val truncated: Boolean,
    val usages: UsageSummary? = null,
)

/** Точка входа в ответе агента. */
data class DumpEntryPoint(
    val kind: String,
    val name: String,
    val owner: String?,
    val handler: String?,
    val event: String?,
    val path: String?,
    val sources: List<String>,
)

/** Страница точек входа. */
data class EntryPointPage(
    val kind: String?,
    val metadata: String?,
    val total: Int,
    val items: List<DumpEntryPoint>,
    val counts: Map<String, Int>,
)

/** Результат чтения модуля. */
data class DumpCodeResult(
    val path: String,
    val moduleKind: String,
    val owner: String?,
    val totalLines: Int,
    val from: Int,
    val to: Int,
    val text: String,
)

/** Совпадение grep. */
data class GrepHit(
    val path: String,
    val line: Int,
    val text: String,
    val owner: String? = null,
    val routine: String? = null,
)

/** Результат grep. */
data class GrepResult(
    val pattern: String,
    val hits: List<GrepHit>,
    val filesScanned: Int,
    val totalFiles: Int,
    val complete: Boolean,
    val elapsedMs: Long,
)
