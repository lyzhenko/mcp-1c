/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.infrastructure.dump

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import ru.alkoleft.context.business.entities.DumpInfo
import ru.alkoleft.context.infrastructure.persistent.cache.PlatformCache
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

    /** Открыть выгрузку из `--dump`, если она задана. */
    @PostConstruct
    fun init() {
        options.path?.takeIf { it.isNotBlank() }?.let { open(it) }
    }

    /** Открыть (или переоткрыть) выгрузку; повторный вызов для того же пути переиспользует индекс. */
    fun open(path: String) {
        val normalized = Path.of(path).toAbsolutePath().normalize()
        if (!Files.isDirectory(normalized)) {
            synchronized(monitor) {
                state = State.FAILED
                error = "Каталог выгрузки не найден: $normalized"
                monitor.notifyAll()
            }
            return
        }

        synchronized(monitor) {
            if (dumpPath == normalized.toString() && state == State.READY) return
            dumpPath = normalized.toString()
            state = State.LOADING
            startedAt = System.currentTimeMillis()
            finishedAt = 0
            error = null
            progress = null
            info = null
            val thread = Thread({ load(normalized, indexFile(normalized)) }, "dump-indexer").apply { isDaemon = true }
            thread.start()
        }
    }

    /** Перестроить индекс текущей выгрузки. */
    fun reload() {
        val path = dumpPath ?: return
        synchronized(monitor) {
            state = State.LOADING
            startedAt = System.currentTimeMillis()
            finishedAt = 0
            error = null
            progress = null
            info = null
            val thread = Thread({ load(Path.of(path), indexFile(Path.of(path))) }, "dump-indexer").apply { isDaemon = true }
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
                indexFile = dumpPath?.let { indexFile(Path.of(it)) },
                indexBytes = dumpPath?.let { runCatching { Files.size(indexFile(Path.of(it))) }.getOrDefault(0) } ?: 0,
            )
        }

    fun currentInfo(): DumpInfo? = info

    fun currentPath(): String? = dumpPath

    /** Закрыть индекс (в тестах и при остановке приложения Windows отпускает файл). */
    override fun close() {
        synchronized(monitor) {
            index?.close()
            index = null
            state = State.CLOSED
            monitor.notifyAll()
        }
    }

    private fun load(
        path: Path,
        indexFile: Path,
    ) {
        try {
            val existing = openExisting(path, indexFile)
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
                return
            }

            val built =
                indexer.build(path, indexFile) { update ->
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
        path: Path,
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
            info.path == path.toString() &&
                info.schema == DumpIndex.SCHEMA_VERSION &&
                info.objects > 0
        if (!valid) {
            candidate.close()
            logger.info { "Индекс устарел (путь/схема), пересобираем" }
            return null
        }
        return candidate
    }

    private fun indexFile(path: Path): Path {
        val directory =
            options.indexDir?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?: PlatformCache.defaultDirectory().resolve("dumps")
        return directory.resolve("dump-${sha1(path.toString()).take(12)}.db")
    }

    private fun sha1(value: String): String =
        MessageDigest
            .getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
