/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.infrastructure.configuration

import com.alexqsoft.business.services.DumpCheckService
import com.alexqsoft.business.services.DumpConventionsService
import com.alexqsoft.business.services.DumpGraphService
import com.alexqsoft.business.services.DumpRightsService
import com.alexqsoft.business.services.DumpSimilarService
import com.alexqsoft.infrastructure.dump.DumpOptions
import com.alexqsoft.infrastructure.dump.DumpStorage
import com.alexqsoft.infrastructure.platform.PlatformOptions
import com.alexqsoft.infrastructure.search.CatalogProvider
import com.alexqsoft.presentation.formatters.DumpJsonService
import com.alexqsoft.presentation.formatters.JsonPager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * Сборка индекса выгрузки конфигурации: настройки, хранилище и JSON-слой.
 */
@Configuration
class DumpConfiguration {
    @Bean
    fun dumpOptions(environment: Environment): DumpOptions =
        DumpOptions(
            path = environment.getProperty("dump.path")?.takeIf { it.isNotBlank() },
            indexDir = environment.getProperty("dump.index-dir")?.takeIf { it.isNotBlank() },
            warmup = environment.getProperty("dump.warmup", Boolean::class.java, true),
            waitTimeoutMs = environment.getProperty("dump.wait-timeout-ms", Long::class.java, 60_000),
            watchSeconds = environment.getProperty("dump.watch-seconds", Long::class.java, 0),
        )

    @Bean
    fun dumpStorage(options: DumpOptions): DumpStorage = DumpStorage(options)

    @Bean
    fun dumpCheckService(
        storage: DumpStorage,
        catalogProvider: CatalogProvider,
    ): DumpCheckService =
        DumpCheckService(storage) {
            runCatching { catalogProvider.catalog() }.getOrNull()
        }

    @Bean
    fun dumpGraphService(storage: DumpStorage): DumpGraphService = DumpGraphService(storage)

    @Bean
    fun dumpRightsService(storage: DumpStorage): DumpRightsService = DumpRightsService(storage)

    @Bean
    fun dumpSimilarService(storage: DumpStorage): DumpSimilarService = DumpSimilarService(storage)

    @Bean
    fun dumpConventionsService(storage: DumpStorage): DumpConventionsService = DumpConventionsService(storage)

    @Bean
    fun dumpJsonService(options: PlatformOptions): DumpJsonService = DumpJsonService(JsonPager(options.responseLimit))
}
