/*
 * Copyright (c) 2024-2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.infrastructure.persistent.storage

import com.alexqsoft.business.entities.EnumDefinition
import com.alexqsoft.business.entities.MethodDefinition
import com.alexqsoft.business.entities.PlatformContextData
import com.alexqsoft.business.entities.PlatformSourceInfo
import com.alexqsoft.business.entities.PlatformTypeDefinition
import com.alexqsoft.business.entities.PropertyDefinition
import com.alexqsoft.exceptions.PlatformContextLoadException
import com.alexqsoft.infrastructure.hbk.PlatformContextReader
import com.alexqsoft.infrastructure.persistent.cache.PlatformCache
import com.alexqsoft.infrastructure.platform.LocatedPlatform
import com.alexqsoft.infrastructure.platform.PlatformLocator
import com.alexqsoft.infrastructure.platform.PlatformOptions
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Результат загрузки модели платформы.
 *
 * @property fromCache модель прочитана из кэша, а не разобрана заново
 * @property loadMillis сколько заняла загрузка (разбор или чтение кэша)
 * @property cacheFile файл кэша, если кэш включён
 */
data class PlatformLoadResult(
    val data: PlatformContextData,
    val located: LocatedPlatform,
    val fromCache: Boolean,
    val loadMillis: Long,
    val cacheFile: Path? = null,
)

/**
 * Загрузчик модели платформы: находит `.hbk`, проверяет кэш, при промахе разбирает справку.
 */
class PlatformContextLoader(
    private val locator: PlatformLocator,
    private val cache: PlatformCache?,
) {
    fun load(options: PlatformOptions): PlatformLoadResult {
        val located =
            locator.locate(options) ?: throw PlatformContextLoadException(
                buildString {
                    append("Не найдена установленная платформа 1С")
                    options.path?.takeIf { it.isNotBlank() }?.let { append(" по пути «$it»") }
                    append(". Укажите каталог установки ключом --platform-path ")
                    append("или положите справку shcntx_${options.normalizedLocale()}.hbk в стандартный каталог.")
                },
            )

        val hbk = located.hbk
        val stat =
            runCatching { Files.readAttributes(hbk, java.nio.file.attribute.BasicFileAttributes::class.java) }
                .getOrElse { throw PlatformContextLoadException("Не удалось прочитать файл справки «$hbk»: ${it.message}", it) }

        val key =
            PlatformCache.Key(
                source = hbk.toAbsolutePath().normalize(),
                size = stat.size(),
                modified = stat.lastModifiedTime().toMillis(),
                locale = located.locale,
                version = located.version,
            )
        val cacheFile = cache?.fileFor(key)

        if (cache != null && !options.refresh) {
            val cacheStarted = System.currentTimeMillis()
            val cached = cache.load(key)
            if (cached != null) {
                return PlatformLoadResult(
                    data = cached,
                    located = located,
                    fromCache = true,
                    loadMillis = System.currentTimeMillis() - cacheStarted,
                    cacheFile = cacheFile,
                )
            }
        }

        val started = System.currentTimeMillis()
        val data = parse(located, stat.size(), stat.lastModifiedTime().toMillis())
        val elapsed = System.currentTimeMillis() - started
        val complete = data.copy(info = data.info.copy(parseMillis = elapsed, loadedAt = System.currentTimeMillis()))
        if (cache != null) {
            cache.save(key, complete)
        }
        if (located.localeFallback) {
            logger.warn { "Справка на запрошенном языке не найдена, используется '${located.locale}'" }
        }
        logger.info {
            "Платформа ${located.version} (${located.locale}) разобрана за $elapsed мс: " +
                "типов ${complete.types.size}, методов ${complete.methods.size}, " +
                "свойств ${complete.properties.size}, перечислений ${complete.enums.size}"
        }
        return PlatformLoadResult(complete, located, fromCache = false, loadMillis = elapsed, cacheFile = cacheFile)
    }

    private fun parse(
        located: LocatedPlatform,
        sizeBytes: Long,
        modifiedAt: Long,
    ): PlatformContextData {
        val methods = mutableListOf<MethodDefinition>()
        val properties = mutableListOf<PropertyDefinition>()
        val types = mutableListOf<PlatformTypeDefinition>()
        val enums = mutableListOf<EnumDefinition>()

        try {
            PlatformContextReader().read(located.hbk) {
                methods += globalMethods().map { it.toEntity(isGlobal = true) }
                properties += globalProperties().map { it.toEntity() }
                types += types().map { it.toEntity() }
                enums += enums().map { it.toEntity() }
            }
        } catch (error: Exception) {
            logger.error(error) { "Ошибка разбора справки платформы" }
            throw PlatformContextLoadException(
                "Не удалось разобрать справку «${located.hbk}»: ${error.message}. " +
                    "Удалите кэш и повторите; если ошибка повторяется — пришлите файл справки.",
                error,
            )
        }

        return PlatformContextData(
            methods = methods,
            properties = properties,
            types = types,
            enums = enums,
            info =
                PlatformSourceInfo(
                    version = located.version,
                    locale = located.locale,
                    path = located.hbk.toString(),
                    sizeBytes = sizeBytes,
                    modifiedAt = modifiedAt,
                ),
        )
    }
}
