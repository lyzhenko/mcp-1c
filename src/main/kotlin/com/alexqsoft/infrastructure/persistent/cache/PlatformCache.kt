/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.infrastructure.persistent.cache

import com.alexqsoft.business.entities.PlatformContextData
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

private val logger = KotlinLogging.logger {}

/**
 * Дисковый кэш разобранной справки платформы.
 *
 * Разбор `shcntx_ru.hbk` (39 МБ справки) занимает заметное время, а сама модель от запуска
 * к запуску не меняется, пока не обновили платформу. Поэтому разобранная модель пишется в JSON
 * рядом с кэшем пользователя, а при старте читается за доли от времени разбора.
 *
 * Кэш сверяется с файлом справки по пути, размеру, времени изменения и языку: подменили платформу —
 * модель пересобирается. Схема кэша входит в ключ, поэтому смена модели инвалидирует старые файлы.
 */
class PlatformCache(
    val directory: Path,
    private val schema: Int = CACHE_SCHEMA,
) {
    private val mapper =
        jacksonObjectMapper().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

    /** Ключ кэша: файл справки и его состояние на диске. */
    data class Key(
        val source: Path,
        val size: Long,
        val modified: Long,
        val locale: String,
        val version: String,
    )

    /** Что лежит в файле кэша: ключ и сама модель. */
    data class Envelope(
        val schema: Int = CACHE_SCHEMA,
        val source: String = "",
        val size: Long = 0,
        val modified: Long = 0,
        val locale: String = "",
        val data: PlatformContextData = PlatformContextData(),
    )

    /** Прочитать модель, если кэш есть и соответствует файлу справки. */
    fun load(key: Key): PlatformContextData? {
        val file = fileFor(key)
        if (!Files.isRegularFile(file)) return null
        val started = System.currentTimeMillis()
        val envelope =
            runCatching { mapper.readValue(file.toFile(), Envelope::class.java) }
                .getOrElse { error ->
                    logger.warn { "Не удалось прочитать кэш '$file': ${error.message}" }
                    return null
                }
        if (envelope.schema != schema ||
            envelope.source != key.source.toString() ||
            envelope.size != key.size ||
            envelope.modified != key.modified ||
            envelope.locale != key.locale
        ) {
            logger.info { "Кэш '$file' устарел: справка изменилась" }
            return null
        }
        logger.info {
            "Справка прочитана из кэша за ${System.currentTimeMillis() - started} мс: $file " +
                "(${humanSize(Files.size(file))})"
        }
        return envelope.data
    }

    /** Записать модель; запись атомарная, чтобы оборванный процесс не оставил половину файла. */
    fun save(
        key: Key,
        data: PlatformContextData,
    ) {
        val file = fileFor(key)
        runCatching {
            Files.createDirectories(directory)
            val temp = Files.createTempFile(directory, file.fileName.toString(), ".tmp")
            val started = System.currentTimeMillis()
            mapper.writeValue(
                temp.toFile(),
                Envelope(
                    schema = schema,
                    source = key.source.toString(),
                    size = key.size,
                    modified = key.modified,
                    locale = key.locale,
                    data = data,
                ),
            )
            move(temp, file)
            logger.info {
                "Разбор сохранён в кэш за ${System.currentTimeMillis() - started} мс: $file " +
                    "(${humanSize(Files.size(file))})"
            }
        }.onFailure { error ->
            logger.warn { "Не удалось сохранить кэш '$file': ${error.message}" }
        }
    }

    /** Файл кэша для ключа; имя зависит от языка и пути к справке. */
    fun fileFor(key: Key): Path {
        val hash = sha1(key.source.toString()).take(HASH_LENGTH)
        return directory.resolve("platform-${key.locale}-$hash.json")
    }

    private fun move(
        temp: Path,
        target: Path,
    ) {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * Версия схемы кэша: поднимать при изменении модели, маппинга или правил разбора.
         * v2 — исправлено повторное использование сигнатур между методами типа.
         */
        const val CACHE_SCHEMA = 2

        private const val HASH_LENGTH = 12

        /** Каталог кэша по умолчанию: `%LOCALAPPDATA%` на Windows, `~/.cache` на остальных. */
        fun defaultDirectory(): Path {
            val os = System.getProperty("os.name").lowercase(Locale.ROOT)
            val base =
                when {
                    os.contains("win") ->
                        System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                            ?: Path.of(System.getProperty("user.home"), "AppData", "Local")

                    os.contains("mac") -> Path.of(System.getProperty("user.home"), "Library", "Caches")
                    else -> Path.of(System.getProperty("user.home"), ".cache")
                }
            return base.resolve("mcp-bsl-context")
        }

        fun humanSize(bytes: Long): String =
            when {
                bytes >= 1 shl 30 -> "%.2f ГБ".format(bytes.toDouble() / (1 shl 30))
                bytes >= 1 shl 20 -> "%.1f МБ".format(bytes.toDouble() / (1 shl 20))
                bytes >= 1 shl 10 -> "%.0f КБ".format(bytes.toDouble() / (1 shl 10))
                else -> "$bytes Б"
            }
    }
}
