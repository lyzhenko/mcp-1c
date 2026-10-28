/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.business.valueobjects

/**
 * Вид элемента API платформы: метод, свойство, тип, перечисление.
 *
 * Конструкторы не выделены в отдельный вид: они принадлежат типу и отдаются инструментом
 * `getConstructors`, а в поиске участвуют как часть типа.
 */
enum class ApiType {
    METHOD,
    PROPERTY,
    TYPE,
    ENUM,
    ;

    fun getDisplayName(): String =
        when (this) {
            METHOD -> "Метод"
            PROPERTY -> "Свойство"
            TYPE -> "Тип"
            ENUM -> "Перечисление"
        }

    fun getPluralName(): String =
        when (this) {
            METHOD -> "Методы"
            PROPERTY -> "Свойства"
            TYPE -> "Типы"
            ENUM -> "Перечисления"
        }

    companion object {
        /** Разбор значения аргумента `type`; null — фильтра нет. */
        fun getType(type: String?): ApiType? {
            if (type.isNullOrBlank()) return null
            return when (type.trim().lowercase()) {
                "object", "class", "datatype", "объект", "класс", "тип", "структура", "данные", "type" -> TYPE

                "метод", "функция", "процедура", "method", "function", "procedure" -> METHOD

                "свойство", "реквизит", "поле", "атрибут", "property", "attribute" -> PROPERTY

                "перечисление", "значение перечисления", "enum", "enumeration" -> ENUM

                else -> null
            }
        }
    }
}
