/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.infrastructure.configuration

import com.alexqsoft.infrastructure.persistent.cache.PlatformCache
import com.alexqsoft.infrastructure.persistent.storage.PlatformContextLoader
import com.alexqsoft.infrastructure.persistent.storage.PlatformContextStorage
import com.alexqsoft.infrastructure.platform.PlatformLocator
import com.alexqsoft.infrastructure.platform.PlatformOptions
import com.alexqsoft.infrastructure.search.CatalogProvider
import com.alexqsoft.presentation.formatters.JsonResponseService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.nio.file.Path

/**
 * Сборка серверных компонентов справки: настройки, поиск платформы, кэш, хранилище и каталог.
 */
@Configuration
class PlatformContextConfiguration {
    @Bean
    fun platformOptions(environment: Environment): PlatformOptions =
        PlatformOptions(
            path = environment.getProperty("platform.context.path")?.takeIf { it.isNotBlank() },
            roots =
                environment
                    .getProperty("platform.context.roots", "")
                    .split(',', ';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
            version = environment.getProperty("platform.context.version")?.takeIf { it.isNotBlank() },
            locale = environment.getProperty("platform.context.locale", "ru"),
            cacheDir = environment.getProperty("platform.context.cache-dir")?.takeIf { it.isNotBlank() },
            cacheEnabled = environment.getProperty("platform.context.cache-enabled", Boolean::class.java, true),
            refresh = environment.getProperty("platform.context.refresh", Boolean::class.java, false),
            warmup = environment.getProperty("platform.context.warmup", Boolean::class.java, true),
            responseLimit = environment.getProperty("platform.context.response-limit", Int::class.java, 24_000),
            waitTimeoutMs = environment.getProperty("platform.context.wait-timeout-ms", Long::class.java, 25_000),
        )

    @Bean
    fun platformLocator(): PlatformLocator = PlatformLocator()

    @Bean
    fun platformContextLoader(
        locator: PlatformLocator,
        options: PlatformOptions,
    ): PlatformContextLoader {
        val cache =
            if (options.cacheEnabled) {
                val directory =
                    options.cacheDir?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                        ?: PlatformCache.defaultDirectory()
                PlatformCache(directory)
            } else {
                null
            }
        return PlatformContextLoader(locator, cache)
    }

    @Bean
    fun platformContextStorage(
        loader: PlatformContextLoader,
        options: PlatformOptions,
    ): PlatformContextStorage = PlatformContextStorage(loader, options)

    @Bean
    fun catalogProvider(storage: PlatformContextStorage): CatalogProvider = CatalogProvider(storage)

    @Bean
    fun jsonResponseService(options: PlatformOptions): JsonResponseService = JsonResponseService(options.responseLimit)
}
