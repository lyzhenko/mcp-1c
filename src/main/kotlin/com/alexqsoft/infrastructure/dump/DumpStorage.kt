/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.infrastructure.dump

import com.alexqsoft.business.entities.DumpInfo
import com.alexqsoft.infrastructure.persistent.cache.PlatformCache
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

/**
 * Настройки индекса выгрузки.
 *
 * @property path каталог выгрузки; null — выгрузка не открыта (её откроет `dumpOpen`)
 * @property indexDir каталог индексов; null — каталог кэша пользователя
 * @property waitTimeoutMs сколько инструмент ждёт готовности индекса
 */
data class DumpOptions(
    val path: String? = null,
    val indexDir: String? = null,
    val warmup: Boolean = true,
    val waitTimeoutMs: Long = 60_000,
    val watchSeconds: Long = 0,
)

/**
 * Состояние индекса выгрузки.
 *
 * @property progress текущая фаза построения (для `status`)
 */
data class DumpSnapshot(
    val state: DumpStorage.State,
    val path: String?,
    val info: DumpInfo?,
    val error: String?,
    val progress: DumpBuildProgress?,
    val elapsedMs: Long,
    val indexFile: Path?,
    val indexBytes: Long,
)

/**
 * Хранилище индекса выгрузки: открытие, построение в фоне, повторное использование.
 *
 * Готовый индекс читается сразу при старте (метаданные хранят путь выгрузки и версию схемы).
 * Если путь другой или индекс битый — он строится заново в фоновом потоке; инструменты ждут
 * готовности не дольше [DumpOptions.waitTimeoutMs].
 */
class DumpStorage(
    private val options: DumpOptions,
    private val indexer: DumpIndexer = DumpIndexer(),
) : Closeable {
    enum class State {
        CLOSED,
        LOADING,
        READY,
        FAILED,
    }

    private val monitor = Object()

    @Volatile
    private var state: State = State.CLOSED

    @Volatile
    private var index: DumpIndex? = null

    @Volatile
    private var info: DumpInfo? = null

    @Volatile
    private var error: String? = null

    @Volatile
    private var progress: DumpBuildProgress? = null

    @Volatile
    private var dumpPath: String? = null

    @Volatile
    private var startedAt: Long = 0

    @Volatile
    private var finishedAt: Long = 0

    @Volatile
    private var watcher: Thread? = null

    @Volatile
    private var lastSignature: String = ""

    @Volatile
    private var moduleStamps: Map<String, Long>? = null

    /** Открыть выгрузку из `--dump`, если она задана. */
    @PostConstruct
    fun init() {
        options.path?.takeIf { it.isNotBlank() }?.let { open(it) }
    }

    /** Открыть (или переоткрыть) выгрузку; повторный вызов для того же пути переиспользует индекс. */
    fun open(path: String) {
        val paths =
            path
                .split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { Path.of(it).toAbsolutePath().normalize() }
        if (paths.isEmpty() || paths.any { !Files.isDirectory(it) }) {
            synchronized(monitor) {
                state = State.FAILED
                error = "Каталог выгрузки не найден: $path"
                monitor.notifyAll()
            }
            return
        }
        val key = paths.joinToString(";")

        synchronized(monitor) {
            if (dumpPath == key && state == State.READY) return
            dumpPath = key
            state = State.LOADING
            startedAt = System.currentTimeMillis()
            finishedAt = 0
            error = null
            progress = null
            info = null
            val thread =
                Thread({ load(paths, key, indexFile(key)) }, "dump-indexer").apply { isDaemon = true }
            thread.start()
            startWatcher()
        }
    }

    /** Перестроить индекс текущей выгрузки. */
    fun reload() {
        val path = dumpPath ?: return
        val paths = path.split(';').map { Path.of(it) }
        synchronized(monitor) {
            state = State.LOADING
            startedAt = System.currentTimeMillis()
            finishedAt = 0
            error = null
            progress = null
            info = null
            val thread =
                Thread({ load(paths, path, indexFile(path)) }, "dump-indexer").apply { isDaemon = true }
            thread.start()
        }
    }

    /** Дождаться готовности индекса; null — ещё строится или построить не удалось. */
    fun awaitReady(): DumpIndex? {
        synchronized(monitor) {
            val deadline = System.currentTimeMillis() + options.waitTimeoutMs
            while (state == State.LOADING) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                try {
                    monitor.wait(left)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            return if (state == State.READY) index else null
        }
    }

    fun snapshot(): DumpSnapshot =
        synchronized(monitor) {
            DumpSnapshot(
                state = state,
                path = dumpPath,
                info = info,
                error = error,
                progress = progress,
                elapsedMs =
                    when (state) {
                        State.LOADING -> System.currentTimeMillis() - startedAt
                        State.READY -> finishedAt - startedAt
                        else -> 0
                    },
                indexFile = dumpPath?.let { indexFile(it) },
                indexBytes = dumpPath?.let { runCatching { Files.size(indexFile(it)) }.getOrDefault(0) } ?: 0,
            )
        }

    fun currentInfo(): DumpInfo? = info

    fun currentPath(): String? = dumpPath

    /** Каталоги выгрузки: базовый и расширения. */
    fun roots(): List<Path> = dumpPath?.split(';')?.map { Path.of(it) } ?: emptyList()

    /** Файл по относительному пути с учётом расширений (последний слой важнее). */
    fun file(relative: String): Path? =
        roots().asReversed().firstNotNullOfOrNull { root ->
            val candidate = root.resolve(relative).normalize()
            candidate.takeIf { it.startsWith(root) && Files.isRegularFile(it) }
        }

    /** Закрыть индекс (в тестах и при остановке приложения Windows отпускает файл). */
    override fun close() {
        synchronized(monitor) {
            index?.close()
            index = null
            state = State.CLOSED
            monitor.notifyAll()
        }
    }

    private fun scanModuleStamps(): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        for (root in roots()) {
            Files.walk(root).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".bsl", ignoreCase = true) }
                    .forEach { file ->
                        result[root.relativize(file).toString().replace('\\', '/')] =
                            runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0)
                    }
            }
        }
        return result
    }

    private fun startWatcher() {
        if (options.watchSeconds <= 0 || watcher?.isAlive == true) return
        watcher =
            Thread(
                {
                    while (!Thread.currentThread().isInterrupted) {
                        try {
                            Thread.sleep(options.watchSeconds.coerceAtLeast(5) * 1000)
                            val roots = roots()
                            if (roots.isEmpty()) continue
                            val signature =
                                roots.joinToString("|") { root ->
                                    runCatching {
                                        Files.getLastModifiedTime(root.resolve("ConfigDumpInfo.xml")).toMillis()
                                    }.getOrDefault(0L).toString()
                                }
                            if (lastSignature.isNotEmpty() && signature != lastSignature) {
                                val current = runCatching { scanModuleStamps() }.getOrDefault(emptyMap())
                                val previous = moduleStamps
                                if (previous != null && state == State.READY) {
                                    val changed = current.filter { previous[it.key] != it.value }.keys
                                    val deleted = previous.keys - current.keys
                                    val touched = changed + deleted
                                    val modulesOnly = touched.all { it.endsWith(".bsl") }
                                    if (modulesOnly && touched.isNotEmpty()) {
                                        try {
                                            val updated =
                                                indexer.updateModules(
                                                    indexFile(dumpPath!!),
                                                    roots,
                                                    changed,
                                                    deleted,
                                                )
                                            moduleStamps = current
                                            logger.info { "Частично обновлено модулей: $updated" }
                                        } catch (failure: Throwable) {
                                            logger.warn { "Частичное обновление не удалось: ${failure.message}" }
                                            reload()
                                            moduleStamps = null
                                        }
                                    } else {
                                        reload()
                                        moduleStamps = null
                                    }
                                } else {
                                    moduleStamps = current
                                }
                            }
                            lastSignature = signature
                        } catch (_: InterruptedException) {
                            return@Thread
                        } catch (failure: Throwable) {
                            logger.warn { "Наблюдение за выгрузкой: ${failure.message}" }
                        }
                    }
                },
                "dump-watch",
            ).apply { isDaemon = true }
        watcher?.start()
    }

    private fun load(
        paths: List<Path>,
        key: String,
        indexFile: Path,
    ) {
        try {
            val existing = openExisting(key, indexFile)
            if (existing != null) {
                synchronized(monitor) {
                    index?.close()
                    index = existing
                    info = existing.info()
                    state = State.READY
                    finishedAt = System.currentTimeMillis()
                    monitor.notifyAll()
                }
                logger.info { "Индекс выгрузки открыт: $indexFile (${Files.size(indexFile)} байт)" }
                moduleStamps = runCatching { scanModuleStamps() }.getOrNull()
                return
            }

            val built =
                indexer.build(paths.first(), indexFile, paths.drop(1)) { update ->
                    synchronized(monitor) { progress = update }
                }
            val opened = DumpIndex(indexFile)
            synchronized(monitor) {
                index?.close()
                index = opened
                info = built
                state = State.READY
                finishedAt = System.currentTimeMillis()
                progress = null
                monitor.notifyAll()
            }
            moduleStamps = runCatching { scanModuleStamps() }.getOrNull()
        } catch (failure: Throwable) {
            logger.error(failure) { "Не удалось построить индекс выгрузки" }
            synchronized(monitor) {
                error = failure.message ?: failure.toString()
                state = State.FAILED
                finishedAt = System.currentTimeMillis()
                monitor.notifyAll()
            }
        }
    }

    private fun openExisting(
        key: String,
        indexFile: Path,
    ): DumpIndex? {
        if (!Files.isRegularFile(indexFile)) return null
        val candidate =
            runCatching { DumpIndex(indexFile) }
                .getOrElse { error ->
                    logger.warn { "Индекс не открылся, пересоберём: ${error.message}" }
                    return null
                }
        val info = candidate.info()
        val valid =
            info.path == key &&
                info.schema == DumpIndex.SCHEMA_VERSION &&
                info.objects > 0
        if (!valid) {
            candidate.close()
            logger.info { "Индекс устарел (путь/схема), пересобираем" }
            return null
        }
        return candidate
    }

    private fun indexFile(key: String): Path {
        val directory =
            options.indexDir?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?: PlatformCache.defaultDirectory().resolve("dumps")
        return directory.resolve("dump-${sha1(key).take(12)}.db")
    }

    private fun sha1(value: String): String =
        MessageDigest
            .getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
