/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.business.services

import org.springframework.stereotype.Service
import ru.alkoleft.context.business.entities.PlatformContextData
import ru.alkoleft.context.infrastructure.persistent.cache.PlatformCache
import ru.alkoleft.context.infrastructure.persistent.storage.PlatformContextStorage
import ru.alkoleft.context.infrastructure.platform.PlatformInstallation
import ru.alkoleft.context.infrastructure.platform.PlatformLocator
import ru.alkoleft.context.infrastructure.platform.PlatformOptions
import java.nio.file.Files
import java.nio.file.Path

/**
 * Снимок состояния сервера для инструмента `status`.
 *
 * @property state `idle`, `loading`, `ready` или `failed`
 * @property elapsedMs сколько длится (или длился) разбор
 * @property available установленные платформы, найденные в стандартных каталогах
 */
data class PlatformStatus(
    val state: String,
    val error: String?,
    val elapsedMs: Long,
    val data: PlatformContextData?,
    val fromCache: Boolean,
    val loadMillis: Long,
    val cacheFile: Path?,
    val cacheDir: Path,
    val cacheEnabled: Boolean,
    val available: List<PlatformInstallation>,
    val options: PlatformOptions,
)

/**
 * Собирает состояние сервера для `status`: что загружено, откуда, сколько заняло и что ещё
 * установлено на машине. Инструмент не ждёт разбора — отвечает тем, что есть сейчас.
 */
@Service
class PlatformStatusService(
    private val storage: PlatformContextStorage,
    private val locator: PlatformLocator,
    private val options: PlatformOptions,
) {
    fun status(): PlatformStatus {
        val snapshot = storage.snapshot()
        val cacheDir =
            options.cacheDir?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?: PlatformCache.defaultDirectory()
        val available =
            runCatching { locator.installations(options) }
                .getOrDefault(emptyList())

        return PlatformStatus(
            state = snapshot.state.name.lowercase(),
            error = snapshot.error,
            elapsedMs = snapshot.elapsedMs,
            data = snapshot.result?.data,
            fromCache = snapshot.result?.fromCache ?: false,
            loadMillis = snapshot.result?.loadMillis ?: 0,
            cacheFile = snapshot.result?.cacheFile?.takeIf { Files.isRegularFile(it) },
            cacheDir = cacheDir,
            cacheEnabled = options.cacheEnabled,
            available = available,
            options = options,
        )
    }
}
