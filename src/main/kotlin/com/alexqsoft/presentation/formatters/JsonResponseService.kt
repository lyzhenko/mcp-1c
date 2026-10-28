/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.presentation.formatters

import com.alexqsoft.business.entities.Definition
import com.alexqsoft.business.entities.EnumDefinition
import com.alexqsoft.business.entities.EnumValueDefinition
import com.alexqsoft.business.entities.MethodDefinition
import com.alexqsoft.business.entities.ParameterDefinition
import com.alexqsoft.business.entities.PlatformTypeDefinition
import com.alexqsoft.business.entities.PropertyDefinition
import com.alexqsoft.business.entities.Signature
import com.alexqsoft.business.services.ConstructorsPage
import com.alexqsoft.business.services.InfoResult
import com.alexqsoft.business.services.MembersPage
import com.alexqsoft.business.services.PlatformStatus
import com.alexqsoft.infrastructure.search.SearchHit
import com.alexqsoft.infrastructure.search.SearchPage
import java.nio.file.Files
import java.time.Instant

/**
 * Формирование JSON-ответов инструментов.
 *
 * Ответы самодокументированы: у каждого элемента есть `kind`, у найденного — поля `found`,
 * `total`, `returned`, `nextOffset` и `truncated`. Размер ответа ограничен ([maxChars]):
 * если он не влезает, сервер уменьшает страницу и отдаёт `nextOffset`, а не обрезает JSON
 * посередине. Это повторяет поведение Data1C, где ответы инструментов тоже ограничены
 * и продолжаемы.
 */
class JsonResponseService(
    private val maxChars: Int = DEFAULT_LIMIT,
) {
    private val pager = JsonPager(maxChars)

    /** Результаты поиска: имя, владелец и короткое описание; подробности — через `info`. */
    fun search(page: SearchPage): String {
        val header = mutableMapOf<String, Any?>("query" to page.query, "total" to page.total)
        page.type?.let { header["type"] = it.name.lowercase() }

        val empty = page.total == 0
        if (empty) {
            if (page.suggestions.isNotEmpty()) header["suggestions"] = page.suggestions
            if (page.memberOwners.isNotEmpty()) header["memberOwners"] = page.memberOwners
        }
        val hint =
            if (empty) {
                "Ничего не найдено. Уточните имя или используйте подсказки."
            } else {
                "Полная карточка — info(name=...); состав типа — getMembers(typeName=...)."
            }
        return pager.page(header, page.items, page.offset, page.total, ::searchItem, hint)
    }

    /** Точные карточки элементов; если ничего не нашлось — подсказки. */
    fun info(result: InfoResult): String {
        if (result.items.isEmpty()) {
            return error(
                code = "not_found",
                message = "В справке платформы нет элемента с таким именем",
                suggestions = result.suggestions,
                memberOwners = result.memberOwners,
            )
        }
        if (result.items.size == 1) return definition(result.items.first())
        return pager.fit(
            linkedMapOf(
                "found" to result.items.size,
                "items" to result.items.map { searchItem(SearchHit(it)) },
                "hint" to "Имён несколько — уточните вид аргументом type: method, property, type или enum.",
            ),
        )
    }

    /** Полная карточка одного элемента. */
    fun definition(definition: Definition): String {
        val frame = linkedMapOf<String, Any?>("found" to true)
        frame.putAll(definitionNode(definition))
        return pager.fit(frame)
    }

    /** Состав типа; синтаксис методов приходит в `signatures`. */
    fun members(page: MembersPage): String {
        val header =
            linkedMapOf<String, Any?>(
                "type" to page.typeName,
                "total" to page.total,
                "offset" to page.offset,
            )
        return pager.page(
            header,
            page.items,
            page.offset,
            page.total,
            ::definitionNode,
            "Продолжение — offset и limit; у методов в signatures есть синтаксис и параметры.",
        )
    }

    /** Конструкторы типа. */
    fun constructors(page: ConstructorsPage): String {
        val header =
            linkedMapOf<String, Any?>(
                "type" to page.typeName,
                "total" to page.total,
                "offset" to page.offset,
            )
        return pager.page(
            header,
            page.items,
            page.offset,
            page.total,
            { signatureNode(it) },
            if (page.total == 0) {
                "В справке у этого типа нет раздела конструкторов: объект создаётся фабрикой или методами."
            } else {
                "Продолжение — offset и limit."
            },
        )
    }

    /** Состояние сервера: что загружено и что ещё установлено. */
    fun status(
        status: PlatformStatus,
        serverVersion: String,
        tools: List<String>,
        dump: Map<String, Any?>? = null,
    ): String {
        val frame =
            linkedMapOf<String, Any?>(
                "state" to status.state,
                "server" to
                    linkedMapOf<String, Any?>(
                        "name" to "mcp-bsl-context",
                        "version" to serverVersion,
                        "tools" to tools,
                    ),
            )
        dump?.let { frame["dump"] = it }
        status.error?.let { frame["error"] = it }
        if (status.state == "loading" || status.state == "idle") {
            frame["elapsedMs"] = status.elapsedMs
            frame["hint"] =
                if (status.state == "loading") {
                    "Идёт разбор справки; повторите запрос через несколько секунд."
                } else {
                    "Разбор ещё не начат: первый запрос к search/info/getMember запустит его."
                }
        }
        status.data?.let { data ->
            frame["platform"] =
                linkedMapOf<String, Any?>(
                    "version" to data.info.version,
                    "locale" to data.info.locale,
                    "path" to data.info.path,
                    "sourceBytes" to data.info.sizeBytes,
                    "loadedAt" to iso(data.info.loadedAt),
                    "parseMillis" to data.info.parseMillis,
                    "fromCache" to status.fromCache,
                    "loadMillis" to status.loadMillis,
                )
            frame["catalog"] =
                linkedMapOf<String, Any?>(
                    "types" to data.types.size,
                    "methods" to data.methods.size,
                    "properties" to data.properties.size,
                    "enums" to data.enums.size,
                    "total" to data.totalDefinitions(),
                )
        }
        frame["cache"] =
            linkedMapOf<String, Any?>(
                "enabled" to status.cacheEnabled,
                "dir" to status.cacheDir.toString(),
                "file" to status.cacheFile?.toString(),
                "sizeBytes" to
                    (
                        status.cacheFile?.let {
                            runCatching { Files.size(it) }.getOrNull() ?: 0
                        } ?: 0
                    ),
            )
        frame["availablePlatforms"] =
            status.available.map { installation ->
                linkedMapOf<String, Any?>(
                    "version" to installation.version,
                    "path" to installation.path.toString(),
                    "locales" to installation.locales.keys.sorted(),
                )
            }
        frame["options"] =
            linkedMapOf<String, Any?>(
                "locale" to status.options.locale,
                "version" to status.options.version,
                "waitTimeoutMs" to status.options.waitTimeoutMs,
                "responseLimit" to maxChars,
            )
        return pager.fit(frame)
    }

    /** Ошибка в едином виде: код, сообщение и подсказки. */
    fun error(
        code: String,
        message: String,
        suggestions: List<String> = emptyList(),
        memberOwners: List<String> = emptyList(),
    ): String {
        val error = linkedMapOf<String, Any?>("code" to code, "message" to message)
        if (suggestions.isNotEmpty()) error["suggestions"] = suggestions
        if (memberOwners.isNotEmpty()) error["memberOwners"] = memberOwners
        val frame = linkedMapOf<String, Any?>("error" to error)
        if (suggestions.isNotEmpty() || memberOwners.isNotEmpty()) {
            frame["hint"] = "Похожие имена — в suggestions; типы с таким членом — в memberOwners."
        }
        return pager.fit(frame)
    }

    // --- построение узлов ---------------------------------------------------

    private fun searchItem(hit: SearchHit): Map<String, Any?> {
        val definition = hit.definition
        val description =
            definition.description
                .take(SEARCH_DESCRIPTION)
                .let { if (definition.description.length > SEARCH_DESCRIPTION) "$it…" else it }
                .takeIf { it.isNotBlank() }
        return linkedMapOf<String, Any?>(
            "kind" to kindOf(definition),
            "name" to definition.name,
            "nameEn" to englishName(definition),
            "owner" to hit.owner,
            "description" to description,
        ).withoutNulls()
    }

    private fun definitionNode(definition: Definition): Map<String, Any?> =
        when (definition) {
            is MethodDefinition -> methodNode(definition)
            is PropertyDefinition -> propertyNode(definition)
            is PlatformTypeDefinition -> typeNode(definition)
            is EnumDefinition -> enumNode(definition)
            is EnumValueDefinition -> enumValueNode(definition)
        }

    private fun methodNode(method: MethodDefinition): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "kind" to "method",
            "name" to method.name,
            "nameEn" to englishName(method),
            "global" to method.isGlobal.takeIf { it },
            "returnType" to method.returnType.takeIf { it.isNotBlank() },
            "description" to method.description.takeIf { it.isNotBlank() },
            "signatures" to method.signatures.map(::signatureNode).takeIf { it.isNotEmpty() },
            "example" to method.example,
            "note" to method.note,
            "related" to method.related.takeIf { it.isNotEmpty() },
        ).withoutNulls()

    private fun propertyNode(property: PropertyDefinition): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "kind" to "property",
            "name" to property.name,
            "nameEn" to englishName(property),
            "propertyType" to property.propertyType.takeIf { it.isNotBlank() },
            "readOnly" to property.isReadOnly,
            "description" to property.description.takeIf { it.isNotBlank() },
            "note" to property.note,
            "related" to property.related.takeIf { it.isNotEmpty() },
        ).withoutNulls()

    private fun typeNode(type: PlatformTypeDefinition): Map<String, Any?> {
        val total = type.memberCount()
        val node =
            linkedMapOf<String, Any?>(
                "kind" to "type",
                "name" to type.name,
                "nameEn" to englishName(type),
                "description" to type.description.takeIf { it.isNotBlank() },
                "counts" to
                    linkedMapOf<String, Any?>(
                        "methods" to type.methods.size,
                        "properties" to type.properties.size,
                        "constructors" to type.constructors.size,
                    ),
                "constructors" to
                    type.constructors
                        .take(MAX_INLINE_CONSTRUCTORS)
                        .map(::signatureNode)
                        .takeIf { it.isNotEmpty() },
                "example" to type.example,
                "note" to type.note,
                "related" to type.related.takeIf { it.isNotEmpty() },
            )
        if (total in 1..SMALL_TYPE_MEMBERS) {
            node["members"] = type.members().map { searchItem(SearchHit(it, type.name)) }
        } else if (total > SMALL_TYPE_MEMBERS) {
            node["hint"] = "Состав типа: getMembers(typeName=\"${type.name}\"); конструкторы: getConstructors."
        }
        return node.withoutNulls()
    }

    private fun enumNode(enum: EnumDefinition): Map<String, Any?> {
        val node =
            linkedMapOf<String, Any?>(
                "kind" to "enum",
                "name" to enum.name,
                "nameEn" to englishName(enum),
                "description" to enum.description.takeIf { it.isNotBlank() },
                "counts" to linkedMapOf<String, Any?>("values" to enum.values.size),
                "values" to enum.values.take(MAX_INLINE_ENUM_VALUES).map(::enumValueNode),
                "example" to enum.example,
            )
        if (enum.values.size > MAX_INLINE_ENUM_VALUES) {
            node["hint"] = "Все значения: getMembers(typeName=\"${enum.name}\")."
        }
        return node.withoutNulls()
    }

    private fun enumValueNode(value: EnumValueDefinition): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "kind" to "enum_value",
            "name" to value.name,
            "nameEn" to englishName(value),
            "description" to value.description.takeIf { it.isNotBlank() },
        ).withoutNulls()

    private fun signatureNode(signature: Signature): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "name" to signature.name.takeIf { it.isNotBlank() && it != "Основная" },
            "syntax" to signature.syntax.takeIf { it.isNotBlank() },
            "description" to signature.description.takeIf { it.isNotBlank() },
            "parameters" to signature.parameters.map(::parameterNode).takeIf { it.isNotEmpty() },
        ).withoutNulls()

    private fun parameterNode(parameter: ParameterDefinition): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "name" to parameter.name,
            "type" to parameter.type.takeIf { it.isNotBlank() },
            "required" to parameter.required,
            "description" to parameter.description.takeIf { it.isNotBlank() },
        ).withoutNulls()

    private fun kindOf(definition: Definition): String =
        when (definition) {
            is MethodDefinition -> "method"
            is PropertyDefinition -> "property"
            is PlatformTypeDefinition -> "type"
            is EnumDefinition -> "enum"
            is EnumValueDefinition -> "enum_value"
        }

    private fun englishName(definition: Definition): String? =
        definition.nameEn.takeIf { it.isNotBlank() && !it.equals(definition.name, ignoreCase = true) }

    private fun iso(epochMillis: Long): String? = epochMillis.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() }

    private fun Map<String, Any?>.withoutNulls(): Map<String, Any?> = filterValues { it != null }

    companion object {
        const val DEFAULT_LIMIT = 24_000
        private const val SEARCH_DESCRIPTION = 220
        private const val SMALL_TYPE_MEMBERS = 40
        private const val MAX_INLINE_CONSTRUCTORS = 5
        private const val MAX_INLINE_ENUM_VALUES = 100
    }
}
