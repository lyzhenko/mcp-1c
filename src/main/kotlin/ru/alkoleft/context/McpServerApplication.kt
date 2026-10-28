/*
 * Copyright (c) 2024-2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import ru.alkoleft.context.presentation.benchmark.PlatformBenchmark
import ru.alkoleft.context.presentation.cli.ServerMode
import ru.alkoleft.context.presentation.cli.ServerOptions
import kotlin.system.exitProcess

@SpringBootApplication
class McpServerApplication

fun main(args: Array<String>) {
    val parsed = ServerOptions.parse(args)
    if (parsed.help) {
        println(ServerOptions.USAGE)
        return
    }
    parsed.error?.let { message ->
        System.err.println("Ошибка аргументов: $message")
        System.err.println(ServerOptions.USAGE)
        exitProcess(2)
    }

    val options = parsed.options ?: return
    options.applyToSystemProperties()

    if (options.benchmark) {
        PlatformBenchmark(options.toPlatformOptions()).run()
        return
    }

    val activeProfiles = mutableListOf<String>()
    when (options.mode) {
        ServerMode.SSE -> {
            activeProfiles += "sse"
            options.port?.let { System.setProperty("server.port", it.toString()) }
        }

        ServerMode.STDIO -> activeProfiles += "stdio"
    }

    runApplication<McpServerApplication>(*args) {
        setDefaultProperties(mapOf("spring.profiles.active" to activeProfiles.joinToString(",")))
    }
}
