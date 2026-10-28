/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.infrastructure.dump

import java.util.Locale

/** Тип токена BSL. */
enum class BslTokenType {
    IDENT,
    STRING,
    NUMBER,
    PUNCT,
    DIRECTIVE,
}

/** Токен модуля BSL с номером строки. */
data class BslToken(
    val type: BslTokenType,
    val text: String,
    val line: Int,
) {
    fun isIdentifier(value: String): Boolean = type == BslTokenType.IDENT && text.equals(value, ignoreCase = true)
}

/** Параметр процедуры/функции. */
data class ParsedParameter(
    val name: String,
    val byValue: Boolean,
    val hasDefault: Boolean,
    val line: Int,
)

/** Локальная переменная или переменная модуля. */
data class ParsedLocal(
    val name: String,
    val line: Int,
)

/** Неиспользуемый параметр или локальная переменная. */
data class ParsedUnused(
    val name: String,
    val kind: String,
    val line: Int,
)

/** Вызов метода, функции или конструктора. */
data class ParsedCall(
    val name: String,
    val qualifier: String?,
    val kind: String,
    val line: Int,
    val arguments: Int,
    val qualified: Boolean = false,
)

/** Обращение к объекту метаданных из кода или запроса. */
data class ParsedMetadataRef(
    val collection: String,
    val objectName: String,
    val context: String,
    val line: Int,
)

/** Разобранная процедура или функция. */
data class ParsedRoutine(
    val name: String,
    val kind: String,
    val export: Boolean,
    val parameters: List<ParsedParameter>,
    val locals: List<ParsedLocal>,
    val calls: List<ParsedCall>,
    val metadataRefs: List<ParsedMetadataRef>,
    val unused: List<ParsedUnused> = emptyList(),
    val hasReturn: Boolean,
    val startLine: Int,
    val endLine: Int,
    val region: String?,
) {
    /** Сколько параметров обязательно (без значения по умолчанию). */
    fun requiredParameters(): Int = parameters.count { !it.hasDefault }

    fun parameterNames(): Set<String> = parameters.map { it.name.lowercase(Locale.ROOT) }.toSet()
}

/** Результат разбора модуля. */
data class ParsedBslModule(
    val lines: Int,
    val routines: List<ParsedRoutine>,
    val moduleLocals: List<ParsedLocal>,
    val calls: List<ParsedCall>,
    val metadataRefs: List<ParsedMetadataRef>,
)

/**
 * Разбор модуля BSL для навигации и проверок.
 *
 * Это не компилятор: токенизатор и структурный проход находят процедуры, параметры, локальные
 * переменные, вызовы, `Возврат` и обращения к метаданным. Разбор устойчив к незнакомым
 * конструкциям: непонятное пропускается, а не роняет весь модуль.
 */
object BslParser {
    private val METADATA_COLLECTIONS =
        setOf(
            "справочники",
            "документы",
            "регистрысведений",
            "регистрынакопления",
            "регистрыбухгалтерии",
            "регистрырасчета",
            "плансчетов",
            "планывидовхарактеристик",
            "планывидоврасчета",
            "планыобмена",
            "перечисления",
            "отчеты",
            "обработки",
            "бизнеспроцессы",
            "задачи",
            "журналыдокументов",
            "константы",
            "критерииотбора",
            "функциональныеопции",
        )

    private val CALL_KEYWORDS =
        setOf(
            "если",
            "иначеесли",
            "для",
            "пока",
            "возврат",
            "перем",
            "процедура",
            "функция",
            "конецпроцедуры",
            "конецфункции",
            "новый",
            "и",
            "или",
            "не",
            "тогда",
            "иначе",
            "конецесли",
            "конеццикла",
            "попытка",
            "исключение",
            "конецпопытки",
            "вызватьисключение",
            "перейти",
            "продолжить",
            "прервать",
            "выполнить",
            "вычислить",
        )

    private val QUERY_REFERENCE =
        Regex(
            """(?:ИЗ|СОЕДИНЕНИЕ|ОБЪЕДИНИТЬ(?:\s+ВСЕ)?)\s+([\p{L}_][\p{L}\p{N}_]*)\s*\.\s*([\p{L}_][\p{L}\p{N}_]*)""",
            RegexOption.IGNORE_CASE,
        )

    fun parse(text: String): ParsedBslModule {
        val tokens = tokenize(text)
        val routines = mutableListOf<ParsedRoutine>()
        val moduleLocals = mutableListOf<ParsedLocal>()
        val moduleCalls = mutableListOf<ParsedCall>()
        val moduleRefs = mutableListOf<ParsedMetadataRef>()
        val regions = ArrayDeque<String>()
        var index = 0

        while (index < tokens.size) {
            val token = tokens[index]
            if (token.type == BslTokenType.DIRECTIVE) {
                updateRegions(token.text, regions)
                index++
                continue
            }
            if (token.type == BslTokenType.IDENT && (token.isIdentifier("Процедура") || token.isIdentifier("Функция"))) {
                val parsed = parseRoutine(tokens, index, regions.lastOrNull())
                if (parsed == null) {
                    index++
                    continue
                }
                routines += parsed.routine
                index = parsed.nextIndex
                continue
            }
            index++
        }

        // Модульный код: всё, что не попало в процедуры, разбираем отдельно.
        val routineRanges = routines.map { it.startLine..it.endLine }
        val moduleTokens = tokens.filter { token -> routineRanges.none { token.line in it } }
        val moduleAnalysis = analyze(moduleTokens, null)
        moduleLocals += moduleAnalysis.locals
        moduleCalls += moduleAnalysis.calls
        moduleRefs += moduleAnalysis.metadataRefs

        return ParsedBslModule(
            lines = text.count { it == '\n' } + if (text.isEmpty() || text.endsWith('\n')) 0 else 1,
            routines = routines,
            moduleLocals = moduleLocals,
            calls = moduleCalls,
            metadataRefs = moduleRefs,
        )
    }

    private data class ParsedRoutineAt(
        val routine: ParsedRoutine,
        val nextIndex: Int,
    )

    private fun parseRoutine(
        tokens: List<BslToken>,
        start: Int,
        region: String?,
    ): ParsedRoutineAt? {
        if (start + 1 >= tokens.size) return null
        val kindToken = tokens[start]
        val nameToken = tokens[start + 1]
        if (nameToken.type != BslTokenType.IDENT) return null
        var index = start + 2
        val parameters = mutableListOf<ParsedParameter>()
        if (index < tokens.size && tokens[index].isPunct("(")) {
            val close = matchingParen(tokens, index)
            parameters += parseParameters(tokens, index + 1, close)
            index = close + 1
        } else {
            return null
        }

        var export = false
        if (index < tokens.size && tokens[index].isIdentifier("Экспорт")) {
            export = true
            index++
        }

        val bodyStart = index
        val endKeywords = if (kindToken.isIdentifier("Функция")) setOf("конецфункции") else setOf("конецпроцедуры")
        var depth = 0
        var end = tokens.size
        while (index < tokens.size) {
            val token = tokens[index]
            if (token.type == BslTokenType.IDENT) {
                if (token.isIdentifier("Процедура") || token.isIdentifier("Функция")) depth++
                if (endKeywords.any { token.isIdentifier(it) }) {
                    if (depth == 0) {
                        end = index
                        break
                    }
                    depth--
                }
            }
            index++
        }

        val body = tokens.subList(bodyStart, end)
        val analysis = analyze(body, parameters)
        val endLine = tokens.getOrNull(end)?.line ?: (tokens.lastOrNull()?.line ?: start + 1)
        val counts =
            body
                .filter { it.type == BslTokenType.IDENT }
                .groupingBy { it.text.lowercase(Locale.ROOT) }
                .eachCount()
        val unusedParameters =
            parameters
                .filter { (counts[it.name.lowercase(Locale.ROOT)] ?: 0) == 0 }
                .map { ParsedUnused(it.name, "parameter", it.line) }
        val locals = analysis.locals.distinctBy { it.name.lowercase(Locale.ROOT) }
        val unusedLocals =
            locals
                .filter { (counts[it.name.lowercase(Locale.ROOT)] ?: 0) <= 1 && !it.name.startsWith("_") }
                .map { ParsedUnused(it.name, "local", it.line) }

        val routine =
            ParsedRoutine(
                name = nameToken.text,
                kind = if (kindToken.isIdentifier("Функция")) "function" else "procedure",
                export = export,
                parameters = parameters,
                locals = locals,
                calls = analysis.calls,
                metadataRefs = analysis.metadataRefs,
                unused = unusedParameters + unusedLocals,
                hasReturn = body.any { it.isIdentifier("Возврат") },
                startLine = kindToken.line,
                endLine = endLine,
                region = region,
            )
        return ParsedRoutineAt(routine, end + 1)
    }

    private fun parseParameters(
        tokens: List<BslToken>,
        from: Int,
        to: Int,
    ): List<ParsedParameter> {
        val result = mutableListOf<ParsedParameter>()
        var index = from
        var start = from
        var depth = 0
        while (index <= to) {
            val token = tokens.getOrNull(index)
            val atEnd = index == to
            if (atEnd || (token != null && token.isPunct(",") && depth == 0)) {
                val segment = tokens.subList(start, index.coerceAtMost(tokens.size))
                parseParameter(segment)?.let { result += it }
                start = index + 1
            } else if (token != null) {
                when {
                    token.isPunct("(") || token.isPunct("[") -> depth++
                    token.isPunct(")") || token.isPunct("]") -> depth--
                }
            }
            index++
        }
        return result
    }

    private fun parseParameter(segment: List<BslToken>): ParsedParameter? {
        var index = 0
        var byValue = false
        while (index < segment.size && segment[index].type == BslTokenType.IDENT && segment[index].isIdentifier("Знач")) {
            byValue = true
            index++
        }
        val name = segment.getOrNull(index)?.takeIf { it.type == BslTokenType.IDENT } ?: return null
        val hasDefault = segment.drop(index + 1).any { it.isPunct("=") }
        return ParsedParameter(name.text, byValue, hasDefault, name.line)
    }

    private data class Analysis(
        val locals: MutableList<ParsedLocal>,
        val calls: MutableList<ParsedCall>,
        val metadataRefs: MutableList<ParsedMetadataRef>,
    )

    private fun analyze(
        tokens: List<BslToken>,
        parameters: List<ParsedParameter>?,
    ): Analysis {
        val locals = mutableListOf<ParsedLocal>()
        val calls = mutableListOf<ParsedCall>()
        val refs = mutableListOf<ParsedMetadataRef>()
        val parameterNames = parameters.orEmpty().map { it.name.lowercase(Locale.ROOT) }.toSet()

        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            when {
                token.type == BslTokenType.IDENT && token.isIdentifier("Перем") -> {
                    index++
                    while (index < tokens.size && !tokens[index].isPunct(";")) {
                        val current = tokens[index]
                        if (current.type == BslTokenType.IDENT) {
                            val name = current.text.lowercase(Locale.ROOT)
                            if (name !in parameterNames) locals += ParsedLocal(current.text, current.line)
                        }
                        index++
                    }
                }

                token.type == BslTokenType.IDENT && token.isIdentifier("Для") -> {
                    val each = tokens.getOrNull(index + 1)
                    if (each != null && (each.isIdentifier("Каждого") || each.isIdentifier("Каждый"))) {
                        val name = tokens.getOrNull(index + 2)
                        if (name?.type == BslTokenType.IDENT && name.text.lowercase(Locale.ROOT) !in parameterNames) {
                            locals += ParsedLocal(name.text, name.line)
                        }
                    }
                }

                token.type == BslTokenType.IDENT && token.isIdentifier("Новый") -> {
                    val typeName = tokens.getOrNull(index + 1)
                    if (typeName?.type == BslTokenType.IDENT) {
                        calls += ParsedCall(typeName.text, null, "new", token.line, argumentsAfter(tokens, index + 2))
                    }
                    index++
                }

                token.type == BslTokenType.IDENT && token.text.lowercase(Locale.ROOT) !in CALL_KEYWORDS -> {
                    val next = tokens.getOrNull(index + 1)
                    if (next?.isPunct("(") == true) {
                        calls +=
                            ParsedCall(
                                name = token.text,
                                qualifier = qualifierBefore(tokens, index),
                                kind = "call",
                                line = token.line,
                                arguments = argumentsAfter(tokens, index + 1),
                                qualified = tokens.getOrNull(index - 1)?.isPunct(".") == true,
                            )
                    } else if (next?.isPunct("=") == true && isStatementStart(tokens, index)) {
                        val name = token.text.lowercase(Locale.ROOT)
                        if (name !in parameterNames) locals += ParsedLocal(token.text, token.line)
                    }

                    if (token.text.lowercase(Locale.ROOT) in METADATA_COLLECTIONS && next?.isPunct(".") == true) {
                        val objectName = tokens.getOrNull(index + 2)
                        val called = tokens.getOrNull(index + 3)?.isPunct("(") == true
                        if (objectName?.type == BslTokenType.IDENT && !called) {
                            refs += ParsedMetadataRef(token.text, objectName.text, "code", token.line)
                        }
                    }
                }

                token.type == BslTokenType.STRING && QUERY_REFERENCE.containsMatchIn(token.text) -> {
                    QUERY_REFERENCE.findAll(token.text).forEach { match ->
                        refs +=
                            ParsedMetadataRef(
                                collection = match.groupValues[1],
                                objectName = match.groupValues[2],
                                context = "query",
                                line = token.line,
                            )
                    }
                }
            }
            index++
        }
        return Analysis(locals, calls, refs)
    }

    private fun isStatementStart(
        tokens: List<BslToken>,
        index: Int,
    ): Boolean {
        if (index == 0) return true
        val previous = tokens[index - 1]
        if (previous.line != tokens[index].line) return true
        return previous.isPunct(";") ||
            previous.isPunct(":") ||
            previous.isIdentifier("Тогда") ||
            previous.isIdentifier("Иначе") ||
            previous.isIdentifier("Попытка") ||
            previous.isIdentifier("Исключение")
    }

    private fun qualifierBefore(
        tokens: List<BslToken>,
        index: Int,
    ): String? {
        if (tokens.getOrNull(index - 1)?.isPunct(".") != true) return null
        val parts = mutableListOf<String>()
        var cursor = index - 2
        while (cursor >= 0) {
            val token = tokens[cursor]
            if (token.type != BslTokenType.IDENT) break
            parts.add(0, token.text)
            if (tokens.getOrNull(cursor - 1)?.isPunct(".") == true) {
                cursor -= 2
            } else {
                break
            }
        }
        return parts.joinToString(".").takeIf { it.isNotEmpty() }
    }

    private fun argumentsAfter(
        tokens: List<BslToken>,
        openParenIndex: Int,
    ): Int {
        if (tokens.getOrNull(openParenIndex)?.isPunct("(") != true) return 0
        val close = matchingParen(tokens, openParenIndex)
        if (close <= openParenIndex + 1) return 0
        var depth = 0
        var count = 1
        for (index in openParenIndex + 1 until close) {
            val token = tokens[index]
            when {
                token.isPunct("(") || token.isPunct("[") -> depth++
                token.isPunct(")") || token.isPunct("]") -> depth--
                token.isPunct(",") && depth == 0 -> count++
            }
        }
        return count
    }

    private fun matchingParen(
        tokens: List<BslToken>,
        openIndex: Int,
    ): Int {
        var depth = 0
        for (index in openIndex until tokens.size) {
            val token = tokens[index]
            when {
                token.isPunct("(") -> depth++
                token.isPunct(")") -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return tokens.size
    }

    private fun BslToken.isPunct(value: String): Boolean = type == BslTokenType.PUNCT && text == value

    private fun updateRegions(
        directive: String,
        regions: ArrayDeque<String>,
    ) {
        val trimmed = directive.removePrefix("#").trim()
        when {
            trimmed.startsWith("Область", ignoreCase = true) -> regions.addLast(trimmed.removePrefix("Область").trim())
            trimmed.startsWith("КонецОбласти", ignoreCase = true) -> if (regions.isNotEmpty()) regions.removeLast()
        }
    }

    fun tokenize(text: String): List<BslToken> {
        val tokens = mutableListOf<BslToken>()
        var index = 0
        var line = 1
        var lineStart = true

        while (index < text.length) {
            val character = text[index]
            when {
                character == '\n' -> {
                    line++
                    lineStart = true
                    index++
                }

                character.isWhitespace() -> index++

                character == '/' && text.getOrNull(index + 1) == '/' -> {
                    while (index < text.length && text[index] != '\n') index++
                }

                character == '#' && lineStart -> {
                    val start = index
                    while (index < text.length && text[index] != '\n') index++
                    tokens += BslToken(BslTokenType.DIRECTIVE, text.substring(start, index).trim(), line)
                }

                character == '"' -> {
                    val startLine = line
                    index++
                    val value = StringBuilder()
                    while (index < text.length) {
                        val current = text[index]
                        if (current == '"') {
                            if (text.getOrNull(index + 1) == '"') {
                                value.append('"')
                                index += 2
                                continue
                            }
                            index++
                            break
                        }
                        if (current == '\n') line++
                        value.append(current)
                        index++
                    }
                    tokens += BslToken(BslTokenType.STRING, value.toString(), startLine)
                    lineStart = false
                }

                character.isLetter() || character == '_' -> {
                    val start = index
                    while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_')) index++
                    tokens += BslToken(BslTokenType.IDENT, text.substring(start, index), line)
                    lineStart = false
                }

                character.isDigit() -> {
                    val start = index
                    while (index < text.length && (text[index].isDigit() || text[index] == '.')) index++
                    tokens += BslToken(BslTokenType.NUMBER, text.substring(start, index), line)
                    lineStart = false
                }

                else -> {
                    tokens += BslToken(BslTokenType.PUNCT, character.toString(), line)
                    lineStart = false
                    index++
                }
            }
            if (character == '\n') lineStart = true
        }
        return tokens
    }
}
