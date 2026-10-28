/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.presentation.cli

import ru.alkoleft.context.infrastructure.platform.PlatformOptions

/** Режим транспорта MCP. */
enum class ServerMode {
    STDIO,
    SSE,
}

/**
 * Разобранные аргументы командной строки сервера.
 *
 * @property benchmark запустить замеры и выйти, не поднимая MCP-сервер
 */
data class ServerOptions(
    val platformPath: String? = null,
    val platformRoots: List<String> = emptyList(),
    val version: String? = null,
    val locale: String? = null,
    val cacheDir: String? = null,
    val cacheEnabled: Boolean = true,
    val refresh: Boolean = false,
    val warmup: Boolean = true,
    val dumpPath: String? = null,
    val dumpIndexDir: String? = null,
    val mode: ServerMode = ServerMode.STDIO,
    val port: Int? = null,
    val verbose: Boolean = false,
    val benchmark: Boolean = false,
) {
    /** Настройки платформы для бенчмарка и для Spring-конфигурации. */
    fun toPlatformOptions(): PlatformOptions =
        PlatformOptions(
            path = platformPath,
            roots = platformRoots,
            version = version,
            locale = locale ?: "ru",
            cacheDir = cacheDir,
            cacheEnabled = cacheEnabled,
            refresh = refresh,
            warmup = warmup,
        )

    /** Перенести настройки в системные свойства — их читает Spring-конфигурация. */
    fun applyToSystemProperties() {
        platformPath?.let { System.setProperty("platform.context.path", it) }
        if (platformRoots.isNotEmpty()) {
            System.setProperty("platform.context.roots", platformRoots.joinToString(","))
        }
        version?.let { System.setProperty("platform.context.version", it) }
        locale?.let { System.setProperty("platform.context.locale", it) }
        cacheDir?.let { System.setProperty("platform.context.cache-dir", it) }
        System.setProperty("platform.context.cache-enabled", cacheEnabled.toString())
        System.setProperty("platform.context.refresh", refresh.toString())
        System.setProperty("platform.context.warmup", warmup.toString())
        dumpPath?.let { System.setProperty("dump.path", it) }
        dumpIndexDir?.let { System.setProperty("dump.index-dir", it) }
        if (verbose) System.setProperty("logging.level.root", "DEBUG")
    }

    companion object {
        val USAGE =
            """
            mcp-bsl-context — MCP-сервер: справка по API платформы 1С и разбор выгрузки конфигурации.

            Использование:
              java -jar mcp-bsl-context.jar [--platform-path <путь>] [--dump <путь>] [ключи]

            Платформа:
              -p, --platform-path <путь>  каталог установки 1С, каталог версии или файл shcntx_*.hbk;
                                          по умолчанию ищется в стандартных каталогах ОС
              --platform-root <путь>      дополнительный корень поиска установленных платформ
                                          (ключ можно повторять)
              --version <версия>          нужная версия, точная или префикс: 8.3.27
              --locale <код>              язык справки: ru (по умолчанию) или en

            Разбор и кэш:
              --cache-dir <путь>          каталог кэша разбора (по умолчанию — каталог пользователя)
              --no-cache                  не читать и не писать кэш
              --refresh                   пересобрать кэш из .hbk
              --lazy                      начать разбор при первом запросе, а не при старте

            Выгрузка конфигурации:
              --dump <путь>               каталог выгрузки «Выгрузить конфигурацию в файлы»
              --dump-index-dir <путь>     каталог SQLite-индексов выгрузки
                                          (по умолчанию — кэш пользователя)

            Запуск:
              -m, --mode <stdio|sse>      транспорт (по умолчанию stdio)
              --port <порт>               порт SSE-сервера (по умолчанию 8080)
              -v, --verbose               отладочное логирование
              --benchmark                 замерить разбор, кэш и поиск, затем выйти
              -h, --help                  показать эту справку

            Диагностика пишется в файл лога; stdout принадлежит протоколу MCP.
            """.trimIndent()

        /** Разобрать аргументы; при ошибке вернуть её текст, при `--help` — флаг справки. */
        fun parse(args: Array<String>): ParseResult {
            var platformPath: String? = null
            val roots = mutableListOf<String>()
            var version: String? = null
            var locale: String? = null
            var cacheDir: String? = null
            var cacheEnabled = true
            var refresh = false
            var warmup = true
            var dumpPath: String? = null
            var dumpIndexDir: String? = null
            var mode = ServerMode.STDIO
            var port: Int? = null
            var verbose = false
            var benchmark = false

            var index = 0
            while (index < args.size) {
                val (name, inline) = split(args[index])

                fun value(): String? = inline ?: args.getOrNull(++index)?.also { if (it.startsWith("--")) return null }

                when (name) {
                    "-h", "--help", "help" -> return ParseResult(help = true)
                    "-p", "--platform-path" -> {
                        platformPath = value() ?: return ParseResult(error = "Ключ $name ждёт путь к платформе")
                    }

                    "--platform-root" -> {
                        roots += value() ?: return ParseResult(error = "Ключ $name ждёт путь к каталогу платформ")
                    }

                    "--version" -> {
                        version = value() ?: return ParseResult(error = "Ключ $name ждёт версию платформы")
                    }

                    "--locale" -> {
                        locale = value() ?: return ParseResult(error = "Ключ $name ждёт код языка: ru или en")
                    }

                    "--cache-dir" -> {
                        cacheDir = value() ?: return ParseResult(error = "Ключ $name ждёт путь к каталогу кэша")
                    }

                    "--no-cache" -> cacheEnabled = false
                    "--refresh" -> refresh = true
                    "--lazy" -> warmup = false

                    "--dump" -> {
                        dumpPath = value() ?: return ParseResult(error = "Ключ $name ждёт путь к выгрузке")
                    }

                    "--dump-index-dir" -> {
                        dumpIndexDir = value() ?: return ParseResult(error = "Ключ $name ждёт путь к каталогу индексов")
                    }

                    "--benchmark" -> benchmark = true
                    "-v", "--verbose" -> verbose = true
                    "-m", "--mode" -> {
                        val raw = value() ?: return ParseResult(error = "Ключ $name ждёт режим: stdio или sse")
                        mode =
                            when (raw.lowercase()) {
                                "stdio" -> ServerMode.STDIO
                                "sse" -> ServerMode.SSE
                                else -> return ParseResult(error = "Неизвестный режим «$raw»: допустимо stdio или sse")
                            }
                    }

                    "--port" -> {
                        val raw = value() ?: return ParseResult(error = "Ключ $name ждёт номер порта")
                        port = raw.toIntOrNull()?.takeIf { it in 1..65535 }
                            ?: return ParseResult(error = "Неверный порт «$raw»")
                    }

                    else -> return ParseResult(error = "Неизвестный ключ «${args[index]}»")
                }
                index++
            }

            return ParseResult(
                options =
                    ServerOptions(
                        platformPath = platformPath,
                        platformRoots = roots,
                        version = version,
                        locale = locale,
                        cacheDir = cacheDir,
                        cacheEnabled = cacheEnabled,
                        refresh = refresh,
                        warmup = warmup,
                        dumpPath = dumpPath,
                        dumpIndexDir = dumpIndexDir,
                        mode = mode,
                        port = port,
                        verbose = verbose,
                        benchmark = benchmark,
                    ),
            )
        }

        private fun split(argument: String): Pair<String, String?> {
            val separator = argument.indexOf('=')
            return if (separator < 0) {
                argument to null
            } else {
                argument.substring(0, separator) to argument.substring(separator + 1)
            }
        }
    }

    /** Результат разбора: либо настройки, либо ошибка, либо запрос справки. */
    data class ParseResult(
        val options: ServerOptions? = null,
        val error: String? = null,
        val help: Boolean = false,
    )
}
