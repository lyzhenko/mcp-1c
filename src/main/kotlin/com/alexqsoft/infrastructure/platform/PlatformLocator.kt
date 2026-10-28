/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.infrastructure.platform

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

private val logger = KotlinLogging.logger {}

/**
 * Установленная платформа 1С: каталог версии и найденные в нём справочные файлы.
 *
 * @property locales язык → файл `shcntx_<язык>.hbk`
 */
data class PlatformInstallation(
    val version: String,
    val path: Path,
    val locales: Map<String, Path>,
)

/**
 * Выбранная для работы платформа.
 *
 * @property localeFallback язык пришлось подменить (запрошенный файл не найден)
 */
data class LocatedPlatform(
    val version: String,
    val locale: String,
    val hbk: Path,
    val localeFallback: Boolean = false,
) {
    val displayPath: String
        get() = hbk.toString()
}

/**
 * Поиск установленных платформ и выбор нужной версии и языка.
 *
 * Умеет три сценария: явный путь (каталог установки, каталог версии или сам `.hbk`), поиск
 * по стандартным каталогам ОС и выбор самой свежей версии. Результат поиска кэшируется на процесс.
 */
class PlatformLocator {
    private val scanLock = Any()
    private var scannedRoots: List<String>? = null
    private var scanned: List<PlatformInstallation> = emptyList()

    /** Все платформы, найденные в заданных и стандартных каталогах, от свежей к старой. */
    fun installations(options: PlatformOptions): List<PlatformInstallation> {
        val roots = roots(options)
        synchronized(scanLock) {
            if (scannedRoots == roots) return scanned
            val found = roots.flatMap { scanRoot(it) }.distinctBy { it.path }
            scanned = found.sortedWith(Comparator { left, right -> compareVersions(right.version, left.version) })
            scannedRoots = roots
            logger.info {
                "Найдено платформ: ${scanned.size}; " +
                    scanned.joinToString(", ") { "${it.version} (${it.locales.keys})" }
            }
            return scanned
        }
    }

    /**
     * Выбирает платформу под настройки: явный путь важнее стандартных каталогов, версия —
     * точная или префикс; язык — с откатом на русский, если запрошенного файла нет.
     */
    fun locate(options: PlatformOptions): LocatedPlatform? {
        val requestedLocale = options.normalizedLocale()
        options.path?.takeIf { it.isNotBlank() }?.let { explicit ->
            return locateExplicit(Path.of(explicit), options, requestedLocale)
        }

        val installations = installations(options)
        if (installations.isEmpty()) return null

        val versionFilter = options.normalizedVersion()
        val byVersion =
            installations.filter {
                versionFilter == null || it.version == versionFilter || it.version.startsWith("$versionFilter.")
            }
        if (byVersion.isEmpty()) {
            logger.warn { "Версия '$versionFilter' не найдена среди установленных: ${installations.map { it.version }}" }
            return null
        }

        val exact = byVersion.firstOrNull { it.locales.containsKey(requestedLocale) }
        if (exact != null) {
            return LocatedPlatform(exact.version, requestedLocale, exact.locales.getValue(requestedLocale))
        }

        val fallback = byVersion.firstOrNull { it.locales.containsKey(DEFAULT_LOCALE) } ?: byVersion.first()
        val fallbackLocale = fallback.locales.keys.firstOrNull() ?: return null
        logger.warn { "Справка на языке '$requestedLocale' не найдена, используется '$fallbackLocale'" }
        return LocatedPlatform(
            version = fallback.version,
            locale = fallbackLocale,
            hbk = fallback.locales.getValue(fallbackLocale),
            localeFallback = fallbackLocale != requestedLocale,
        )
    }

    private fun locateExplicit(
        path: Path,
        options: PlatformOptions,
        requestedLocale: String,
    ): LocatedPlatform? {
        if (Files.isRegularFile(path)) {
            if (!isSyntaxAssistantFile(path)) {
                logger.warn { "Файл '$path' не похож на shcntx_*.hbk" }
                return null
            }
            return LocatedPlatform(versionOf(path), localeOf(path) ?: requestedLocale, path)
        }

        if (!Files.isDirectory(path)) {
            logger.warn { "Путь '$path' не существует" }
            return null
        }

        val found = scanDirectory(path)
        if (found.isEmpty()) return null

        val installations = found.distinctBy { it.path }
        val versionFilter = options.normalizedVersion()
        val byVersion =
            installations
                .filter { versionFilter == null || it.version.startsWith(versionFilter) }
                .sortedWith(Comparator { left, right -> compareVersions(right.version, left.version) })
        if (byVersion.isEmpty()) return null

        byVersion.firstOrNull { it.locales.containsKey(requestedLocale) }?.let {
            return LocatedPlatform(it.version, requestedLocale, it.locales.getValue(requestedLocale))
        }
        val fallback = byVersion.firstOrNull { it.locales.containsKey(DEFAULT_LOCALE) } ?: byVersion.first()
        val fallbackLocale = fallback.locales.keys.firstOrNull() ?: return null
        return LocatedPlatform(
            version = fallback.version,
            locale = fallbackLocale,
            hbk = fallback.locales.getValue(fallbackLocale),
            localeFallback = fallbackLocale != requestedLocale,
        )
    }

    /**
     * Корни поиска: явно заданные (ключи, путь, переменные окружения) важнее стандартных.
     * Если явных нет — смотрим стандартные каталоги ОС.
     */
    private fun roots(options: PlatformOptions): List<String> {
        val explicitRoots =
            options.roots
                .flatMap { it.split(',', ';') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val explicitPath =
            options.path
                ?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it) }
                ?.takeIf { Files.isDirectory(it) }
                ?.toString()
                .orEmpty()
        val explicit = explicitRoots + listOfNotNull(explicitPath) + environmentRoots()
        val chosen = if (explicit.isNotEmpty()) explicit else defaultRoots()
        return chosen
            .filter { it.isNotEmpty() && Files.isDirectory(Path.of(it)) }
            .distinct()
    }

    private fun environmentRoots(): List<String> {
        val roots = mutableListOf<String>()
        System.getenv("PLATFORM_ROOT")?.split(',', ';')?.forEach { roots += it.trim() }
        System.getenv("PLATFORM_CONTEXT_PATH")?.let { roots += it.trim() }
        return roots.filter { it.isNotEmpty() }
    }

    private fun defaultRoots(): List<String> {
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        return when {
            os.contains("win") ->
                listOf(
                    "C:\\Program Files\\1cv8",
                    "C:\\Program Files (x86)\\1cv8",
                )

            os.contains("mac") -> listOf("/opt/1cv8")
            else -> listOf("/opt/1cv8/x86_64", "/opt/1cv8", "/opt/1C/v8.3/x86_64")
        }
    }

    private fun scanRoot(root: String): List<PlatformInstallation> = scanDirectory(Path.of(root))

    private fun scanDirectory(directory: Path): List<PlatformInstallation> {
        val files =
            runCatching {
                Files.walk(directory, MAX_DEPTH).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) }
                        .filter { isSyntaxAssistantFile(it) }
                        .toList()
                }
            }.getOrElse { error ->
                logger.warn { "Не удалось просмотреть '$directory': ${error.message}" }
                emptyList()
            }
        return files
            .groupBy { it.parent }
            .map { (parent, hbkFiles) ->
                PlatformInstallation(
                    version = versionOf(parent),
                    path = parent,
                    locales = hbkFiles.associate { (localeOf(it) ?: DEFAULT_LOCALE) to it },
                )
            }
    }

    private fun isSyntaxAssistantFile(path: Path): Boolean = SYNTAX_ASSISTANT.matches(path.fileName?.toString().orEmpty())

    private fun localeOf(path: Path): String? =
        LOCALE_IN_NAME
            .matchEntire(path.fileName?.toString().orEmpty())
            ?.groupValues
            ?.get(1)
            ?.lowercase(Locale.ROOT)

    /** Версия — последний сегмент пути вида `8.3.27.2214`; иначе `unknown`. */
    private fun versionOf(path: Path): String {
        var version = "unknown"
        for (segment in path) {
            if (VERSION.matches(segment.toString())) version = segment.toString()
        }
        return version
    }

    companion object {
        const val DEFAULT_LOCALE = "ru"
        private const val MAX_DEPTH = 5
        private val SYNTAX_ASSISTANT = Regex("""shcntx_.*\.hbk""", RegexOption.IGNORE_CASE)
        private val LOCALE_IN_NAME = Regex("""shcntx_(.+)\.hbk""", RegexOption.IGNORE_CASE)
        private val VERSION = Regex("""\d+\.\d+\.\d+(\.\d+)?""")

        /** Ключ сравнения версий: `8.3.27.2214` → числовой список. */
        fun versionKey(version: String): List<Int> {
            val parts = version.split('.').map { it.toIntOrNull() ?: 0 }
            return parts + List((4 - parts.size).coerceAtLeast(0)) { 0 }
        }

        /** Сравнение версий по числовым сегментам: `8.3.27` < `8.3.27.2214`. */
        fun compareVersions(
            left: String,
            right: String,
        ): Int {
            val leftKey = versionKey(left)
            val rightKey = versionKey(right)
            val length = maxOf(leftKey.size, rightKey.size)
            for (index in 0 until length) {
                val difference = leftKey.getOrElse(index) { 0 }.compareTo(rightKey.getOrElse(index) { 0 })
                if (difference != 0) return difference
            }
            return 0
        }
    }
}
