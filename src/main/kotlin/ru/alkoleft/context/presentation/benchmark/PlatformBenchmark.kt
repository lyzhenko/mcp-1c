/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.presentation.benchmark

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ru.alkoleft.context.business.entities.PlatformContextData
import ru.alkoleft.context.business.services.ConstructorsPage
import ru.alkoleft.context.business.services.InfoResult
import ru.alkoleft.context.business.services.MembersPage
import ru.alkoleft.context.business.valueobjects.ApiType
import ru.alkoleft.context.infrastructure.persistent.cache.PlatformCache
import ru.alkoleft.context.infrastructure.persistent.storage.PlatformContextLoader
import ru.alkoleft.context.infrastructure.platform.PlatformLocator
import ru.alkoleft.context.infrastructure.platform.PlatformOptions
import ru.alkoleft.context.infrastructure.search.PlatformCatalog
import ru.alkoleft.context.presentation.formatters.JsonResponseService
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Замеры сервера на реальной платформе: разбор, кэш, поиск и размер ответов.
 *
 * Запуск: `java -jar mcp-bsl-context.jar --benchmark --platform-path <путь>`.
 * Печатает JSON-отчёт; сервер MCP при этом не поднимается.
 */
class PlatformBenchmark(
    private val options: PlatformOptions,
) {
    private val mapper = jacksonObjectMapper()

    fun run() {
        BenchmarkLogging.routeToStderr()
        val locator = PlatformLocator()
        val cacheDir =
            options.cacheDir?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: PlatformCache.defaultDirectory()
        val cache = if (options.cacheEnabled) PlatformCache(cacheDir) else null
        val loader = PlatformContextLoader(locator, cache)

        val built =
            runCatching { loader.load(options.copy(refresh = true)) }
                .getOrElse { error ->
                    println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf("error" to error.message)))
                    exitProcess(1)
                }
        val read =
            runCatching { loader.load(options.copy(refresh = false)) }
                .getOrElse { built }

        val catalog = PlatformCatalog(read.data)
        val queries = sampleQueries(read.data)
        val searchMillis = queries.map { query -> measure { catalog.search(query, null, 10, 0) } }
        val formatter = JsonResponseService(options.responseLimit)
        val responseSizes =
            queries.map { query -> formatter.search(catalog.search(query, null, 10, 0)).length.toLong() }
        val samples = sampleResponses(catalog, formatter)

        val report =
            linkedMapOf<String, Any?>(
                "platform" to
                    linkedMapOf<String, Any?>(
                        "version" to read.data.info.version,
                        "locale" to read.data.info.locale,
                        "path" to read.data.info.path,
                        "sourceBytes" to read.data.info.sizeBytes,
                        "sourceHuman" to PlatformCache.humanSize(read.data.info.sizeBytes),
                    ),
                "catalog" to
                    linkedMapOf<String, Any?>(
                        "types" to read.data.types.size,
                        "methods" to read.data.methods.size,
                        "properties" to read.data.properties.size,
                        "enums" to read.data.enums.size,
                        "members" to read.data.types.sumOf { it.memberCount() },
                    ),
                "parse" to
                    linkedMapOf<String, Any?>(
                        "millis" to built.loadMillis,
                        "fromCache" to built.fromCache,
                    ),
                "cache" to
                    linkedMapOf<String, Any?>(
                        "dir" to cacheDir.toString(),
                        "file" to built.cacheFile?.toString(),
                        "bytes" to (built.cacheFile?.let { fileSize(it) } ?: 0),
                        "readMillis" to if (read.fromCache) read.loadMillis else null,
                    ),
                "search" to
                    linkedMapOf<String, Any?>(
                        "queries" to queries.size,
                        "avgMillis" to average(searchMillis),
                        "p50Millis" to percentile(searchMillis, 50),
                        "p95Millis" to percentile(searchMillis, 95),
                        "maxMillis" to (searchMillis.maxOrNull() ?: 0),
                        "avgResponseChars" to average(responseSizes),
                        "maxResponseChars" to (responseSizes.maxOrNull() ?: 0),
                    ),
                "loadLimits" to
                    linkedMapOf<String, Any?>(
                        "responseLimit" to options.responseLimit,
                        "waitTimeoutMs" to options.waitTimeoutMs,
                    ),
                "samples" to samples,
            )

        println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
    }

    /** Примеры ответов инструментов — их видно в отчёте и удобно проверить глазами. */
    private fun sampleResponses(
        catalog: PlatformCatalog,
        formatter: JsonResponseService,
    ): Map<String, String?> {
        val samples = linkedMapOf<String, String?>()
        samples["info СтрНайти"] = formatter.info(InfoResult(catalog.findExact("СтрНайти", ApiType.METHOD)))

        val array = catalog.findType("Массив")
        if (array != null) {
            catalog.findMember(array, "Добавить")?.let { member ->
                samples["member Массив.Добавить"] = formatter.definition(member)
            }
            samples["members Массив (5)"] =
                formatter.members(
                    MembersPage(array.name, array.memberCount(), 0, 5, array.members().take(5)),
                )
            if (array.constructors.isNotEmpty()) {
                samples["constructors Массив"] =
                    formatter.constructors(
                        ConstructorsPage(array.name, array.constructors.size, 0, 5, array.constructors.take(5)),
                    )
            }
        }
        samples["search Массив"] = formatter.search(catalog.search("Массив", null, 3, 0))
        return samples
    }

    /** Запросы для замера: имена типов, методов и составные «Тип.Член». */
    private fun sampleQueries(data: PlatformContextData): List<String> {
        val types =
            spread(data.types.map { it.name }, SAMPLE)
        val methods =
            spread(data.methods.map { it.name }, SAMPLE)
        val members =
            data.types
                .asSequence()
                .filter { it.methods.isNotEmpty() }
                .take(SAMPLE)
                .map { "${it.name}.${it.methods.first().name}" }
                .toList()
        return (types + methods + members).filter { it.isNotBlank() }.distinct()
    }

    private fun spread(
        values: List<String>,
        count: Int,
    ): List<String> {
        if (values.isEmpty()) return emptyList()
        val step = maxOf(1, values.size / count)
        return values.filterIndexed { index, _ -> index % step == 0 }.take(count)
    }

    private inline fun measure(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000
    }

    private fun percentile(
        values: List<Long>,
        percent: Int,
    ): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * percent / 100).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun average(values: List<Long>): Long = if (values.isEmpty()) 0 else values.average().toLong()

    private fun fileSize(path: Path): Long = runCatching { Files.size(path) }.getOrDefault(0)

    companion object {
        private const val SAMPLE = 30
    }
}
