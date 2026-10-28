/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.presentation.formatters

import com.alexqsoft.business.entities.DumpField
import com.alexqsoft.business.entities.DumpRoutine
import com.alexqsoft.business.services.CheckResult
import com.alexqsoft.business.services.ConventionsResult
import com.alexqsoft.business.services.DumpCodeResult
import com.alexqsoft.business.services.DumpMetadataCard
import com.alexqsoft.business.services.DumpSearchItem
import com.alexqsoft.business.services.DumpSearchPage
import com.alexqsoft.business.services.EntryPointPage
import com.alexqsoft.business.services.GraphEdge
import com.alexqsoft.business.services.GraphNode
import com.alexqsoft.business.services.GrepResult
import com.alexqsoft.business.services.MetadataRefRowView
import com.alexqsoft.business.services.NeighborsResult
import com.alexqsoft.business.services.RightsPage
import com.alexqsoft.business.services.SimilarPage
import com.alexqsoft.business.services.TypeSymbol
import com.alexqsoft.business.services.TypesResult
import com.alexqsoft.infrastructure.dump.DumpSnapshot
import com.alexqsoft.infrastructure.dump.DumpStorage
import com.alexqsoft.infrastructure.dump.UsageSummary
import java.time.Instant

/**
 * JSON-ответы инструментов выгрузки конфигурации.
 *
 * Формат тот же, что у справки по платформе: `kind`, `total`/`returned`/`nextOffset`,
 * `truncated` при сокращении. Размер ограничивает общий [JsonPager].
 */
class DumpJsonService(
    private val pager: JsonPager = JsonPager(),
) {
    fun search(page: DumpSearchPage): String {
        val header = linkedMapOf<String, Any?>("query" to page.query, "kind" to page.kind, "total" to page.total)
        if (page.suggestions.isNotEmpty()) header["suggestions"] = page.suggestions
        return pager.page(
            header = header,
            items = page.items,
            offset = page.offset,
            total = page.total,
            render = ::searchItem,
            hint =
                if (page.items.isEmpty()) {
                    "Ничего не найдено. Уточните имя или вид: object, routine, field."
                } else {
                    "Карточка — dumpMetadata; код модуля — dumpCode; текст — dumpGrep."
                },
        )
    }

    fun metadata(card: DumpMetadataCard): String {
        val obj = card.obj
        val frame =
            linkedMapOf<String, Any?>(
                "found" to true,
                "kind" to obj.kind,
                "name" to obj.name,
                "synonym" to obj.synonym.takeIf { it.isNotBlank() },
                "path" to obj.path,
                "counts" to
                    linkedMapOf<String, Any?>(
                        "attributes" to card.attributes.size,
                        "tabularSections" to card.tabularSections.size,
                        "enumValues" to card.enumValues.size,
                        "forms" to card.forms.size,
                        "commands" to card.commands.size,
                        "templates" to card.templates.size,
                        "modules" to card.modules.size,
                        "fields" to card.totalFields,
                    ),
                "attributes" to card.attributes.map(::fieldNode).takeIf { it.isNotEmpty() },
                "tabularSections" to
                    card.tabularSections
                        .map { section ->
                            linkedMapOf<String, Any?>(
                                "name" to section.name,
                                "synonym" to section.synonym.takeIf { it.isNotBlank() },
                                "fields" to section.fields.map(::fieldNode),
                            ).withoutNulls()
                        }.takeIf { it.isNotEmpty() },
                "enumValues" to card.enumValues.map(::fieldNode).takeIf { it.isNotEmpty() },
                "forms" to card.forms.takeIf { it.isNotEmpty() },
                "commands" to card.commands.takeIf { it.isNotEmpty() },
                "templates" to card.templates.takeIf { it.isNotEmpty() },
                "modules" to card.modules.map(::moduleCard).takeIf { it.isNotEmpty() },
                "usages" to card.usages?.let(::usageNode),
                "truncated" to card.truncated,
                "hint" to
                    if (card.modules.isNotEmpty()) {
                        "Код модуля — dumpCode(path=…, id=…); процедуры — в routines; точки входа — dumpEntrypoints."
                    } else {
                        "У объекта нет модулей."
                    },
            )
        return pager.fit(frame.withoutNulls())
    }

    /** Страница точек входа. */
    fun entrypoints(page: EntryPointPage): String {
        val header =
            linkedMapOf<String, Any?>(
                "kind" to page.kind,
                "metadata" to page.metadata,
                "total" to page.total,
                "counts" to page.counts,
            )
        return pager.page(
            header = header,
            items = page.items,
            offset = 0,
            total = page.total,
            render = { entryPoint ->
                linkedMapOf<String, Any?>(
                    "kind" to entryPoint.kind,
                    "name" to entryPoint.name,
                    "owner" to entryPoint.owner,
                    "handler" to entryPoint.handler,
                    "event" to entryPoint.event,
                    "path" to entryPoint.path,
                    "sources" to entryPoint.sources.takeIf { it.isNotEmpty() },
                ).withoutNulls()
            },
            hint = "Фильтры: kind (form, subscription, job, http, web), metadata=Catalog.Имя.",
        )
    }

    /** Результат проверки модуля. */
    fun check(result: CheckResult): String {
        val errors = result.problems.count { it.severity == "error" }
        val warnings = result.problems.count { it.severity == "warning" }
        val frame =
            linkedMapOf<String, Any?>(
                "path" to result.path,
                "source" to result.source,
                "lines" to result.lines,
                "routines" to
                    result.routines.map { routine ->
                        linkedMapOf<String, Any?>(
                            "name" to routine.name,
                            "kind" to routine.kind,
                            "export" to routine.export,
                            "parameters" to routine.parameters,
                            "lines" to "${routine.startLine}-${routine.endLine}",
                        )
                    },
                "calls" to result.calls,
                "metadataAccesses" to
                    result.metadataAccesses.map {
                        linkedMapOf<String, Any?>(
                            "collection" to it.collection,
                            "name" to it.name,
                            "context" to it.context,
                            "line" to it.line,
                        )
                    },
                "counts" to linkedMapOf<String, Any?>("errors" to errors, "warnings" to warnings),
                "problems" to
                    result.problems.map {
                        linkedMapOf<String, Any?>(
                            "line" to it.line,
                            "severity" to it.severity,
                            "code" to it.code,
                            "message" to it.message,
                            "hint" to it.hint,
                        ).withoutNulls()
                    },
                "notes" to result.notes.takeIf { it.isNotEmpty() },
            )
        return pager.fit(frame.withoutNulls())
    }

    fun code(result: DumpCodeResult): String =
        pager.fit(
            linkedMapOf<String, Any?>(
                "path" to result.path,
                "moduleKind" to result.moduleKind,
                "owner" to result.owner,
                "totalLines" to result.totalLines,
                "from" to result.from,
                "to" to result.to,
                "text" to result.text,
                "hint" to
                    if (result.to < result.totalLines) {
                        "Продолжение — from=${result.to + 1}; не больше ${result.to - result.from + 1} строк за вызов."
                    } else {
                        null
                    },
            ).withoutNulls(),
        )

    fun grep(result: GrepResult): String {
        val frame =
            linkedMapOf<String, Any?>(
                "pattern" to result.pattern,
                "hits" to
                    result.hits.map { hit ->
                        linkedMapOf<String, Any?>(
                            "path" to hit.path,
                            "line" to hit.line,
                            "text" to hit.text,
                            "owner" to hit.owner,
                            "routine" to hit.routine,
                        ).withoutNulls()
                    },
                "filesScanned" to result.filesScanned,
                "totalFiles" to result.totalFiles,
                "complete" to result.complete,
                "elapsedMs" to result.elapsedMs,
                "hint" to
                    if (!result.complete) {
                        "Поиск остановлен: увеличьте deadlineMs, сузьте paths или extensions."
                    } else {
                        null
                    },
            )
        return pager.fit(frame.withoutNulls())
    }

    fun statusJson(snapshot: DumpSnapshot): String = pager.fit(status(snapshot))

    /** Ошибка в том же виде, что у инструментов справки. */
    fun error(
        code: String,
        message: String,
        suggestions: List<String> = emptyList(),
    ): String {
        val error = linkedMapOf<String, Any?>("code" to code, "message" to message)
        if (suggestions.isNotEmpty()) error["suggestions"] = suggestions
        return pager.fit(linkedMapOf("error" to error))
    }

    fun status(snapshot: DumpSnapshot): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "state" to snapshot.state.name.lowercase(),
            "path" to snapshot.path,
            "error" to snapshot.error,
            "elapsedMs" to snapshot.elapsedMs,
            "progress" to
                snapshot.progress?.let { progress ->
                    linkedMapOf<String, Any?>(
                        "phase" to progress.phase,
                        "done" to progress.done,
                        "total" to progress.total,
                    )
                },
            "index" to
                snapshot.indexFile?.let { file ->
                    linkedMapOf<String, Any?>("file" to file.toString(), "bytes" to snapshot.indexBytes)
                },
            "info" to
                snapshot.info?.let { info ->
                    linkedMapOf<String, Any?>(
                        "configuration" to info.configuration,
                        "version" to info.version,
                        "objects" to info.objects,
                        "fields" to info.fields,
                        "modules" to info.modules,
                        "routines" to info.routines,
                        "buildMillis" to info.buildMillis,
                        "indexedAt" to info.indexedAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() },
                    ).withoutNulls()
                },
            "hint" to
                when (snapshot.state) {
                    DumpStorage.State.LOADING -> "Индекс строится; повторите запрос через несколько секунд."
                    DumpStorage.State.FAILED -> "Проверьте путь к выгрузке и повторите dumpOpen."
                    DumpStorage.State.CLOSED -> "Выгрузка не открыта: --dump <каталог> или dumpOpen."
                    else -> null
                },
        ).withoutNulls()

    private fun searchItem(item: DumpSearchItem): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "kind" to item.kind,
            "name" to item.name,
            "synonym" to item.synonym.takeIf { it.isNotBlank() },
            "owner" to item.owner,
            "path" to item.path,
            "detail" to item.detail,
            "id" to item.id.takeIf { it > 0 },
        ).withoutNulls()

    private fun usageNode(usage: UsageSummary): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "total" to usage.total,
            "byContext" to usage.byContext,
            "readers" to
                usage.readers.map { reader ->
                    linkedMapOf<String, Any?>(
                        "module" to reader.modulePath,
                        "routine" to reader.routine,
                        "context" to reader.context,
                        "line" to reader.line,
                    ).withoutNulls()
                },
        )

    /** Карточка узла графа. */
    fun node(node: GraphNode): String =
        pager.fit(
            linkedMapOf<String, Any?>(
                "id" to node.id,
                "kind" to node.kind,
                "name" to node.name,
                "owner" to node.owner,
                "path" to node.path,
                "moduleKind" to node.moduleKind,
                "export" to node.export,
                "lines" to node.startLine?.let { "$it-${node.endLine}" },
                "parameters" to node.parameters.takeIf { it.isNotEmpty() },
                "routines" to
                    node.routines
                        .map {
                            linkedMapOf<String, Any?>(
                                "id" to "routine:${it.id}",
                                "name" to it.name,
                                "kind" to it.kind,
                                "export" to it.export,
                                "parameters" to it.parameters.takeIf { value -> value.isNotBlank() },
                                "lines" to "${it.startLine}-${it.endLine}",
                            ).withoutNulls()
                        }.takeIf { it.isNotEmpty() },
                "usages" to node.usages?.let(::usageNode),
                "hint" to
                    if (node.kind == "routine" || node.kind == "module") {
                        "Связи — dumpNeighbors(id=…); таблица символов — dumpTypes."
                    } else {
                        "Карточка объекта — dumpMetadata."
                    },
            ).withoutNulls(),
        )

    /** Входящие/исходящие вызовы и обращения к метаданным. */
    fun neighbors(result: NeighborsResult): String =
        pager.page(
            header =
                linkedMapOf<String, Any?>(
                    "id" to result.id,
                    "node" to
                        linkedMapOf<String, Any?>(
                            "kind" to result.node.kind,
                            "name" to result.node.name,
                            "owner" to result.node.owner,
                            "path" to result.node.path,
                            "lines" to result.node.startLine?.let { "${result.node.startLine}-${result.node.endLine}" },
                        ).withoutNulls(),
                    "counts" to result.counts,
                ),
            items = result.incoming + result.outgoing + result.metadata,
            offset = 0,
            total = result.incoming.size + result.outgoing.size + result.metadata.size,
            render = { item ->
                when (item) {
                    is GraphEdge ->
                        linkedMapOf<String, Any?>(
                            "edge" to item.direction,
                            "kind" to item.kind,
                            "name" to item.name,
                            "line" to item.line,
                            "sourceModule" to item.sourceModule,
                            "sourceRoutine" to item.sourceRoutine,
                            "targetModule" to item.targetModule,
                            "targetRoutine" to item.targetRoutine,
                            "targetId" to item.targetId,
                            "external" to item.external,
                        ).withoutNulls()
                    is MetadataRefRowView ->
                        linkedMapOf<String, Any?>(
                            "edge" to "metadata",
                            "object" to item.objectName,
                            "collection" to item.collection,
                            "context" to item.context,
                            "line" to item.line,
                        )
                    else -> emptyMap()
                }
            },
            hint = "Направление: in, out, both; kinds: calls, metadata; limit 1–500.",
        )

    /** Таблица символов модуля. */
    fun types(result: TypesResult): String =
        pager.fit(
            linkedMapOf<String, Any?>(
                "path" to result.path,
                "lines" to result.lines,
                "moduleLocals" to result.moduleLocals.map(::symbolNode).takeIf { it.isNotEmpty() },
                "routines" to
                    result.routines.map { routine ->
                        linkedMapOf<String, Any?>(
                            "name" to routine.name,
                            "kind" to routine.kind,
                            "export" to routine.export,
                            "parameters" to routine.parameters.map(::symbolNode).takeIf { it.isNotEmpty() },
                            "locals" to routine.locals.map(::symbolNode).takeIf { it.isNotEmpty() },
                            "calls" to
                                routine.calls
                                    .map {
                                        linkedMapOf<String, Any?>(
                                            "name" to it.name,
                                            "qualifier" to it.qualifier,
                                            "line" to it.line,
                                            "arguments" to it.arguments,
                                        ).withoutNulls()
                                    }.takeIf { it.isNotEmpty() },
                        ).withoutNulls()
                    },
                "hint" to "Тип выводится по правой части присваивания; отсутствие type — тип не определён.",
            ).withoutNulls(),
        )

    /** Права ролей. */
    fun rights(page: RightsPage): String =
        pager.page(
            header =
                linkedMapOf<String, Any?>(
                    "role" to page.role,
                    "metadata" to page.metadata,
                    "condition" to page.condition,
                    "total" to page.total,
                    "roles" to page.roles,
                ).withoutNulls(),
            items = page.items,
            offset = 0,
            total = page.total,
            render = { right ->
                linkedMapOf<String, Any?>(
                    "role" to right.role,
                    "object" to right.objectRef,
                    "right" to right.right,
                    "value" to right.value,
                    "condition" to right.condition,
                ).withoutNulls()
            },
            hint = "Фильтры: role, metadata, condition; limit 1–1000.",
        )

    /** Похожие процедуры. */
    fun similar(page: SimilarPage): String =
        pager.page(
            header =
                linkedMapOf<String, Any?>(
                    "source" to page.source,
                    "targetRoutines" to page.targetRoutines,
                    "total" to page.items.size,
                ),
            items = page.items,
            offset = 0,
            total = page.items.size,
            render = { item ->
                linkedMapOf<String, Any?>(
                    "id" to "routine:${item.id}",
                    "name" to item.name,
                    "module" to item.module,
                    "lines" to item.lines,
                    "score" to item.score,
                    "sharedCalls" to item.sharedCalls.takeIf { it.isNotEmpty() },
                    "sharedMetadata" to item.sharedMetadata.takeIf { it.isNotEmpty() },
                ).withoutNulls()
            },
            hint = "Похожесть — эвристика по общим вызовам и метаданным.",
        )

    /** Конвенции кода. */
    fun conventions(result: ConventionsResult): String =
        pager.page(
            header =
                linkedMapOf<String, Any?>(
                    "path" to result.path,
                    "lines" to result.lines,
                    "total" to result.findings.size,
                    "catalog" to
                        result.catalog.map {
                            mapOf("code" to it.code, "title" to it.title, "severity" to it.severity)
                        },
                ),
            items = result.findings,
            offset = 0,
            total = result.findings.size,
            render = { finding ->
                linkedMapOf<String, Any?>(
                    "code" to finding.code,
                    "severity" to finding.severity,
                    "line" to finding.line,
                    "message" to finding.message,
                    "hint" to finding.hint,
                )
            },
            hint = "Конвенции — эвристики, а не линтер платформы.",
        )

    private fun symbolNode(symbol: TypeSymbol): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "name" to symbol.name,
            "line" to symbol.line,
            "type" to symbol.type,
        ).withoutNulls()

    private fun fieldNode(field: DumpField): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "name" to field.name,
            "synonym" to field.synonym.takeIf { it.isNotBlank() },
            "type" to field.type.takeIf { it.isNotBlank() },
            "fieldKind" to field.fieldKind,
            "tabular" to field.tabular,
        ).withoutNulls()

    private fun moduleCard(card: com.alexqsoft.business.services.DumpModuleCard): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "id" to card.module.id,
            "path" to card.module.path,
            "kind" to card.module.kind,
            "lines" to card.module.lines,
            "routinesTotal" to card.routinesTotal,
            "routines" to card.routines.map(::routineNode),
        ).withoutNulls()

    private fun routineNode(routine: DumpRoutine): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "name" to routine.name,
            "kind" to routine.kind,
            "export" to routine.export,
            "parameters" to routine.parameters.takeIf { it.isNotBlank() },
            "lines" to "${routine.startLine}-${routine.endLine}",
        ).withoutNulls()

    private fun Map<String, Any?>.withoutNulls(): Map<String, Any?> = filterValues { it != null }
}
