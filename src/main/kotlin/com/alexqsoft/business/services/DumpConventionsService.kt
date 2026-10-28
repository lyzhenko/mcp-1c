/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.business.services

import com.alexqsoft.exceptions.DefinitionNotFoundException
import com.alexqsoft.exceptions.InvalidSearchQueryException
import com.alexqsoft.infrastructure.dump.DumpStorage
import com.alexqsoft.infrastructure.dump.DumpTextReader
import java.util.Locale

/** Нарушение конвенции. */
data class ConventionFinding(
    val code: String,
    val severity: String,
    val line: Int,
    val message: String,
    val hint: String,
)

data class ConventionsResult(
    val path: String,
    val lines: Int,
    val findings: List<ConventionFinding>,
    val catalog: List<ConventionInfo>,
)

data class ConventionInfo(
    val code: String,
    val title: String,
    val severity: String,
)

/**
 * Конвенции кода 1С: простые эвристики по строкам модуля.
 *
 * Это не линтер платформы, а подсказки: «Сообщить» в серверном коде, запись/запрос в цикле,
 * пустая «Попытка», «ВызватьИсключение» без текста, TODO в комментариях.
 */
class DumpConventionsService(
    private val storage: DumpStorage,
) {
    fun conventions(
        path: String? = null,
        id: Long? = null,
        text: String? = null,
    ): ConventionsResult {
        val index =
            if (storage.currentPath() == null) {
                throw InvalidSearchQueryException("выгрузка не открыта")
            } else {
                storage.awaitReady() ?: throw InvalidSearchQueryException("индекс выгрузки ещё не готов")
            }
        val normalized = path?.replace('\\', '/')?.trimStart('/')
        val source =
            when {
                text != null -> text to (normalized ?: "draft.bsl")
                id != null -> {
                    val module = index.module(id) ?: throw DefinitionNotFoundException("модуль #$id")
                    val file = storage.file(module.path) ?: throw DefinitionNotFoundException("файл «${module.path}»")
                    DumpTextReader.read(file) to module.path
                }
                !normalized.isNullOrBlank() -> {
                    val module = index.moduleByPath(normalized) ?: throw DefinitionNotFoundException("модуль «$normalized»")
                    val file = storage.file(module.path) ?: throw DefinitionNotFoundException("файл «${module.path}»")
                    DumpTextReader.read(file) to module.path
                }
                else -> throw InvalidSearchQueryException("укажите path, id или text")
            }

        val lines = source.first.split('\n')
        val findings = mutableListOf<ConventionFinding>()
        val client = lines.any { it.trim().startsWith("&НаКлиенте") }
        var loopDepth = 0
        var inTry = false
        var hasExcept = false

        for ((indexLine, raw) in lines.withIndex()) {
            val line = raw.trim()
            val lower = line.lowercase(Locale.ROOT)
            val lineNumber = indexLine + 1

            if (lower.startsWith("для ") || lower.startsWith("для каждого") || lower.startsWith("пока ")) loopDepth++
            if (lower.startsWith("конеццикла")) loopDepth = (loopDepth - 1).coerceAtLeast(0)
            if (lower.startsWith("попытка")) {
                inTry = true
                hasExcept = false
            }
            if (lower.startsWith("исключение")) hasExcept = true
            if (lower.startsWith("конецпопытки")) {
                if (inTry && !hasExcept) {
                    findings +=
                        ConventionFinding(
                            code = "empty_except",
                            severity = "warning",
                            line = lineNumber,
                            message = "«Попытка» без «Исключение»",
                            hint = "Добавьте обработку исключения или уберите «Попытка».",
                        )
                }
                inTry = false
            }

            if (!client && lower.contains("сообщить(")) {
                findings +=
                    ConventionFinding(
                        code = "server_message",
                        severity = "warning",
                        line = lineNumber,
                        message = "«Сообщить» в серверном модуле",
                        hint = "На сервере используйте «СообщениеПользователю».",
                    )
            }
            if (loopDepth > 0 && lower.contains("новый запрос")) {
                findings +=
                    ConventionFinding(
                        code = "query_in_loop",
                        severity = "warning",
                        line = lineNumber,
                        message = "Запрос создаётся в цикле",
                        hint = "Вынесите запрос из цикла и меняйте параметры.",
                    )
            }
            if (loopDepth > 0 && (lower.contains(".записать(") || lower.endsWith(".записать();"))) {
                findings +=
                    ConventionFinding(
                        code = "write_in_loop",
                        severity = "warning",
                        line = lineNumber,
                        message = "Запись объекта в цикле",
                        hint = "Записывайте пачкой или вне цикла.",
                    )
            }
            if (lower.contains("todo") || lower.contains("fixme")) {
                findings +=
                    ConventionFinding(
                        code = "todo",
                        severity = "info",
                        line = lineNumber,
                        message = "Комментарий TODO/FIXME",
                        hint = "Закройте задачу или оформите её в трекере.",
                    )
            }
            if (lower.startsWith("вызватьисключение") && !lower.contains("(")) {
                findings +=
                    ConventionFinding(
                        code = "raise_without_text",
                        severity = "warning",
                        line = lineNumber,
                        message = "«ВызватьИсключение» без текста",
                        hint = "Передайте описание ошибки.",
                    )
            }
        }

        return ConventionsResult(
            path = source.second,
            lines = lines.size,
            findings = findings.take(200),
            catalog =
                listOf(
                    ConventionInfo("server_message", "Сообщить на сервере", "warning"),
                    ConventionInfo("query_in_loop", "Запрос в цикле", "warning"),
                    ConventionInfo("write_in_loop", "Запись в цикле", "warning"),
                    ConventionInfo("empty_except", "Попытка без Исключение", "warning"),
                    ConventionInfo("raise_without_text", "Исключение без текста", "warning"),
                    ConventionInfo("todo", "TODO/FIXME", "info"),
                ),
        )
    }
}
