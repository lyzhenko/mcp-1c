/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.business.services

import com.alexqsoft.exceptions.DefinitionNotFoundException
import com.alexqsoft.exceptions.InvalidSearchQueryException
import com.alexqsoft.infrastructure.dump.BslParser
import com.alexqsoft.infrastructure.dump.DumpStorage
import com.alexqsoft.infrastructure.dump.DumpTextReader
import java.util.Locale

/** Похожая процедура. */
data class SimilarRoutine(
    val id: Long,
    val name: String,
    val module: String,
    val lines: String,
    val score: Double,
    val sharedCalls: List<String>,
    val sharedMetadata: List<String>,
)

data class SimilarPage(
    val source: String,
    val targetRoutines: List<String>,
    val items: List<SimilarRoutine>,
)

/**
 * Поиск похожих процедур по общим вызовам и объектам метаданных.
 *
 * Это эвристика для навигации: кандидаты берутся из индекса по общим вызовам/ссылкам,
 * затем ранжируются по пересечению признаков и близости имени.
 */
class DumpSimilarService(
    private val storage: DumpStorage,
) {
    fun similar(
        path: String? = null,
        id: Long? = null,
        text: String? = null,
        routineName: String? = null,
        limit: Int? = null,
    ): SimilarPage {
        val index =
            if (storage.currentPath() == null) {
                throw InvalidSearchQueryException("выгрузка не открыта")
            } else {
                storage.awaitReady() ?: throw InvalidSearchQueryException("индекс выгрузки ещё не готов")
            }
        val normalizedPath = path?.replace('\\', '/')?.trimStart('/')
        val source: String
        val moduleText: String
        when {
            text != null -> {
                moduleText = text
                source = normalizedPath ?: "draft.bsl"
            }
            id != null -> {
                val module = index.module(id) ?: throw DefinitionNotFoundException("модуль #$id")
                moduleText = readModule(module.path)
                source = module.path
            }
            !normalizedPath.isNullOrBlank() -> {
                val module =
                    index.moduleByPath(normalizedPath)
                        ?: throw DefinitionNotFoundException("модуль «$normalizedPath»")
                moduleText = readModule(module.path)
                source = module.path
            }
            else -> throw InvalidSearchQueryException("укажите path, id или text")
        }

        val parsed = BslParser.parse(moduleText)
        val targets =
            if (routineName.isNullOrBlank()) {
                parsed.routines
            } else {
                listOfNotNull(parsed.routines.firstOrNull { it.name.equals(routineName, ignoreCase = true) })
            }
        if (targets.isEmpty()) {
            throw DefinitionNotFoundException("процедура «$routineName»")
        }
        val targetCalls = targets.flatMap { it.calls }.map { it.name.lowercase(Locale.ROOT) }.toSet()
        val targetRefs =
            targets.flatMap { it.metadataRefs }.map { it.objectName.lowercase(Locale.ROOT) }.toSet()
        val candidateIds =
            (
                index.routineIdsByCalls(targetCalls.toList()) +
                    index.routineIdsByMetadata(targetRefs.toList())
            ).distinct().take(300)
        val targetNames = targets.map { it.name }.toSet()
        val results =
            candidateIds
                .mapNotNull { candidateId ->
                    val routine = index.routineById(candidateId) ?: return@mapNotNull null
                    if (routine.name in targetNames) return@mapNotNull null
                    val calls = index.callsFrom(candidateId).map { it.callee.lowercase(Locale.ROOT) }.toSet()
                    val refs =
                        index
                            .metadataRefsForRoutine(candidateId)
                            .map { it.objectName.lowercase(Locale.ROOT) }
                            .toSet()
                    val sharedCalls = targetCalls intersect calls
                    val sharedRefs = targetRefs intersect refs
                    if (sharedCalls.isEmpty() && sharedRefs.isEmpty()) return@mapNotNull null
                    val nameBonus =
                        if (targets.any { it.name.equals(routine.name, ignoreCase = true) }) 0.3 else 0.0
                    val score =
                        (sharedCalls.size * 2.0 + sharedRefs.size * 2.0 + nameBonus) /
                            (targetCalls.size * 2.0 + targetRefs.size * 2.0 + 1.0)
                    val module = index.moduleById(routine.moduleId) ?: return@mapNotNull null
                    SimilarRoutine(
                        id = routine.id,
                        name = routine.name,
                        module = module.path,
                        lines = "${routine.startLine}-${routine.endLine}",
                        score = (score * 1000).toInt() / 1000.0,
                        sharedCalls = sharedCalls.take(10),
                        sharedMetadata = sharedRefs.take(10),
                    )
                }.sortedByDescending { it.score }
                .take((limit ?: 20).coerceIn(1, 100))
        return SimilarPage(source, targets.map { it.name }, results)
    }

    private fun readModule(modulePath: String): String {
        val file = storage.file(modulePath) ?: throw DefinitionNotFoundException("файл «$modulePath»")
        return DumpTextReader.read(file)
    }
}
