/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.infrastructure.search

import com.alexqsoft.business.entities.PlatformContextData
import com.alexqsoft.exceptions.PlatformNotReadyException
import com.alexqsoft.infrastructure.persistent.storage.PlatformContextStorage
import com.alexqsoft.infrastructure.persistent.storage.PlatformLoadResult
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Доступ к каталогу платформы с ленивой сборкой.
 *
 * Сборка каталога занимает заметное время, поэтому он строится один раз на загруженную модель
 * и переиспользуется всеми запросами. При перезагрузке модели (другой `.hbk`) каталог
 * перестраивается сам — сравнение идёт по ссылке на результат загрузки.
 */
class CatalogProvider(
    private val storage: PlatformContextStorage,
) {
    private val lock = Any()

    @Volatile
    private var cachedFor: PlatformLoadResult? = null

    @Volatile
    private var cachedCatalog: PlatformCatalog? = null

    /** Каталог готовой модели; если модель ещё грузится — исключение [PlatformNotReadyException]. */
    fun catalog(): PlatformCatalog {
        val snapshot = storage.snapshot()
        val loaded =
            storage.awaitReady() ?: throw PlatformNotReadyException(
                when (snapshot.state) {
                    PlatformContextStorage.State.LOADING ->
                        "Справка платформы ещё загружается (${snapshot.elapsedMs / 1000} с). " +
                            "Повторите запрос через несколько секунд — дальше ответы будут мгновенными."

                    PlatformContextStorage.State.FAILED ->
                        "Справка платформы не загрузилась: ${snapshot.error ?: "неизвестная ошибка"}"

                    else -> "Справка платформы не загружена."
                },
            )

        cachedCatalog?.let { catalog -> if (cachedFor === loaded) return catalog }

        synchronized(lock) {
            cachedCatalog?.let { catalog -> if (cachedFor === loaded) return catalog }
            val started = System.currentTimeMillis()
            val built = PlatformCatalog(loaded.data)
            cachedFor = loaded
            cachedCatalog = built
            logger.info { "Каталог платформы построен за ${System.currentTimeMillis() - started} мс" }
            return built
        }
    }

    /** Загруженные данные без сборки каталога (для `status`). */
    fun dataOrNull(): PlatformContextData? = storage.snapshot().result?.data
}
