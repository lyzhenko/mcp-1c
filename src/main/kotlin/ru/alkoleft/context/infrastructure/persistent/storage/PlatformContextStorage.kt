/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.infrastructure.persistent.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import ru.alkoleft.context.infrastructure.platform.PlatformOptions

private val logger = KotlinLogging.logger {}

/**
 * Состояние модели платформы на текущий момент.
 *
 * @property elapsedMs сколько уже длится разбор (или сколько длился в прошлый раз)
 */
data class PlatformContextSnapshot(
    val state: PlatformContextStorage.State,
    val result: PlatformLoadResult?,
    val error: String?,
    val startedAt: Long,
    val elapsedMs: Long,
)

/**
 * Хранилище модели платформы с фоновой загрузкой.
 *
 * Разбор `.hbk` — самая долгая операция сервера, поэтому он идёт в отдельном потоке:
 * `status` отвечает сразу, а инструменты ждут готовности не дольше [PlatformOptions.waitTimeoutMs]
 * и, если не дождались, честно отвечают «загружается», вместо того чтобы держать вызов минутами.
 * Загруженная модель неизменяема и публикуется через volatile-ссылку — блокировок на чтении нет.
 */
class PlatformContextStorage(
    private val loader: PlatformContextLoader,
    private val options: PlatformOptions,
) {
    enum class State {
        IDLE,
        LOADING,
        READY,
        FAILED,
    }

    private val monitor = Object()

    @Volatile
    private var state: State = State.IDLE

    @Volatile
    private var result: PlatformLoadResult? = null

    @Volatile
    private var failure: String? = null

    @Volatile
    private var startedAt: Long = 0

    /**
     * Разбор стартует при создании бина, а не по ApplicationRunner: MCP-сервер в stdio-режиме
     * занимает основной поток ещё до запуска runner'ов, и статус успевал показать `idle`.
     */
    @PostConstruct
    fun startWarmup() {
        if (options.warmup) warmup()
    }

    /** Начать разбор, если он ещё не начат. */
    fun warmup() {
        synchronized(monitor) {
            if (state != State.IDLE) return
            state = State.LOADING
            startedAt = System.currentTimeMillis()
            val thread =
                Thread({ runLoad() }, "platform-context-loader").apply {
                    isDaemon = true
                    start()
                }
            logger.info { "Разбор справки платформы запущен в фоне (поток ${thread.name})" }
        }
    }

    /**
     * Дождаться готовности модели.
     *
     * @return результат или null, если разбор ещё идёт либо завершился ошибкой
     */
    fun awaitReady(): PlatformLoadResult? {
        warmup()
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
            return if (state == State.READY) result else null
        }
    }

    /** Текущее состояние без ожидания. */
    fun snapshot(): PlatformContextSnapshot =
        synchronized(monitor) {
            val elapsed =
                when (state) {
                    State.LOADING -> System.currentTimeMillis() - startedAt
                    State.READY -> result?.loadMillis ?: 0
                    else -> 0
                }
            PlatformContextSnapshot(state, result, failure, startedAt, elapsed)
        }

    fun options(): PlatformOptions = options

    private fun runLoad() {
        try {
            val loaded = loader.load(options)
            synchronized(monitor) {
                result = loaded
                state = State.READY
                monitor.notifyAll()
            }
            val data = loaded.data
            logger.info {
                "Модель платформы готова: ${data.types.size} типов, ${data.methods.size} методов, " +
                    "${data.properties.size} свойств, ${data.enums.size} перечислений" +
                    if (loaded.fromCache) " (из кэша)" else ""
            }
        } catch (error: Throwable) {
            logger.error(error) { "Разбор справки платформы завершился ошибкой" }
            synchronized(monitor) {
                failure = error.message ?: error.toString()
                state = State.FAILED
                monitor.notifyAll()
            }
        }
    }
}
