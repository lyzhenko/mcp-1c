/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.business.services

import com.alexqsoft.business.entities.DumpModule
import com.alexqsoft.business.entities.DumpObject
import com.alexqsoft.exceptions.DefinitionNotFoundException
import com.alexqsoft.exceptions.InvalidSearchQueryException
import com.alexqsoft.infrastructure.dump.BslParser
import com.alexqsoft.infrastructure.dump.DumpIndex
import com.alexqsoft.infrastructure.dump.DumpStorage
import com.alexqsoft.infrastructure.dump.DumpTextReader
import com.alexqsoft.infrastructure.dump.ParsedLocal

/** Узел графа: процедура, модуль или объект метаданных. */
data class GraphNode(
    val id: String,
    val kind: String,
    val name: String,
    val owner: String?,
    val path: String?,
    val moduleKind: String?,
    val export: Boolean?,
    val startLine: Int?,
    val endLine: Int?,
    val parameters: List<String>,
    val routines: List<GraphRoutine>,
    val usages: com.alexqsoft.infrastructure.dump.UsageSummary?,
)

data class GraphRoutine(
    val id: Long,
    val name: String,
    val kind: String,
    val export: Boolean,
    val parameters: String,
    val startLine: Int,
    val endLine: Int,
)

/** Ребро графа в ответе. */
data class GraphEdge(
    val direction: String,
    val kind: String,
    val sourceId: String?,
    val sourceModule: String?,
    val sourceRoutine: String?,
    val targetId: String?,
    val targetModule: String?,
    val targetRoutine: String?,
    val name: String,
    val line: Int,
    val external: String?,
)

data class NeighborsResult(
    val id: String,
    val node: GraphNode,
    val incoming: List<GraphEdge>,
    val outgoing: List<GraphEdge>,
    val metadata: List<MetadataRefRowView>,
    val counts: Map<String, Int>,
)

data class MetadataRefRowView(
    val objectName: String,
    val collection: String,
    val context: String,
    val line: Int,
)

/** Переменная с выведенным типом. */
data class TypeSymbol(
    val name: String,
    val line: Int,
    val type: String?,
)

data class TypeRoutine(
    val name: String,
    val kind: String,
    val export: Boolean,
    val parameters: List<TypeSymbol>,
    val locals: List<TypeSymbol>,
    val calls: List<TypeCall>,
)

data class TypeCall(
    val name: String,
    val qualifier: String?,
    val line: Int,
    val arguments: Int,
)

data class TypesResult(
    val path: String,
    val lines: Int,
    val routines: List<TypeRoutine>,
    val moduleLocals: List<TypeSymbol>,
)

/**
 * Навигация по графу выгрузки: карточка узла, входящие/исходящие вызовы и обращения к метаданным,
 * таблица символов модуля с простым выводом типов.
 */
class DumpGraphService(
    private val storage: DumpStorage,
) {
    fun node(id: String): GraphNode {
        val index = requireIndex()
        return resolveNode(index, id)
    }

    fun neighbors(
        id: String,
        direction: String? = null,
        kinds: List<String>? = null,
        limit: Int? = null,
    ): NeighborsResult {
        val index = requireIndex()
        val node = resolveNode(index, id)
        val safeLimit = (limit ?: 100).coerceIn(1, 500)
        val normalizedDirection = direction?.lowercase()?.takeIf { it in setOf("in", "out", "both") } ?: "both"
        val kindFilter = kinds?.map { it.lowercase() }?.toSet().orEmpty()

        val incoming = mutableListOf<GraphEdge>()
        val outgoing = mutableListOf<GraphEdge>()
        if ("calls" in kindFilter || kindFilter.isEmpty()) {
            when {
                id.startsWith("routine:") ->
                    fillCalls(
                        index,
                        id.removePrefix("routine:").toLong(),
                        normalizedDirection,
                        incoming,
                        outgoing,
                    )
                id.startsWith("module:") -> {
                    val moduleId = id.removePrefix("module:").toLong()
                    if (normalizedDirection != "out") {
                        incoming += index.callsIntoModule(moduleId).map { it.toEdge("in", "module:$moduleId") }
                    }
                    if (normalizedDirection != "in") {
                        outgoing += index.callsOfModule(moduleId).map { it.toEdge("out", "module:$moduleId") }
                    }
                }
            }
        }
        val metadata =
            when {
                id.startsWith("routine:") -> index.metadataRefsForRoutine(id.removePrefix("routine:").toLong())
                id.startsWith("module:") -> index.metadataRefsForModule(id.removePrefix("module:").toLong())
                else -> emptyList()
            }.map { MetadataRefRowView(it.objectName, it.collection, it.context, it.line) }

        return NeighborsResult(
            id = id,
            node = node,
            incoming = incoming.take(safeLimit),
            outgoing = outgoing.take(safeLimit),
            metadata = metadata.take(safeLimit),
            counts =
                linkedMapOf(
                    "incoming" to incoming.size,
                    "outgoing" to outgoing.size,
                    "metadata" to metadata.size,
                ),
        )
    }

    fun types(
        path: String? = null,
        id: Long? = null,
        text: String? = null,
    ): TypesResult {
        val index = requireIndex()
        val source =
            when {
                text != null -> text to (path?.replace('\\', '/') ?: "draft.bsl")
                id != null -> {
                    val module = index.module(id) ?: throw DefinitionNotFoundException("модуль #$id")
                    readModule(index, module) to module.path
                }
                !path.isNullOrBlank() -> {
                    val normalized = path.replace('\\', '/').trimStart('/')
                    val module =
                        index.moduleByPath(normalized)
                            ?: throw DefinitionNotFoundException("модуль «$normalized»")
                    readModule(index, module) to module.path
                }
                else -> throw InvalidSearchQueryException("укажите path, id или text")
            }
        val parsed = BslParser.parse(source.first)
        return TypesResult(
            path = source.second,
            lines = parsed.lines,
            routines =
                parsed.routines.map { routine ->
                    TypeRoutine(
                        name = routine.name,
                        kind = routine.kind,
                        export = routine.export,
                        parameters = routine.parameters.map { TypeSymbol(it.name, it.line, null) },
                        locals = routine.locals.map { it.toSymbol() },
                        calls =
                            routine.calls.map {
                                TypeCall(it.name, it.qualifier, it.line, it.arguments)
                            },
                    )
                },
            moduleLocals = parsed.moduleLocals.map { it.toSymbol() },
        )
    }

    private fun fillCalls(
        index: DumpIndex,
        routineId: Long,
        direction: String,
        incoming: MutableList<GraphEdge>,
        outgoing: MutableList<GraphEdge>,
    ) {
        if (direction != "out") {
            incoming += index.callsInto(routineId).map { it.toEdge("in", "routine:$routineId") }
        }
        if (direction != "in") {
            outgoing += index.callsFrom(routineId).map { it.toEdge("out", "routine:$routineId") }
        }
    }

    private fun com.alexqsoft.infrastructure.dump.CallEdge.toEdge(
        direction: String,
        currentId: String?,
    ): GraphEdge =
        GraphEdge(
            direction = direction,
            kind = if (external != null) "external" else "call",
            sourceId = if (direction == "out") currentId else null,
            sourceModule = sourceModule,
            sourceRoutine = sourceRoutine,
            targetId =
                if (direction == "out") {
                    targetRoutineId?.let { "routine:$it" }
                } else {
                    currentId?.takeIf { it.startsWith("routine:") }
                },
            targetModule = targetModule,
            targetRoutine = targetRoutine,
            name = if (qualifier.isNullOrBlank()) callee else "$qualifier.$callee",
            line = line,
            external = external,
        )

    private fun ParsedLocal.toSymbol(): TypeSymbol = TypeSymbol(name, line, typeHint)

    private fun readModule(
        index: DumpIndex,
        module: DumpModule,
    ): String {
        val file = storage.file(module.path) ?: throw DefinitionNotFoundException("файл «${module.path}»")
        return DumpTextReader.read(file)
    }

    private fun resolveNode(
        index: DumpIndex,
        id: String,
    ): GraphNode =
        when {
            id.startsWith("routine:") -> {
                val routineId =
                    id.removePrefix("routine:").toLongOrNull()
                        ?: throw InvalidSearchQueryException("неверный id узла: $id")
                val routine = index.routineById(routineId) ?: throw DefinitionNotFoundException("процедура #$routineId")
                val module = index.moduleById(routine.moduleId) ?: throw DefinitionNotFoundException("модуль #${routine.moduleId}")
                val owner = module.objectId?.let { index.objectById(it) }
                GraphNode(
                    id = id,
                    kind = "routine",
                    name = routine.name,
                    owner = owner?.let { "${it.kind}.${it.name}" },
                    path = module.path,
                    moduleKind = module.kind,
                    export = routine.export,
                    startLine = routine.startLine,
                    endLine = routine.endLine,
                    parameters =
                        routine.parameters
                            .split(',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() },
                    routines = emptyList(),
                    usages = null,
                )
            }
            id.startsWith("module:") -> {
                val moduleId =
                    id.removePrefix("module:").toLongOrNull()
                        ?: throw InvalidSearchQueryException("неверный id узла: $id")
                val module = index.moduleById(moduleId) ?: throw DefinitionNotFoundException("модуль #$moduleId")
                val owner = module.objectId?.let { index.objectById(it) }
                GraphNode(
                    id = id,
                    kind = "module",
                    name = module.path.substringAfterLast('/'),
                    owner = owner?.let { "${it.kind}.${it.name}" },
                    path = module.path,
                    moduleKind = module.kind,
                    export = null,
                    startLine = null,
                    endLine = module.lines,
                    parameters = emptyList(),
                    routines =
                        index.routines(module.id).map {
                            GraphRoutine(it.id, it.name, it.kind, it.export, it.parameters, it.startLine, it.endLine)
                        },
                    usages = null,
                )
            }
            id.startsWith("object:") -> {
                val objectId =
                    id.removePrefix("object:").toLongOrNull()
                        ?: throw InvalidSearchQueryException("неверный id узла: $id")
                val obj = index.objectById(objectId) ?: throw DefinitionNotFoundException("объект #$objectId")
                objectNode(index, obj, id)
            }
            id.startsWith("external:") ->
                GraphNode(
                    id = id,
                    kind = "external",
                    name = id.removePrefix("external:"),
                    owner = null,
                    path = null,
                    moduleKind = null,
                    export = null,
                    startLine = null,
                    endLine = null,
                    parameters = emptyList(),
                    routines = emptyList(),
                    usages = null,
                )
            else -> {
                val obj = objectByRef(index, id) ?: throw DefinitionNotFoundException("узел «$id»")
                objectNode(index, obj, id)
            }
        }

    private fun objectByRef(
        index: DumpIndex,
        id: String,
    ): DumpObject? {
        val dot = id.indexOf('.')
        if (dot > 0) {
            index.objectByKindAndName(id.substring(0, dot), id.substring(dot + 1))?.let { return it }
        }
        return index.findObjects(id, 5).firstOrNull()
    }

    private fun objectNode(
        index: DumpIndex,
        obj: DumpObject,
        id: String,
    ): GraphNode =
        GraphNode(
            id = id,
            kind = obj.kind,
            name = obj.name,
            owner = null,
            path = obj.path,
            moduleKind = null,
            export = null,
            startLine = null,
            endLine = null,
            parameters = emptyList(),
            routines = emptyList(),
            usages = index.usageSummary(obj.name),
        )

    private fun requireIndex(): DumpIndex =
        if (storage.currentPath() == null) {
            throw InvalidSearchQueryException("выгрузка не открыта")
        } else {
            storage.awaitReady() ?: throw InvalidSearchQueryException("индекс выгрузки ещё не готов")
        }
}
