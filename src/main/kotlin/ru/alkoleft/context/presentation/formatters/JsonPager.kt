/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.presentation.formatters

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Сборка JSON-страниц с ограничением размера.
 *
 * Общий механизм для справки по платформе и разбора выгрузки: страница уменьшается до тех пор,
 * пока ответ не влезет в лимит, а курсор `nextOffset` позволяет продолжить чтение. Если не влезает
 * даже один элемент, длинные строки сокращаются, ответ помечается `truncated` и остаётся валидным JSON.
 */
class JsonPager(
    private val maxChars: Int = DEFAULT_LIMIT,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) {
    fun <T> page(
        header: Map<String, Any?>,
        items: List<T>,
        offset: Int,
        total: Int,
        render: (T) -> Map<String, Any?>,
        hint: String?,
    ): String {
        var shown = minOf(items.size, (total - offset).coerceAtLeast(0))
        while (true) {
            val slice = items.take(shown)
            val nextOffset = if (offset + shown < total) offset + shown else null
            val frame = LinkedHashMap<String, Any?>()
            header.forEach { (key, value) -> if (value != null) frame[key] = value }
            frame["returned"] = shown
            frame["nextOffset"] = nextOffset
            frame["hasMore"] = nextOffset != null
            frame["results"] = slice.map(render)
            hint?.let { frame["hint"] = it }
            val json = fit(frame)
            if (json.length <= maxChars || shown <= 1) return json
            shown = maxOf(1, shown / 2)
        }
    }

    /** Сериализовать и, если ответ велик, сократить длинные строки, сохранив структуру. */
    fun fit(frame: Map<String, Any?>): String {
        var json = mapper.writeValueAsString(frame)
        if (json.length <= maxChars) return json
        for (limit in STRING_LIMITS) {
            val truncated = LinkedHashMap<String, Any?>()
            frame.forEach { (key, value) -> truncated[key] = shorten(value, limit) }
            truncated["truncated"] = true
            truncated["hint"] = "Ответ сокращён по размеру: сузьте запрос (offset, limit) или уточните имя."
            json = mapper.writeValueAsString(truncated)
            if (json.length <= maxChars) return json
        }
        return json
    }

    fun json(value: Any?): String = mapper.writeValueAsString(value)

    private fun shorten(
        value: Any?,
        limit: Int,
    ): Any? =
        when (value) {
            is String -> if (value.length > limit) value.take(limit) + "…" else value
            is Map<*, *> -> value.entries.associate { (key, item) -> key to shorten(item, limit) }
            is Iterable<*> -> value.map { shorten(it, limit) }
            else -> value
        }

    companion object {
        const val DEFAULT_LIMIT = 24_000
        private val STRING_LIMITS = intArrayOf(4000, 1500, 500, 200)
    }
}
