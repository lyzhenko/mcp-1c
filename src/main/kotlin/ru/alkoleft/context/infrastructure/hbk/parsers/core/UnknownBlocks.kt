/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.infrastructure.hbk.parsers.core

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Политика для незнакомых блоков справки.
 *
 * Справка платформы меняется от версии к версии, и новый раздел в одной странице не должен
 * обрушивать разбор всей платформы. Незнакомый блок пропускается, а предупреждение о нём
 * печатается один раз — по нему видно, что парсер пора обновить.
 */
object UnknownBlocks {
    private val warned = ConcurrentHashMap.newKeySet<String>()

    fun skip(title: String): BlockHandler<*>? {
        if (warned.add(title)) {
            logger.warn { "Незнакомый блок справки пропущен: '$title'. Возможно, парсер пора обновить." }
        }
        return null
    }
}
