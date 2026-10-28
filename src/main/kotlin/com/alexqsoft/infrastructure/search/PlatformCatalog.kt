/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.infrastructure.search

import com.alexqsoft.business.entities.Definition
import com.alexqsoft.business.entities.EnumDefinition
import com.alexqsoft.business.entities.EnumValueDefinition
import com.alexqsoft.business.entities.MethodDefinition
import com.alexqsoft.business.entities.PlatformContextData
import com.alexqsoft.business.entities.PlatformTypeDefinition
import com.alexqsoft.business.entities.PropertyDefinition
import com.alexqsoft.business.valueobjects.ApiType
import java.util.Locale

/**
 * Один результат поиска: элемент справочника и его владелец.
 *
 * @property owner тип, которому принадлежит метод, свойство или значение перечисления
 */
data class SearchHit(
    val definition: Definition,
    val owner: String? = null,
)

/**
 * Страница поиска.
 *
 * @property total сколько всего совпадений (может быть больше, чем в [items])
 * @property suggestions похожие имена, если совпадений нет
 * @property memberOwners типы, у которых есть элемент с таким именем, если совпадений нет
 */
data class SearchPage(
    val query: String,
    val type: ApiType?,
    val total: Int,
    val offset: Int,
    val limit: Int,
    val items: List<SearchHit>,
    val suggestions: List<String> = emptyList(),
    val memberOwners: List<String> = emptyList(),
)

private data class CatalogEntry(
    val definition: Definition,
    val type: ApiType,
    val owner: String?,
    val nameLower: String,
    val nameEnLower: String,
    val ownerLower: String,
    val descriptionLower: String,
)

/**
 * Поисковый каталог по разобранной справке платформы.
 *
 * Каталог строится один раз на модель и дальше только читается, поэтому поиск — это проход
 * по списку с ранжированием: на десятках тысяч элементов это единицы миллисекунд, зато не нужны
 * поддерживаемые вручную индексы, из-за которых поиск по описанию раньше просто не работал.
 *
 * Ранжирование повторяет логику Data1C: точное имя, префикс, вхождение в имя, английское имя,
 * описание, затем совпадение всех слов запроса. Дополнительно понимается запись «Тип.Член».
 */
class PlatformCatalog(
    private val data: PlatformContextData,
) {
    private val typesByName: Map<String, PlatformTypeDefinition>
    private val enumsByName: Map<String, EnumDefinition>
    private val methodsByName: Map<String, MethodDefinition>
    private val propertiesByName: Map<String, PropertyDefinition>
    private val entries: List<CatalogEntry>
    private val memberOwnersByName: Map<String, List<String>>

    init {
        typesByName = data.types.associateBy { it.name.lowercase(Locale.ROOT) }
        enumsByName = data.enums.associateBy { it.name.lowercase(Locale.ROOT) }
        methodsByName = data.methods.associateBy { it.name.lowercase(Locale.ROOT) }
        propertiesByName = data.properties.associateBy { it.name.lowercase(Locale.ROOT) }

        val owners = mutableMapOf<String, MutableList<String>>()
        val built = ArrayList<CatalogEntry>(data.totalDefinitions() * 4)

        fun add(
            definition: Definition,
            type: ApiType,
            owner: String? = null,
        ) {
            built +=
                CatalogEntry(
                    definition = definition,
                    type = type,
                    owner = owner,
                    nameLower = definition.name.lowercase(Locale.ROOT),
                    nameEnLower = definition.nameEn.lowercase(Locale.ROOT),
                    ownerLower = owner.orEmpty().lowercase(Locale.ROOT),
                    descriptionLower = definition.description.lowercase(Locale.ROOT),
                )
            if (owner != null) {
                owners
                    .getOrPut(definition.name.lowercase(Locale.ROOT)) { mutableListOf() }
                    .add("$owner.${definition.name}")
            }
        }

        data.types.forEach { type ->
            add(type, ApiType.TYPE)
            type.methods.forEach { add(it, ApiType.METHOD, owner = type.name) }
            type.properties.forEach { add(it, ApiType.PROPERTY, owner = type.name) }
        }
        data.methods.forEach { add(it, ApiType.METHOD) }
        data.properties.forEach { add(it, ApiType.PROPERTY) }
        data.enums.forEach { enum ->
            add(enum, ApiType.ENUM)
            enum.values.forEach { add(it, ApiType.ENUM, owner = enum.name) }
        }
        entries = built
        memberOwnersByName = owners
    }

    val types: List<PlatformTypeDefinition> get() = data.types

    val enums: List<EnumDefinition> get() = data.enums

    fun findType(name: String): PlatformTypeDefinition? = typesByName[name.trim().lowercase(Locale.ROOT)]

    fun findEnum(name: String): EnumDefinition? = enumsByName[name.trim().lowercase(Locale.ROOT)]

    fun findMethod(name: String): MethodDefinition? = methodsByName[name.trim().lowercase(Locale.ROOT)]

    fun findProperty(name: String): PropertyDefinition? = propertiesByName[name.trim().lowercase(Locale.ROOT)]

    /** Точные совпадения по имени с учётом фильтра вида. */
    fun findExact(
        name: String,
        type: ApiType? = null,
    ): List<Definition> {
        val key = name.trim().lowercase(Locale.ROOT)
        val result = mutableListOf<Definition>()
        if (type == null || type == ApiType.TYPE) findType(key)?.let { result += it }
        if (type == null || type == ApiType.ENUM) findEnum(key)?.let { result += it }
        if (type == null || type == ApiType.METHOD) findMethod(key)?.let { result += it }
        if (type == null || type == ApiType.PROPERTY) findProperty(key)?.let { result += it }
        return result
    }

    /** Элемент типа по имени: метод, свойство или значение перечисления. */
    fun findMember(
        type: PlatformTypeDefinition,
        memberName: String,
    ): Definition? {
        val key = memberName.trim().lowercase(Locale.ROOT)
        return type.methods.firstOrNull { it.name.lowercase(Locale.ROOT) == key }
            ?: type.properties.firstOrNull { it.name.lowercase(Locale.ROOT) == key }
    }

    /** Значение перечисления по имени. */
    fun findEnumValue(
        enum: EnumDefinition,
        valueName: String,
    ): EnumValueDefinition? {
        val key = valueName.trim().lowercase(Locale.ROOT)
        return enum.values.firstOrNull { it.name.lowercase(Locale.ROOT) == key }
    }

    /**
     * Поиск с ранжированием.
     *
     * @param limit сколько вернуть (1–50), [offset] — сколько пропустить
     */
    fun search(
        query: String,
        type: ApiType? = null,
        limit: Int = 10,
        offset: Int = 0,
    ): SearchPage {
        val normalized = query.trim().lowercase(Locale.ROOT)
        val words = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        val qualified = qualifiedMatch(normalized)

        val matches =
            entries
                .asSequence()
                .filter { type == null || it.type == type }
                .mapNotNull { entry ->
                    val rank = rank(entry, normalized, words)
                    if (rank == null) null else entry to rank
                }.sortedWith(
                    compareBy<Pair<CatalogEntry, Int>> { it.second }
                        .thenBy { it.first.nameLower.length }
                        .thenBy { it.first.nameLower }
                        .thenBy { it.first.ownerLower },
                ).map { SearchHit(it.first.definition, it.first.owner) }
                .toList()

        val combined =
            if (qualified != null && (type == null || kindOf(qualified.definition) == type)) {
                val withoutDuplicate = matches.filterNot { it.definition === qualified.definition }
                listOf(qualified) + withoutDuplicate
            } else {
                matches
            }

        val pageItems = combined.drop(offset).take(limit)
        val empty = matches.isEmpty()
        return SearchPage(
            query = query.trim(),
            type = type,
            total = combined.size,
            offset = offset,
            limit = limit,
            items = pageItems,
            suggestions = if (empty) suggest(normalized) else emptyList(),
            memberOwners = if (empty) memberOwners(normalized) else emptyList(),
        )
    }

    /** Похожие имена: сначала подстрока, затем близкие по расстоянию Левенштейна. */
    fun suggest(
        query: String,
        limit: Int = 5,
    ): List<String> {
        val normalized = query.trim().lowercase(Locale.ROOT)
        if (normalized.length < 2) return emptyList()

        val contains =
            entries
                .filter { it.nameLower.contains(normalized) || it.nameEnLower.contains(normalized) }
                .sortedWith(compareBy<CatalogEntry> { it.nameLower.length }.thenBy { it.nameLower })
                .map { it.definition.name }
                .distinct()
                .take(limit)
        if (contains.isNotEmpty()) return contains

        return entries
            .asSequence()
            .filter { kotlin.math.abs(it.nameLower.length - normalized.length) <= 2 }
            .map { it.definition.name to levenshtein(it.nameLower, normalized) }
            .filter { it.second in 1..2 }
            .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
            .map { it.first }
            .distinct()
            .take(limit)
            .toList()
    }

    /** Типы, у которых есть элемент с таким именем: «Добавить» → Массив, ТаблицаЗначений, … */
    fun memberOwners(
        query: String,
        limit: Int = 5,
    ): List<String> = memberOwnersByName[query.trim().lowercase(Locale.ROOT)].orEmpty().take(limit)

    private fun qualifiedMatch(normalized: String): SearchHit? {
        if (!normalized.contains('.')) return null
        val owner = normalized.substringBeforeLast('.').trim()
        val member = normalized.substringAfterLast('.').trim()
        if (owner.isEmpty() || member.isEmpty()) return null
        val type = findType(owner) ?: return null
        val found = findMember(type, member) ?: return null
        return SearchHit(found, type.name)
    }

    private fun kindOf(definition: Definition): ApiType =
        when (definition) {
            is MethodDefinition -> ApiType.METHOD
            is PropertyDefinition -> ApiType.PROPERTY
            is PlatformTypeDefinition -> ApiType.TYPE
            is EnumDefinition, is EnumValueDefinition -> ApiType.ENUM
        }

    private fun rank(
        entry: CatalogEntry,
        query: String,
        words: List<String>,
    ): Int? {
        val name = entry.nameLower
        val qualified = if (entry.ownerLower.isEmpty()) "" else "${entry.ownerLower}.$name"

        if (name == query || qualified == query) return 0
        if (name.startsWith(query)) return 1
        if (name.contains(query)) return 2
        if (entry.nameEnLower == query || (entry.ownerLower.isNotEmpty() && "${entry.ownerLower}.${entry.nameEnLower}" == query)) {
            return 3
        }
        if (entry.nameEnLower.contains(query)) return 4
        if (query.length >= 3 && entry.descriptionLower.contains(query)) return 5
        if (words.size > 1 && words.all { name.contains(it) || entry.ownerLower.contains(it) }) return 6
        if (words.size > 1 && words.all { entry.descriptionLower.contains(it) }) return 7
        return null
    }

    private fun levenshtein(
        left: String,
        right: String,
    ): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (i in 1..left.length) {
            current[0] = i
            for (j in 1..right.length) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }
}
