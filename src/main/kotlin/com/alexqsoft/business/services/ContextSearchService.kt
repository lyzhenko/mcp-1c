/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.business.services

import com.alexqsoft.business.entities.Definition
import com.alexqsoft.business.valueobjects.ApiType
import com.alexqsoft.exceptions.InvalidSearchQueryException
import com.alexqsoft.exceptions.PlatformTypeNotFoundException
import com.alexqsoft.exceptions.TypeMemberNotFoundException
import com.alexqsoft.infrastructure.search.CatalogProvider
import com.alexqsoft.infrastructure.search.PlatformCatalog
import com.alexqsoft.infrastructure.search.SearchPage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Поиск по справке платформы: запросы, точные карточки, состав типа и конструкторы.
 *
 * Сервис отвечает за валидацию аргументов и границы страниц, а ранжирование и разрешение имён
 * делает каталог. Все ошибки — доменные исключения с готовым текстом для JSON-ответа.
 */
@Service
class ContextSearchService(
    private val provider: CatalogProvider,
) {
    /** Поиск с ранжированием; пустой запрос — ошибка. */
    fun search(
        query: String,
        type: String? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): SearchPage {
        if (query.isBlank()) throw InvalidSearchQueryException("поисковый запрос не может быть пустым")
        val apiType = parseType(type)
        val safeLimit = (limit ?: DEFAULT_SEARCH_LIMIT).coerceIn(1, MAX_SEARCH_LIMIT)
        val safeOffset = (offset ?: 0).coerceAtLeast(0)
        logger.debug { "search: '$query', type=$apiType, limit=$safeLimit, offset=$safeOffset" }
        return provider.catalog().search(query, apiType, safeLimit, safeOffset)
    }

    /**
     * Точная карточка по имени. Если имя записано как «Тип.Член», ищется член типа.
     * Если ничего не найдено, возвращаются подсказки вместо исключения — их показывает агент.
     */
    fun info(
        name: String,
        type: String? = null,
    ): InfoResult {
        if (name.isBlank()) throw InvalidSearchQueryException("имя элемента не может быть пустым")
        val apiType = parseType(type)
        val catalog = provider.catalog()

        val items = mutableListOf<Definition>()
        if (apiType == null) qualified(catalog, name)?.let { items += it }
        if (items.isEmpty()) items += catalog.findExact(name, apiType)

        return if (items.isNotEmpty()) {
            InfoResult(items)
        } else {
            InfoResult(
                items = emptyList(),
                suggestions = catalog.suggest(name),
                memberOwners = catalog.memberOwners(name),
            )
        }
    }

    /** Член типа (метод или свойство) либо значение перечисления. */
    fun member(
        typeName: String,
        memberName: String,
    ): Definition {
        if (typeName.isBlank()) throw InvalidSearchQueryException("имя типа не может быть пустым")
        if (memberName.isBlank()) throw InvalidSearchQueryException("имя элемента типа не может быть пустым")
        val catalog = provider.catalog()

        catalog.findType(typeName)?.let { type ->
            return catalog.findMember(type, memberName)
                ?: throw TypeMemberNotFoundException(memberName, typeName)
        }
        catalog.findEnum(typeName)?.let { enum ->
            return catalog.findEnumValue(enum, memberName)
                ?: throw TypeMemberNotFoundException(memberName, typeName)
        }
        throw PlatformTypeNotFoundException(typeName)
    }

    /** Состав типа: методы и свойства, а для перечисления — его значения. */
    fun members(
        typeName: String,
        limit: Int? = null,
        offset: Int? = null,
    ): MembersPage {
        if (typeName.isBlank()) throw InvalidSearchQueryException("имя типа не может быть пустым")
        val catalog = provider.catalog()
        val safeLimit = (limit ?: DEFAULT_MEMBERS_LIMIT).coerceIn(1, MAX_MEMBERS_LIMIT)
        val safeOffset = (offset ?: 0).coerceAtLeast(0)

        catalog.findType(typeName)?.let { type ->
            val all = type.members()
            return MembersPage(typeName, all.size, safeOffset, safeLimit, all.drop(safeOffset).take(safeLimit))
        }
        catalog.findEnum(typeName)?.let { enum ->
            val all: List<Definition> = enum.values
            return MembersPage(typeName, all.size, safeOffset, safeLimit, all.drop(safeOffset).take(safeLimit))
        }
        throw PlatformTypeNotFoundException(typeName)
    }

    /** Конструкторы типа. */
    fun constructors(
        typeName: String,
        limit: Int? = null,
        offset: Int? = null,
    ): ConstructorsPage {
        if (typeName.isBlank()) throw InvalidSearchQueryException("имя типа не может быть пустым")
        val safeLimit = (limit ?: DEFAULT_CONSTRUCTORS_LIMIT).coerceIn(1, MAX_CONSTRUCTORS_LIMIT)
        val safeOffset = (offset ?: 0).coerceAtLeast(0)
        val type = provider.catalog().findType(typeName) ?: throw PlatformTypeNotFoundException(typeName)
        val all = type.constructors
        return ConstructorsPage(typeName, all.size, safeOffset, safeLimit, all.drop(safeOffset).take(safeLimit))
    }

    /** Похожие имена для подсказки в ошибке. */
    fun suggest(query: String): List<String> = runCatching { provider.catalog().suggest(query) }.getOrDefault(emptyList())

    /** Типы, у которых есть элемент с таким именем. */
    fun memberOwners(query: String): List<String> = runCatching { provider.catalog().memberOwners(query) }.getOrDefault(emptyList())

    private fun parseType(type: String?): ApiType? {
        if (type.isNullOrBlank()) return null
        return ApiType.getType(type)
            ?: throw InvalidSearchQueryException(
                "неизвестный вид элемента «$type»; допустимо: method, property, type, enum",
            )
    }

    private fun qualified(
        catalog: PlatformCatalog,
        name: String,
    ): Definition? {
        if (!name.contains('.')) return null
        val typeName = name.substringBeforeLast('.').trim()
        val memberName = name.substringAfterLast('.').trim()
        if (typeName.isEmpty() || memberName.isEmpty()) return null
        catalog.findType(typeName)?.let { type -> catalog.findMember(type, memberName)?.let { return it } }
        catalog.findEnum(typeName)?.let { enum -> catalog.findEnumValue(enum, memberName)?.let { return it } }
        return null
    }

    private fun invalidType(type: String?): Nothing =
        throw InvalidSearchQueryException(
            "неизвестный вид элемента «$type»; допустимо: method, property, type, enum",
        )

    companion object {
        const val DEFAULT_SEARCH_LIMIT = 10
        const val MAX_SEARCH_LIMIT = 50
        const val DEFAULT_MEMBERS_LIMIT = 100
        const val MAX_MEMBERS_LIMIT = 500
        const val DEFAULT_CONSTRUCTORS_LIMIT = 20
        const val MAX_CONSTRUCTORS_LIMIT = 100
    }
}
