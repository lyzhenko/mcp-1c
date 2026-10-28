/*
 * Copyright (c) 2024-2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.exceptions

/**
 * Базовое исключение доменного слоя.
 *
 * Сообщение попадает в JSON-ответ агенту, поэтому пишется как законченная фраза без разметки.
 */
abstract class DomainException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Тип платформы не найден. */
class PlatformTypeNotFoundException(
    typeName: String,
) : DomainException("Тип «$typeName» не найден в справке платформы")

/** Невалидный поисковый запрос. */
class InvalidSearchQueryException(
    message: String,
) : DomainException("Невалидный запрос: $message")

/** Ошибка загрузки или разбора справки платформы. */
class PlatformContextLoadException(
    message: String,
    cause: Throwable? = null,
) : DomainException(message, cause)

/** Модель платформы ещё не готова (идёт разбор или он завершился ошибкой). */
class PlatformNotReadyException(
    message: String,
) : DomainException(message)

/** Выгрузка конфигурации не открыта. */
class DumpNotOpenException :
    DomainException(
        "Выгрузка конфигурации не открыта. Укажите --dump <каталог> при запуске " +
            "или откройте её инструментом dumpOpen.",
    )

/** Индекс выгрузки ещё строится или построить его не удалось. */
class DumpNotReadyException(
    message: String,
) : DomainException(message)

/** Элемент типа не найден. */
class TypeMemberNotFoundException(
    memberName: String,
    typeName: String,
) : DomainException("Элемент «$memberName» в типе «$typeName» не найден")

/** Элемент не найден. */
class DefinitionNotFoundException(
    entityName: String,
    typeName: String? = null,
) : DomainException(
        if (typeName.isNullOrBlank()) {
            "Элемент «$entityName» не найден"
        } else {
            "Элемент «$entityName» с типом «$typeName» не найден"
        },
    )
