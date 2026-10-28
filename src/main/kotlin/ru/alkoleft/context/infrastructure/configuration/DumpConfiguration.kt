/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.infrastructure.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import ru.alkoleft.context.business.services.DumpCheckService
import ru.alkoleft.context.business.services.DumpConventionsService
import ru.alkoleft.context.business.services.DumpGraphService
import ru.alkoleft.context.business.services.DumpRightsService
import ru.alkoleft.context.business.services.DumpSimilarService
import ru.alkoleft.context.infrastructure.dump.DumpOptions
import ru.alkoleft.context.infrastructure.dump.DumpStorage
import ru.alkoleft.context.infrastructure.platform.PlatformOptions
import ru.alkoleft.context.infrastructure.search.CatalogProvider
import ru.alkoleft.context.presentation.formatters.DumpJsonService
import ru.alkoleft.context.presentation.formatters.JsonPager

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
