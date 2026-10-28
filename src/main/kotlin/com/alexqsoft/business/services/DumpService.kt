/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.business.services

import com.alexqsoft.business.entities.DumpObject
import com.alexqsoft.exceptions.DefinitionNotFoundException
import com.alexqsoft.exceptions.DumpNotOpenException
import com.alexqsoft.exceptions.DumpNotReadyException
import com.alexqsoft.exceptions.InvalidSearchQueryException
import com.alexqsoft.infrastructure.dump.DumpIndex
import com.alexqsoft.infrastructure.dump.DumpSnapshot
import com.alexqsoft.infrastructure.dump.DumpStorage
import com.alexqsoft.infrastructure.dump.DumpTextReader
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.abs

/**
 * Работа с выгрузкой конфигурации: поиск, карточка объекта, код модулей, grep.
 *
 * Сервис отвечает за валидацию аргументов, разрешение имён и границы страниц; SQL-запросы
 * и разбор файлов — в инфраструктуре. Все ошибки — доменные исключения с текстом для JSON.
 */
@Service
class DumpService(
    private val storage: DumpStorage,
) {
    fun status(): DumpSnapshot = storage.snapshot()

    fun open(path: String): DumpSnapshot {
        if (path.isBlank()) throw InvalidSearchQueryException("путь к выгрузке не может быть пустым")
        storage.open(path)
        storage.awaitReady()
        return storage.snapshot()
    }

    fun reload(): DumpSnapshot {
        if (storage.currentPath() == null) throw DumpNotOpenException()
        storage.reload()
        storage.awaitReady()
        return storage.snapshot()
    }

    /** Поиск по объектам, реквизитам и процедурам. */
    fun search(
        query: String,
        kind: String? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): DumpSearchPage {
        if (query.isBlank()) throw InvalidSearchQueryException("поисковый запрос не может быть пустым")
        val index = requireIndex()
        val normalized = query.trim()
        val needle = normalized.lowercase(Locale.ROOT)
        val safeLimit = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val safeOffset = (offset ?: 0).coerceAtLeast(0)
        val kindFilter = kind?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val items = mutableListOf<RankedItem>()

        val objectKinds = setOf("object", "routine", "field")
        if (kindFilter == null || kindFilter !in objectKinds || kindFilter == "object") {
            index.findObjects(needle, FETCH_LIMIT).forEach { obj ->
                if (kindFilter != null && kindFilter !in objectKinds && !obj.kind.equals(kindFilter, true)) {
                    return@forEach
                }
                items +=
                    RankedItem(
                        rank = rank(obj.name, obj.synonym, needle),
                        item =
                            DumpSearchItem(
                                kind = obj.kind,
                                name = obj.name,
                                synonym = obj.synonym,
                                path = obj.path,
                                id = obj.id,
                            ),
                    )
            }
        }
        if (kindFilter == null || kindFilter == "routine") {
            index.findRoutines(needle, FETCH_LIMIT).forEach { hit ->
                items +=
                    RankedItem(
                        rank = rank(hit.routine.name, "", needle),
                        item =
                            DumpSearchItem(
                                kind = "routine",
                                name = hit.routine.name,
                                owner = ownerRef(hit.ownerKind, hit.ownerName),
                                path = hit.modulePath,
                                detail =
                                    "${hit.moduleKind}: ${hit.routine.kind}" +
                                        if (hit.routine.export) ", экспорт" else "",
                                id = hit.routine.id,
                            ),
                    )
            }
        }
        if (kindFilter == null || kindFilter == "field") {
            index.findFields(needle, FETCH_LIMIT).forEach { hit ->
                items +=
                    RankedItem(
                        rank = rank(hit.field.name, hit.field.synonym, needle),
                        item =
                            DumpSearchItem(
                                kind = "field",
                                name = hit.field.name,
                                synonym = hit.field.synonym,
                                owner = ownerRef(hit.ownerKind, hit.ownerName),
                                detail =
                                    hit.field.fieldKind +
                                        if (hit.field.tabular.isNullOrBlank()) "" else " в ${hit.field.tabular}",
                                id = hit.field.id,
                            ),
                    )
            }
        }

        val ordered =
            items
                .sortedWith(
                    compareBy<RankedItem> { it.rank }
                        .thenBy { it.item.name.length }
                        .thenBy { it.item.name.lowercase(Locale.ROOT) },
                ).map { it.item }
        val page = ordered.drop(safeOffset).take(safeLimit)
        return DumpSearchPage(
            query = normalized,
            kind = kind,
            total = ordered.size,
            offset = safeOffset,
            limit = safeLimit,
            items = page,
            suggestions = if (ordered.isEmpty()) suggest(index, normalized) else emptyList(),
        )
    }

    /** Карточка объекта метаданных. */
    fun metadata(
        ref: String,
        fieldLimit: Int? = null,
    ): DumpMetadataCard {
        if (ref.isBlank()) throw InvalidSearchQueryException("имя объекта не может быть пустым")
        val index = requireIndex()
        val obj = resolveObject(index, ref.trim()) ?: throw DefinitionNotFoundException(ref.trim())
        val limit = (fieldLimit ?: DEFAULT_FIELDS).coerceIn(1, MAX_FIELDS)
        val fields = index.fields(obj.id)

        val tabularNames = fields.filter { it.fieldKind == "TabularSection" }
        val tabular =
            tabularNames.map { section ->
                DumpTabularSection(
                    name = section.name,
                    synonym = section.synonym,
                    fields = fields.filter { it.tabular == section.name }.take(limit),
                )
            }
        val attributes =
            fields
                .filter { it.fieldKind != "TabularSection" && it.tabular == null && it.fieldKind !in STATIC_KINDS }
                .take(limit)
        val enumValues = fields.filter { it.fieldKind == "EnumValue" }.take(limit)
        val modules =
            index.modules(obj.id).map { module ->
                val routines = index.routines(module.id)
                DumpModuleCard(module, routines.take(MAX_ROUTINES), routines.size)
            }

        val usages = runCatching { index.usageSummary(obj.name, collectionsForKind(obj.kind)) }.getOrNull()

        return DumpMetadataCard(
            obj = obj,
            attributes = attributes,
            tabularSections = tabular,
            enumValues = enumValues,
            forms = fields.filter { it.fieldKind == "Form" }.map { it.name }.take(limit),
            commands = fields.filter { it.fieldKind == "Command" }.map { it.name }.take(limit),
            templates = fields.filter { it.fieldKind == "Template" }.map { it.name }.take(limit),
            modules = modules,
            totalFields = fields.size,
            truncated = fields.size > limit || modules.any { it.routinesTotal > MAX_ROUTINES },
            usages = usages,
        )
    }

    /** Точки входа: обработчики форм, подписки, задания, HTTP/веб-сервисы. */
    fun entrypoints(
        kind: String? = null,
        metadata: String? = null,
        limit: Int? = null,
    ): EntryPointPage {
        val index = requireIndex()
        val safeLimit = (limit ?: DEFAULT_ENTRYPOINTS).coerceIn(1, MAX_ENTRYPOINTS)
        val rows = index.entrypoints(kind?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }, metadata?.trim(), safeLimit)
        return EntryPointPage(
            kind = kind,
            metadata = metadata,
            total = rows.size,
            items =
                rows.map {
                    DumpEntryPoint(
                        kind = it.kind,
                        name = it.name,
                        owner = it.owner,
                        handler = it.handler,
                        event = it.event,
                        path = it.path,
                        sources = it.sources,
                    )
                },
            counts = index.entrypointCounts(),
        )
    }

    /** Текст модуля с номерами строк. */
    fun code(
        path: String? = null,
        id: Long? = null,
        from: Int? = null,
        to: Int? = null,
    ): DumpCodeResult {
        val index = requireIndex()
        val module =
            when {
                id != null -> index.module(id) ?: throw DefinitionNotFoundException("модуль #$id")
                !path.isNullOrBlank() ->
                    index.moduleByPath(relativePath(path))
                        ?: throw DefinitionNotFoundException("модуль «${relativePath(path)}»")

                else -> throw InvalidSearchQueryException("укажите path или id модуля")
            }

        val file = storage.file(module.path) ?: throw DefinitionNotFoundException("файл «${module.path}»")

        val text = DumpTextReader.read(file)
        val lines = text.split('\n')
        val total = if (lines.lastOrNull() == "") lines.size - 1 else lines.size
        val first = (from ?: 1).coerceIn(1, total.coerceAtLeast(1))
        val last = (to ?: (first + MAX_CODE_LINES)).coerceIn(first, minOf(total, first + MAX_CODE_LINES))
        val numbered =
            (first..last).joinToString("\n") { line ->
                "%5d  %s".format(line, lines.getOrElse(line - 1) { "" })
            }

        return DumpCodeResult(
            path = module.path,
            moduleKind = module.kind,
            owner = index.objectForModule(module.path)?.let { "${it.kind}.${it.name}" },
            totalLines = total,
            from = first,
            to = last,
            text = numbered,
        )
    }

    /** Поиск по тексту файлов выгрузки. */
    fun grep(
        pattern: String,
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        extensions: List<String>? = null,
        paths: List<String>? = null,
        context: Int = 0,
        limit: Int = 50,
        deadlineMs: Long = 8_000,
    ): GrepResult {
        if (pattern.isEmpty()) throw InvalidSearchQueryException("шаблон не может быть пустым")
        val index = requireIndex()
        val root = dumpRoot()
        val started = System.currentTimeMillis()
        val suffix =
            (extensions?.takeIf { it.isNotEmpty() } ?: DEFAULT_EXTENSIONS)
                .map { if (it.startsWith(".")) it.lowercase(Locale.ROOT) else ".${it.lowercase(Locale.ROOT)}" }
                .toSet()
        val prefixes = paths.orEmpty().map { relativePath(it).trimEnd('/') }.filter { it.isNotEmpty() }
        val maxHits = limit.coerceIn(1, MAX_GREP_LIMIT)
        val contextSize = context.coerceIn(0, 5)

        val matcher =
            if (regex) {
                Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
            } else {
                null
            }

        // Список модулей берём из индекса: обход 65 000 файлов выгрузки съедал весь бюджет
        // поиска ещё до чтения первого файла.
        val candidates = collectCandidates(root, suffix, prefixes, index)
        val deadline = System.currentTimeMillis() + deadlineMs.coerceIn(100, 120_000)
        val hits = mutableListOf<GrepHit>()
        var scanned = 0
        var stopped = false

        for (relative in candidates) {
            if (System.currentTimeMillis() > deadline) {
                stopped = true
                break
            }
            val text =
                runCatching { storage.file(relative)?.let { DumpTextReader.read(it) } }
                    .getOrNull()
                    ?: continue
            scanned++
            val lines = text.split('\n')
            for (indexLine in lines.indices) {
                val line = lines[indexLine]
                val matched = matcher?.containsMatchIn(line) ?: line.contains(pattern, ignoreCase)
                if (!matched) continue
                val lineNumber = indexLine + 1
                val from = (indexLine - contextSize).coerceAtLeast(0)
                val to = (indexLine + contextSize).coerceAtMost(lines.lastIndex)
                hits +=
                    GrepHit(
                        path = relative,
                        line = lineNumber,
                        text = lines.subList(from, to + 1).joinToString("\n"),
                        owner = index.objectForModule(relative)?.let { "${it.kind}.${it.name}" },
                        routine = index.routineAt(relative, lineNumber)?.name,
                    )
                if (hits.size >= maxHits) {
                    stopped = true
                    break
                }
            }
            if (hits.size >= maxHits) break
        }

        return GrepResult(
            pattern = pattern,
            hits = hits,
            filesScanned = scanned,
            totalFiles = candidates.size,
            complete = !stopped && scanned >= candidates.size,
            elapsedMs = System.currentTimeMillis() - started,
        )
    }

    private fun collectCandidates(
        root: Path,
        suffixes: Set<String>,
        prefixes: List<String>,
        index: DumpIndex,
    ): List<String> {
        val result = LinkedHashSet<String>()
        if (".bsl" in suffixes) {
            index.modulePaths().filterTo(result) { matchesPrefix(it, prefixes) }
        }
        val other = suffixes - ".bsl"
        if (other.isNotEmpty()) {
            Files.walk(root).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter { file ->
                        other.any {
                            file.fileName
                                .toString()
                                .lowercase(Locale.ROOT)
                                .endsWith(it)
                        }
                    }.map { root.relativize(it).toString().replace('\\', '/') }
                    .filter { matchesPrefix(it, prefixes) }
                    .forEach(result::add)
            }
        }
        return result.sorted()
    }

    private fun matchesPrefix(
        relative: String,
        prefixes: List<String>,
    ): Boolean =
        !relative.contains("/.data1c/") &&
            (prefixes.isEmpty() || prefixes.any { relative.startsWith(it) })

    private fun requireIndex(): DumpIndex {
        if (storage.currentPath() == null) throw DumpNotOpenException()
        return storage.awaitReady() ?: throw DumpNotReadyException(
            when (val state = storage.snapshot().state) {
                DumpStorage.State.LOADING -> "Индекс выгрузки ещё строится, повторите запрос через несколько секунд."
                DumpStorage.State.FAILED -> "Индекс выгрузки не построен: ${storage.snapshot().error ?: state}"
                else -> "Индекс выгрузки не готов."
            },
        )
    }

    private fun dumpRoot(): Path = storage.roots().firstOrNull() ?: throw DumpNotOpenException()

    private fun relativePath(path: String): String =
        path
            .trim()
            .replace('\\', '/')
            .removePrefix("./")
            .trimStart('/')

    private fun resolveObject(
        index: DumpIndex,
        ref: String,
    ): DumpObject? {
        if (ref.contains('.')) return index.objectByRef(ref)
        return index.findObjects(ref, 200).firstOrNull { it.name.equals(ref, ignoreCase = true) }
    }

    private fun ownerRef(
        kind: String?,
        name: String?,
    ): String? = if (kind.isNullOrBlank() || name.isNullOrBlank()) null else "$kind.$name"

    private fun rank(
        name: String,
        synonym: String,
        needle: String,
    ): Int {
        val lowerName = name.lowercase(Locale.ROOT)
        val lowerSynonym = synonym.lowercase(Locale.ROOT)
        return when {
            lowerName == needle -> 0
            lowerName.startsWith(needle) -> 1
            lowerName.contains(needle) -> 2
            lowerSynonym.contains(needle) -> 3
            else -> 4
        }
    }

    private fun suggest(
        index: DumpIndex,
        query: String,
    ): List<String> {
        val needle = query.lowercase(Locale.ROOT)
        if (needle.length < 2) return emptyList()
        val names = index.objectNames(SUGGEST_SCAN)
        val contains =
            names
                .filter { it.lowercase(Locale.ROOT).contains(needle) }
                .sortedBy { it.length }
                .take(5)
        if (contains.isNotEmpty()) return contains
        return names
            .asSequence()
            .filter { abs(it.length - needle.length) <= 2 }
            .map { it to levenshtein(it.lowercase(Locale.ROOT), needle) }
            .filter { it.second in 1..2 }
            .sortedBy { it.second }
            .map { it.first }
            .distinct()
            .take(5)
            .toList()
    }

    private fun levenshtein(
        left: String,
        right: String,
    ): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (i in 1..left.length) {
            current[0] = i
            for (j in 1..right.length) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }

    private fun collectionsForKind(kind: String): List<String> =
        when (kind) {
            "Catalog" -> listOf("Справочники", "Справочник")
            "Document" -> listOf("Документы", "Документ")
            "InformationRegister" -> listOf("РегистрыСведений", "РегистрСведений")
            "AccumulationRegister" -> listOf("РегистрыНакопления", "РегистрНакопления")
            "AccountingRegister" -> listOf("РегистрыБухгалтерии", "РегистрБухгалтерии")
            "CalculationRegister" -> listOf("РегистрыРасчета", "РегистрРасчета")
            "ChartOfAccounts" -> listOf("ПланыСчетов", "ПланСчетов")
            "ChartOfCharacteristicTypes" -> listOf("ПланыВидовХарактеристик", "ПланВидовХарактеристик")
            "ChartOfCalculationTypes" -> listOf("ПланыВидовРасчета", "ПланВидовРасчета")
            "ExchangePlan" -> listOf("ПланыОбмена", "ПланОбмена")
            "Enum" -> listOf("Перечисления", "Перечисление")
            "Report" -> listOf("Отчеты", "Отчет")
            "DataProcessor" -> listOf("Обработки", "Обработка")
            "BusinessProcess" -> listOf("БизнесПроцессы", "БизнесПроцесс")
            "Task" -> listOf("Задачи", "Задача")
            "DocumentJournal" -> listOf("ЖурналыДокументов", "ЖурналДокументов")
            "Constant" -> listOf("Константы", "Константа")
            else -> emptyList()
        }

    private data class RankedItem(
        val rank: Int,
        val item: DumpSearchItem,
    )

    companion object {
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 100
        private const val FETCH_LIMIT = 500
        private const val SUGGEST_SCAN = 20_000
        private const val DEFAULT_FIELDS = 200
        private const val MAX_FIELDS = 500
        private const val MAX_ROUTINES = 100
        private const val MAX_CODE_LINES = 2000
        private const val MAX_GREP_LIMIT = 200
        private const val DEFAULT_ENTRYPOINTS = 200
        private const val MAX_ENTRYPOINTS = 1000
        private val DEFAULT_EXTENSIONS = listOf(".bsl")
        private val STATIC_KINDS = setOf("Form", "Command", "Template", "EnumValue")
    }
}
