/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.infrastructure.dump

/**
 * Найденная процедура или функция модуля.
 *
 * @property parameters текст между скобками объявления (может быть пустым)
 */
data class ScannedRoutine(
    val name: String,
    val kind: String,
    val export: Boolean,
    val parameters: String,
    val startLine: Int,
    val endLine: Int,
)

/**
 * Лёгкий сканер объявлений BSL: процедуры, функции, экспорт, границы.
 *
 * Это не полный парсер языка, а достаточный инструмент для поиска и навигации: он не строит
 * дерево, но надёжно находит объявления и их границы, в том числе когда список параметров
 * перенесён на несколько строк. Строки и комментарии при подсчёте скобок не учитываются грубо,
 * поэтому редкие сложные случаи могут дать неточную границу — для карточки модуля это допустимо,
 * а для проверки кода нужен полноценный лексер (следующий этап).
 */
object BslModuleScanner {
    private val START =
        Regex(
            """^\s*(?:Асинх\s+|Async\s+)?(Процедура|Функция|Procedure|Function)\s+([\p{L}_][\p{L}\p{N}_]*)""",
            RegexOption.IGNORE_CASE,
        )
    private val END =
        Regex(
            """^\s*(КонецПроцедуры|КонецФункции|EndProcedure|EndFunction)\s*$""",
            RegexOption.IGNORE_CASE,
        )
    private val EXPORT = Regex("""(Экспорт|Export)""", RegexOption.IGNORE_CASE)

    fun scan(text: String): List<ScannedRoutine> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n')
        val routines = mutableListOf<ScannedRoutine>()
        var index = 0

        while (index < lines.size) {
            val match = START.find(lines[index])
            if (match == null) {
                index++
                continue
            }
            val kind = normalizeKind(match.groupValues[1])
            val name = match.groupValues[2]
            val startLine = index + 1
            val signature = StringBuilder()
            var depth = 0
            var signatureClosed = false
            var line = index

            while (line < lines.size) {
                val current = lines[line]
                for (character in current) {
                    when (character) {
                        '(' -> {
                            depth++
                            if (depth == 1 && signatureClosed) break
                        }

                        ')' -> {
                            depth--
                            if (depth == 0) signatureClosed = true
                        }
                    }
                    if (depth > 0 || signatureClosed) signature.append(character)
                }
                if (signatureClosed) break
                signature.append(' ')
                line++
            }

            val export = EXPORT.containsMatchIn(lines.getOrElse(line) { "" })
            val parameters =
                if (signature.startsWith("(") && signature.endsWith(")")) {
                    signature.substring(1, signature.length - 1).trim()
                } else {
                    ""
                }

            var depthOfRoutines = 1
            var endLine = lines.size
            var cursor = line + 1
            while (cursor < lines.size) {
                if (START.containsMatchIn(lines[cursor])) depthOfRoutines++
                if (END.containsMatchIn(lines[cursor])) {
                    depthOfRoutines--
                    if (depthOfRoutines == 0) {
                        endLine = cursor + 1
                        break
                    }
                }
                cursor++
            }

            routines += ScannedRoutine(name, kind, export, parameters, startLine, endLine)
            index = endLine.coerceAtLeast(startLine) + 1
        }

        return routines
    }

    private fun normalizeKind(raw: String): String =
        when (raw.lowercase()) {
            "функция", "function" -> "function"
            else -> "procedure"
        }
}
