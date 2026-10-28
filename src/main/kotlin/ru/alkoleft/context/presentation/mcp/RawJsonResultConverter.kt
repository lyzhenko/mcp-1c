/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.presentation.mcp

import org.springframework.ai.tool.execution.ToolCallResultConverter
import org.springframework.ai.util.json.JsonParser
import java.lang.reflect.Type

/**
 * Результат инструмента уже является JSON-строкой, и повторно сериализовать его нельзя.
 *
 * По умолчанию Spring AI прогоняет результат через Jackson, поэтому готовая JSON-строка
 * превращается в экранированную строку внутри строки: агент видит `"{\\\"found\\\":...}"`
 * вместо объекта. Конвертер отдаёт строку как есть, а остальные значения сериализует.
 */
class RawJsonResultConverter : ToolCallResultConverter {
    override fun convert(
        result: Any?,
        returnType: Type?,
    ): String =
        when (result) {
            null -> "null"
            is String -> result
            else -> JsonParser.toJson(result)
        }
}
