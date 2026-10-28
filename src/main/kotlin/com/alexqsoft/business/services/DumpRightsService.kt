/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.business.services

import com.alexqsoft.exceptions.InvalidSearchQueryException
import com.alexqsoft.infrastructure.dump.DumpStorage

/** Право роли в ответе. */
data class DumpRight(
    val role: String,
    val objectRef: String,
    val right: String,
    val value: Boolean,
    val condition: String?,
)

data class RightsPage(
    val role: String?,
    val metadata: String?,
    val condition: String?,
    val total: Int,
    val roles: List<String>,
    val items: List<DumpRight>,
)

/** Права ролей и RLS-условия из выгрузки. */
class DumpRightsService(
    private val storage: DumpStorage,
) {
    fun rights(
        role: String? = null,
        metadata: String? = null,
        condition: String? = null,
        limit: Int? = null,
    ): RightsPage {
        val index =
            if (storage.currentPath() == null) {
                throw InvalidSearchQueryException("выгрузка не открыта")
            } else {
                storage.awaitReady() ?: throw InvalidSearchQueryException("индекс выгрузки ещё не готов")
            }
        val rows = index.rights(role, metadata, condition, (limit ?: 200).coerceIn(1, 1000))
        return RightsPage(
            role = role,
            metadata = metadata,
            condition = condition,
            total = rows.size,
            roles = index.roleNames(),
            items = rows.map { DumpRight(it.role, it.objectRef, it.right, it.value, it.condition) },
        )
    }
}
