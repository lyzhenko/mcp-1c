/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.presentation.mcp

/**
 * Каталог инструментов сервера: используется в `status`, чтобы клиент сразу видел,
 * что умеет текущая сборка, и не путал «инструмент не найден» с устаревшим сервером.
 */
object McpToolCatalog {
    const val SERVER_NAME = "mcp-bsl-context"

    val TOOLS: List<String> =
        listOf(
            "search",
            "info",
            "getMember",
            "getMembers",
            "getConstructors",
            "dumpOpen",
            "dumpSearch",
            "dumpMetadata",
            "dumpCode",
            "dumpGrep",
            "dumpEntrypoints",
            "dumpCheck",
            "dumpNode",
            "dumpNeighbors",
            "dumpTypes",
            "dumpRights",
            "dumpSimilar",
            "dumpConventions",
            "dumpReload",
            "status",
        )
}
