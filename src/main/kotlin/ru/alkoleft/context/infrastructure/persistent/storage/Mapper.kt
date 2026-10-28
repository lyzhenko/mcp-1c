/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.infrastructure.persistent.storage

import ru.alkoleft.context.business.entities.EnumDefinition
import ru.alkoleft.context.business.entities.EnumValueDefinition
import ru.alkoleft.context.business.entities.MethodDefinition
import ru.alkoleft.context.business.entities.ParameterDefinition
import ru.alkoleft.context.business.entities.PlatformTypeDefinition
import ru.alkoleft.context.business.entities.PropertyDefinition
import ru.alkoleft.context.business.entities.Signature
import ru.alkoleft.context.infrastructure.hbk.models.ConstructorInfo
import ru.alkoleft.context.infrastructure.hbk.models.EnumInfo
import ru.alkoleft.context.infrastructure.hbk.models.EnumValueInfo
import ru.alkoleft.context.infrastructure.hbk.models.MethodInfo
import ru.alkoleft.context.infrastructure.hbk.models.MethodParameterInfo
import ru.alkoleft.context.infrastructure.hbk.models.MethodSignatureInfo
import ru.alkoleft.context.infrastructure.hbk.models.ObjectInfo
import ru.alkoleft.context.infrastructure.hbk.models.PropertyInfo

/*
 * Маппинг «модель hbk → домен». Раньше здесь терялись сигнатуры, параметры и конструкторы,
 * поэтому MCP-ответы содержали только имя и описание. Маппинг обязан сохранять всё, что
 * разобрано из справки: если поле не нужно инструментам, его отбрасывает рендер, а не маппер.
 */

fun MethodParameterInfo.toEntity() =
    ParameterDefinition(
        name = normalizeParameterName(name),
        type = type,
        description = description,
        required = !isOptional,
    )

/**
 * Имя параметра из справки иногда приходит с угловыми скобками и диапазоном:
 * `<Значение>` или `<КоличествоЭлементов1>,...,<КоличествоЭлементовN>`. В ответе агент
 * должен видеть нормальное имя, а не обрывок разметки.
 */
private fun normalizeParameterName(raw: String): String {
    val trimmed = raw.trim().removePrefix("<").removeSuffix(">")
    val rangeEnd = trimmed.indexOf('>')
    return if (rangeEnd > 0) trimmed.substring(0, rangeEnd).trim() else trimmed
}

fun MethodInfo.toEntity(isGlobal: Boolean = false) =
    MethodDefinition(
        name = nameRu,
        nameEn = nameEn,
        description = description,
        returnType = returnValue?.type ?: "",
        signatures = signatures.map { it.toEntity() },
        example = example?.takeIf { it.isNotBlank() },
        note = note?.takeIf { it.isNotBlank() },
        related = relatedObjects.orEmpty().map { it.name }.filter { it.isNotBlank() },
        isGlobal = isGlobal,
    )

fun MethodSignatureInfo.toEntity() =
    Signature(
        name = name,
        syntax = syntax,
        description = description,
        parameters = parameters.map { it.toEntity() },
    )

fun ConstructorInfo.toSignature() =
    Signature(
        name = name,
        syntax = syntax,
        description = description,
        parameters = parameters.map { it.toEntity() },
    )

fun PropertyInfo.toEntity() =
    PropertyDefinition(
        name = nameRu,
        nameEn = nameEn,
        description = description,
        propertyType = typeName,
        isReadOnly = readonly,
        note = note?.takeIf { it.isNotBlank() },
        related = relatedObjects.orEmpty().map { it.name }.filter { it.isNotBlank() },
    )

fun ObjectInfo.toEntity() =
    PlatformTypeDefinition(
        name = nameRu,
        nameEn = nameEn,
        description = description,
        methods = methods.orEmpty().map { it.toEntity() },
        properties = properties.orEmpty().map { it.toEntity() },
        constructors = constructors.orEmpty().map { it.toSignature() },
        example = example?.takeIf { it.isNotBlank() },
        note = note?.takeIf { it.isNotBlank() },
        related = relatedObjects.orEmpty().map { it.name }.filter { it.isNotBlank() },
    )

fun EnumInfo.toEntity() =
    EnumDefinition(
        name = nameRu,
        nameEn = nameEn,
        description = description,
        values = values.map { it.toEntity() },
        example = example?.takeIf { it.isNotBlank() },
    )

fun EnumValueInfo.toEntity() =
    EnumValueDefinition(
        name = nameRu,
        nameEn = nameEn,
        description = description,
    )
