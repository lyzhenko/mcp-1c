/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.infrastructure.dump

import com.alexqsoft.business.entities.DumpField
import com.alexqsoft.business.entities.DumpInfo
import com.alexqsoft.business.entities.DumpModule
import com.alexqsoft.business.entities.DumpObject
import com.alexqsoft.business.entities.DumpRoutine
import java.io.Closeable
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.Locale

/**
 * Чтение SQLite-индекса выгрузки.
 *
 * Индекс строится [DumpIndexer] и после сборки только читается. Одно соединение защищено
 * блокировкой: запросы короткие, а инструменты могут вызываться параллельно.
 */
class DumpIndex(
    val file: Path,
) : Closeable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
    private val lock = Any()

    init {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA query_only = true")
        }
    }

    fun info(): DumpInfo =
        synchronized(lock) {
            val meta = readMeta()

            fun int(key: String) = meta[key]?.toIntOrNull() ?: 0
            DumpInfo(
                path = meta["dump.path"].orEmpty(),
                configuration = meta["dump.configuration"].orEmpty(),
                version = meta["dump.version"].orEmpty(),
                schema = int("schema"),
                objects = int("dump.objects"),
                fields = int("dump.fields"),
                modules = int("dump.modules"),
                routines = int("dump.routines"),
                indexedAt = meta["dump.indexedAt"]?.toLongOrNull() ?: 0,
                buildMillis = meta["dump.buildMillis"]?.toLongOrNull() ?: 0,
                indexBytes =
                    runCatching {
                        java.nio.file.Files
                            .size(file)
                    }.getOrDefault(0),
            )
        }

    fun objectByRef(ref: String): DumpObject? {
        val separator = ref.indexOf('.')
        if (separator <= 0) return null
        val kind = ref.substring(0, separator)
        val name = ref.substring(separator + 1)
        return synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT id, kind, name, synonym, comment, path FROM objects " +
                        "WHERE kind = ? AND name_lower = ? LIMIT 1",
                ).use { statement ->
                    statement.setString(1, kind)
                    statement.setString(2, name.lowercase(Locale.ROOT))
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.toObject() else null
                    }
                }
        }
    }

    fun objectById(id: Long): DumpObject? =
        synchronized(lock) {
            connection
                .prepareStatement("SELECT id, kind, name, synonym, comment, path FROM objects WHERE id = ?")
                .use { statement ->
                    statement.setLong(1, id)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.toObject() else null }
                }
        }

    fun objectByKindAndName(
        kind: String,
        name: String,
    ): DumpObject? =
        synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT id, kind, name, synonym, comment, path FROM objects " +
                        "WHERE kind = ? AND name_lower = ? LIMIT 1",
                ).use { statement ->
                    statement.setString(1, kind)
                    statement.setString(2, name.lowercase(Locale.ROOT))
                    statement.executeQuery().use { rows -> if (rows.next()) rows.toObject() else null }
                }
        }

    fun findObjects(
        query: String,
        limit: Int,
    ): List<DumpObject> {
        val pattern = likePattern(query)
        return synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT id, kind, name, synonym, comment, path FROM objects " +
                        "WHERE name_lower LIKE ? ESCAPE '\\' OR synonym_lower LIKE ? ESCAPE '\\' " +
                        "ORDER BY length(name) LIMIT ?",
                ).use { statement ->
                    statement.setString(1, pattern)
                    statement.setString(2, pattern)
                    statement.setInt(3, limit)
                    statement.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(rows.toObject()) }
                    }
                }
        }
    }

    fun objectNames(limit: Int): List<String> =
        synchronized(lock) {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM objects LIMIT $limit").use { rows ->
                    buildList { while (rows.next()) add(rows.getString("name")) }
                }
            }
        }

    fun findRoutines(
        query: String,
        limit: Int,
    ): List<RoutineHit> {
        val pattern = likePattern(query)
        return synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT r.id, r.module_id, r.name, r.kind, r.is_export, r.parameters, r.start_line, " +
                        "r.end_line, m.path, m.kind AS module_kind, m.object_id, o.kind AS owner_kind, o.name AS owner_name " +
                        "FROM routines r JOIN modules m ON m.id = r.module_id " +
                        "LEFT JOIN objects o ON o.id = m.object_id " +
                        "WHERE r.name_lower LIKE ? ESCAPE '\\' ORDER BY length(r.name) LIMIT ?",
                ).use { statement ->
                    statement.setString(1, pattern)
                    statement.setInt(2, limit)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    RoutineHit(
                                        routine =
                                            DumpRoutine(
                                                id = rows.getLong("id"),
                                                moduleId = rows.getLong("module_id"),
                                                name = rows.getString("name"),
                                                kind = rows.getString("kind"),
                                                export = rows.getInt("is_export") == 1,
                                                parameters = rows.getString("parameters").orEmpty(),
                                                startLine = rows.getInt("start_line"),
                                                endLine = rows.getInt("end_line"),
                                            ),
                                        modulePath = rows.getString("path"),
                                        moduleKind = rows.getString("module_kind"),
                                        ownerKind = rows.getString("owner_kind"),
                                        ownerName = rows.getString("owner_name"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }
    }

    fun findFields(
        query: String,
        limit: Int,
    ): List<FieldHit> {
        val pattern = likePattern(query)
        return synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT f.id, f.object_id, f.name, f.synonym, f.type, f.field_kind, f.tabular, " +
                        "o.kind AS owner_kind, o.name AS owner_name " +
                        "FROM fields f JOIN objects o ON o.id = f.object_id " +
                        "WHERE f.name_lower LIKE ? ESCAPE '\\' OR f.synonym_lower LIKE ? ESCAPE '\\' " +
                        "ORDER BY length(f.name) LIMIT ?",
                ).use { statement ->
                    statement.setString(1, pattern)
                    statement.setString(2, pattern)
                    statement.setInt(3, limit)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    FieldHit(
                                        field =
                                            DumpField(
                                                id = rows.getLong("id"),
                                                objectId = rows.getLong("object_id"),
                                                name = rows.getString("name"),
                                                synonym = rows.getString("synonym").orEmpty(),
                                                type = rows.getString("type").orEmpty(),
                                                fieldKind = rows.getString("field_kind"),
                                                tabular = rows.getString("tabular"),
                                            ),
                                        ownerKind = rows.getString("owner_kind"),
                                        ownerName = rows.getString("owner_name"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }
    }

    /** Пути всех модулей: grep по `.bsl` не обходит всю выгрузку. */
    fun modulePaths(): List<String> =
        synchronized(lock) {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT path FROM modules ORDER BY path").use { rows ->
                    buildList { while (rows.next()) add(rows.getString("path")) }
                }
            }
        }

    fun fields(objectId: Long): List<DumpField> =
        synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT id, object_id, name, synonym, type, field_kind, tabular FROM fields " +
                        "WHERE object_id = ? ORDER BY id",
                ).use { statement ->
                    statement.setLong(1, objectId)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    DumpField(
                                        id = rows.getLong("id"),
                                        objectId = rows.getLong("object_id"),
                                        name = rows.getString("name"),
                                        synonym = rows.getString("synonym").orEmpty(),
                                        type = rows.getString("type").orEmpty(),
                                        fieldKind = rows.getString("field_kind"),
                                        tabular = rows.getString("tabular"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    fun modules(objectId: Long): List<DumpModule> =
        synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT id, object_id, path, kind, lines, bytes FROM modules WHERE object_id = ? ORDER BY path",
                ).use { statement ->
                    statement.setLong(1, objectId)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    DumpModule(
                                        id = rows.getLong("id"),
                                        objectId = rows.getLong("object_id"),
                                        path = rows.getString("path"),
                                        kind = rows.getString("kind"),
                                        lines = rows.getInt("lines"),
                                        bytes = rows.getLong("bytes"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    fun module(id: Long): DumpModule? = synchronized(lock) { moduleInternal(id) }

    fun moduleByPath(path: String): DumpModule? =
        synchronized(lock) {
            connection
                .prepareStatement("SELECT id, object_id, path, kind, lines, bytes FROM modules WHERE path = ?")
                .use { statement ->
                    statement.setString(1, path)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.toModule() else null }
                }
        }

    fun moduleById(id: Long): DumpModule? = synchronized(lock) { moduleInternal(id) }

    fun routineById(id: Long): DumpRoutine? =
        synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT id, module_id, name, kind, is_export, parameters, start_line, end_line " +
                        "FROM routines WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, id)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.toRoutine() else null }
                }
        }

    /** Исходящие вызовы процедуры. */
    fun callsFrom(routineId: Long): List<CallEdge> =
        synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT c.id, c.callee, c.qualifier, c.kind, c.line, c.target_routine_id, c.external, " +
                        "tm.path AS target_module, tr.name AS target_routine, tr.module_id AS target_module_id " +
                        "FROM calls c LEFT JOIN routines tr ON tr.id = c.target_routine_id " +
                        "LEFT JOIN modules tm ON tm.id = tr.module_id " +
                        "WHERE c.routine_id = ? ORDER BY c.line",
                ).use { statement ->
                    statement.setLong(1, routineId)
                    statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toOutgoingCall()) } }
                }
        }

    /** Кто вызывает процедуру. */
    fun callsInto(routineId: Long): List<CallEdge> =
        synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT c.id, c.callee, c.qualifier, c.kind, c.line, c.target_routine_id, c.external, " +
                        "m.path AS source_module, r.name AS source_routine " +
                        "FROM calls c JOIN modules m ON m.id = c.module_id " +
                        "LEFT JOIN routines r ON r.id = c.routine_id " +
                        "WHERE c.target_routine_id = ? ORDER BY m.path, c.line",
                ).use { statement ->
                    statement.setLong(1, routineId)
                    statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toIncomingCall()) } }
                }
        }

    fun callsOfModule(moduleId: Long): List<CallEdge> =
        synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT c.id, c.callee, c.qualifier, c.kind, c.line, c.target_routine_id, c.external, " +
                        "tm.path AS target_module, tr.name AS target_routine, tr.module_id AS target_module_id " +
                        "FROM calls c LEFT JOIN routines tr ON tr.id = c.target_routine_id " +
                        "LEFT JOIN modules tm ON tm.id = tr.module_id " +
                        "WHERE c.module_id = ? ORDER BY c.line",
                ).use { statement ->
                    statement.setLong(1, moduleId)
                    statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toOutgoingCall()) } }
                }
        }

    fun callsIntoModule(moduleId: Long): List<CallEdge> =
        synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT c.id, c.callee, c.qualifier, c.kind, c.line, c.target_routine_id, c.external, " +
                        "m.path AS source_module, r.name AS source_routine " +
                        "FROM calls c JOIN routines tr ON tr.id = c.target_routine_id " +
                        "JOIN modules m ON m.id = c.module_id LEFT JOIN routines r ON r.id = c.routine_id " +
                        "WHERE tr.module_id = ? ORDER BY m.path, c.line",
                ).use { statement ->
                    statement.setLong(1, moduleId)
                    statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toIncomingCall()) } }
                }
        }

    /** Обращения к метаданным из процедуры. */
    fun metadataRefsForRoutine(routineId: Long): List<MetadataRefRow> =
        synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT object_name, collection, context, line FROM metadata_refs " +
                        "WHERE routine_id = ? ORDER BY line",
                ).use { statement ->
                    statement.setLong(1, routineId)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    MetadataRefRow(
                                        rows.getString("object_name"),
                                        rows.getString("collection"),
                                        rows.getString("context"),
                                        rows.getInt("line"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    fun metadataRefsForModule(moduleId: Long): List<MetadataRefRow> =
        synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT object_name, collection, context, line FROM metadata_refs " +
                        "WHERE module_id = ? ORDER BY line",
                ).use { statement ->
                    statement.setLong(1, moduleId)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    MetadataRefRow(
                                        rows.getString("object_name"),
                                        rows.getString("collection"),
                                        rows.getString("context"),
                                        rows.getInt("line"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    fun routines(moduleId: Long): List<DumpRoutine> =
        synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT id, module_id, name, kind, is_export, parameters, start_line, end_line " +
                        "FROM routines WHERE module_id = ? ORDER BY start_line",
                ).use { statement ->
                    statement.setLong(1, moduleId)
                    statement.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(rows.toRoutine()) }
                    }
                }
        }

    /** Процедура, в которую попадает строка модуля. */
    fun routineAt(
        path: String,
        line: Int,
    ): DumpRoutine? =
        synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT r.id, r.module_id, r.name, r.kind, r.is_export, r.parameters, r.start_line, r.end_line " +
                        "FROM routines r JOIN modules m ON m.id = r.module_id " +
                        "WHERE m.path = ? AND r.start_line <= ? AND r.end_line >= ? ORDER BY r.start_line LIMIT 1",
                ).use { statement ->
                    statement.setString(1, path)
                    statement.setInt(2, line)
                    statement.setInt(3, line)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.toRoutine() else null }
                }
        }

    fun objectForModule(path: String): DumpObject? =
        synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT o.id, o.kind, o.name, o.synonym, o.comment, o.path " +
                        "FROM modules m JOIN objects o ON o.id = m.object_id WHERE m.path = ? LIMIT 1",
                ).use { statement ->
                    statement.setString(1, path)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.toObject() else null }
                }
        }

    /** Сводка обращений к объекту метаданных из кода и запросов. */
    fun usageSummary(
        objectName: String,
        collections: List<String> = emptyList(),
    ): UsageSummary =
        synchronized(lock) {
            val needle = objectName.lowercase(Locale.ROOT)
            val filter =
                if (collections.isEmpty()) {
                    ""
                } else {
                    " AND r.collection IN (${collections.joinToString(",") { "?" }})"
                }

            val counts = mutableMapOf<String, Int>()
            connection
                .prepareStatement(
                    "SELECT r.context, COUNT(*) AS total FROM metadata_refs r " +
                        "WHERE r.object_name_lower = ?$filter GROUP BY r.context",
                ).use { statement ->
                    var index = 1
                    statement.setString(index++, needle)
                    collections.forEach { statement.setString(index++, it) }
                    statement.executeQuery().use { rows ->
                        while (rows.next()) counts[rows.getString("context")] = rows.getInt("total")
                    }
                }

            val readers = mutableListOf<UsageReader>()
            connection
                .prepareStatement(
                    "SELECT r.context, r.line, m.path, rt.name AS routine_name FROM metadata_refs r " +
                        "JOIN modules m ON m.id = r.module_id LEFT JOIN routines rt ON rt.id = r.routine_id " +
                        "WHERE r.object_name_lower = ?$filter ORDER BY m.path, r.line LIMIT 20",
                ).use { statement ->
                    var index = 1
                    statement.setString(index++, needle)
                    collections.forEach { statement.setString(index++, it) }
                    statement.executeQuery().use { rows ->
                        while (rows.next()) {
                            readers +=
                                UsageReader(
                                    modulePath = rows.getString("path"),
                                    routine = rows.getString("routine_name"),
                                    context = rows.getString("context"),
                                    line = rows.getInt("line"),
                                )
                        }
                    }
                }

            UsageSummary(
                objectName = objectName,
                total = counts.values.sum(),
                byContext = counts,
                readers = readers,
            )
        }

    fun entrypoints(
        kind: String? = null,
        metadata: String? = null,
        limit: Int = 100,
    ): List<EntryPointRow> {
        val conditions = mutableListOf<String>()
        val parameters = mutableListOf<String>()
        kind?.takeIf { it.isNotBlank() }?.let {
            conditions += "e.kind = ?"
            parameters += it
        }
        metadata?.takeIf { it.isNotBlank() }?.let {
            conditions +=
                "(e.owner = ? OR EXISTS(SELECT 1 FROM entrypoint_sources s WHERE s.entrypoint_id = e.id AND s.source = ?))"
            parameters += it
            parameters += it
        }
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        return synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT e.kind, e.name, e.owner, e.handler, e.event, e.path, " +
                        "(SELECT group_concat(s.source, char(31)) FROM entrypoint_sources s " +
                        "WHERE s.entrypoint_id = e.id) AS sources FROM entrypoints e $where " +
                        "ORDER BY e.kind, e.name LIMIT ?",
                ).use { statement ->
                    var index = 1
                    parameters.forEach { statement.setString(index++, it) }
                    statement.setInt(index, limit.coerceIn(1, 1000))
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    EntryPointRow(
                                        kind = rows.getString("kind"),
                                        name = rows.getString("name"),
                                        owner = rows.getString("owner"),
                                        handler = rows.getString("handler"),
                                        event = rows.getString("event"),
                                        path = rows.getString("path"),
                                        sources =
                                            rows
                                                .getString("sources")
                                                .orEmpty()
                                                .split('\u001f')
                                                .filter { it.isNotBlank() },
                                    ),
                                )
                            }
                        }
                    }
                }
        }
    }

    fun entrypointCounts(): Map<String, Int> =
        synchronized(lock) {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT kind, COUNT(*) AS total FROM entrypoints GROUP BY kind").use { rows ->
                    buildMap { while (rows.next()) put(rows.getString("kind"), rows.getInt("total")) }
                }
            }
        }

    /** Права ролей с фильтрами. */
    fun rights(
        role: String? = null,
        metadata: String? = null,
        condition: String? = null,
        limit: Int = 200,
    ): List<RoleRightRow> {
        val conditions = mutableListOf<String>()
        val parameters = mutableListOf<String>()
        role?.takeIf { it.isNotBlank() }?.let {
            conditions += "ro.name_lower = ?"
            parameters += it.lowercase(Locale.ROOT)
        }
        metadata?.takeIf { it.isNotBlank() }?.let {
            conditions += "rr.object_ref_lower = ?"
            parameters += it.lowercase(Locale.ROOT)
        }
        condition?.takeIf { it.isNotBlank() }?.let {
            conditions += "rr.condition LIKE ?"
            parameters += "%$it%"
        }
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        return synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT ro.name AS role, rr.object_ref, rr.right_name, rr.value, rr.condition " +
                        "FROM role_rights rr JOIN roles ro ON ro.id = rr.role_id $where " +
                        "ORDER BY ro.name, rr.object_ref, rr.right_name LIMIT ?",
                ).use { statement ->
                    var index = 1
                    parameters.forEach { statement.setString(index++, it) }
                    statement.setInt(index, limit.coerceIn(1, 1000))
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    RoleRightRow(
                                        role = rows.getString("role"),
                                        objectRef = rows.getString("object_ref"),
                                        right = rows.getString("right_name"),
                                        value = rows.getInt("value") == 1,
                                        condition = rows.getString("condition"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }
    }

    fun roleNames(): List<String> =
        synchronized(lock) {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM roles ORDER BY name").use { rows ->
                    buildList { while (rows.next()) add(rows.getString("name")) }
                }
            }
        }

    /** Кандидаты для поиска похожих процедур по общим вызовам. */
    fun routineIdsByCalls(
        names: List<String>,
        limit: Int = 1000,
    ): List<Long> {
        if (names.isEmpty()) return emptyList()
        val placeholders = names.joinToString(",") { "?" }
        return synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT DISTINCT routine_id FROM calls WHERE callee_lower IN ($placeholders) " +
                        "AND routine_id IS NOT NULL LIMIT ?",
                ).use { statement ->
                    var index = 1
                    names.forEach { statement.setString(index++, it.lowercase(Locale.ROOT)) }
                    statement.setInt(index, limit.coerceIn(1, 5000))
                    statement.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(rows.getLong(1)) }
                    }
                }
        }
    }

    /** Кандидаты для поиска похожих процедур по общим объектам метаданных. */
    fun routineIdsByMetadata(
        names: List<String>,
        limit: Int = 1000,
    ): List<Long> {
        if (names.isEmpty()) return emptyList()
        val placeholders = names.joinToString(",") { "?" }
        return synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT DISTINCT routine_id FROM metadata_refs WHERE object_name_lower IN ($placeholders) " +
                        "AND routine_id IS NOT NULL LIMIT ?",
                ).use { statement ->
                    var index = 1
                    names.forEach { statement.setString(index++, it.lowercase(Locale.ROOT)) }
                    statement.setInt(index, limit.coerceIn(1, 5000))
                    statement.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(rows.getLong(1)) }
                    }
                }
        }
    }

    override fun close() {
        runCatching { connection.close() }
    }

    private fun moduleInternal(id: Long): DumpModule? =
        connection
            .prepareStatement("SELECT id, object_id, path, kind, lines, bytes FROM modules WHERE id = ?")
            .use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use { rows -> if (rows.next()) rows.toModule() else null }
            }

    private fun readMeta(): Map<String, String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT key, value FROM meta").use { rows ->
                buildMap { while (rows.next()) put(rows.getString("key"), rows.getString("value").orEmpty()) }
            }
        }

    private fun java.sql.ResultSet.toObject() =
        DumpObject(
            id = getLong("id"),
            kind = getString("kind"),
            name = getString("name"),
            synonym = getString("synonym").orEmpty(),
            comment = getString("comment").orEmpty(),
            path = getString("path"),
        )

    private fun java.sql.ResultSet.toModule() =
        DumpModule(
            id = getLong("id"),
            objectId = getLong("object_id").takeIf { !wasNull() },
            path = getString("path"),
            kind = getString("kind"),
            lines = getInt("lines"),
            bytes = getLong("bytes"),
        )

    private fun java.sql.ResultSet.toOutgoingCall() =
        CallEdge(
            id = getLong("id"),
            callee = getString("callee"),
            qualifier = getString("qualifier"),
            kind = getString("kind"),
            line = getInt("line"),
            targetRoutineId = longOrNull("target_routine_id"),
            external = getString("external"),
            sourceModule = null,
            sourceRoutine = null,
            targetModule = getString("target_module"),
            targetRoutine = getString("target_routine"),
        )

    private fun java.sql.ResultSet.toIncomingCall() =
        CallEdge(
            id = getLong("id"),
            callee = getString("callee"),
            qualifier = getString("qualifier"),
            kind = getString("kind"),
            line = getInt("line"),
            targetRoutineId = longOrNull("target_routine_id"),
            external = getString("external"),
            sourceModule = getString("source_module"),
            sourceRoutine = getString("source_routine"),
            targetModule = null,
            targetRoutine = null,
        )

    private fun java.sql.ResultSet.longOrNull(column: String): Long? = (getObject(column) as? Number)?.toLong()

    private fun java.sql.ResultSet.toRoutine() =
        DumpRoutine(
            id = getLong("id"),
            moduleId = getLong("module_id"),
            name = getString("name"),
            kind = getString("kind"),
            export = getInt("is_export") == 1,
            parameters = getString("parameters").orEmpty(),
            startLine = getInt("start_line"),
            endLine = getInt("end_line"),
        )

    companion object {
        /**
         * Версия схемы индекса выгрузки: поднимать при изменении таблиц или правил разбора.
         * v7 — права ролей и условия RLS.
         */
        const val SCHEMA_VERSION = 7

        fun likePattern(query: String): String {
            val escaped =
                query
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_")
            return "%${escaped.lowercase(Locale.ROOT)}%"
        }
    }
}

/** Процедура вместе с модулем и объектом-владельцем. */
data class RoutineHit(
    val routine: DumpRoutine,
    val modulePath: String,
    val moduleKind: String,
    val ownerKind: String?,
    val ownerName: String?,
)

/** Реквизит вместе с объектом-владельцем. */
data class FieldHit(
    val field: DumpField,
    val ownerKind: String,
    val ownerName: String,
)

/** Одно обращение к объекту метаданных. */
data class UsageReader(
    val modulePath: String,
    val routine: String?,
    val context: String,
    val line: Int,
)

/** Сводка обращений к объекту: сколько и откуда. */
data class UsageSummary(
    val objectName: String,
    val total: Int,
    val byContext: Map<String, Int>,
    val readers: List<UsageReader>,
)

/** Точка входа в индекс. */
data class EntryPointRow(
    val kind: String,
    val name: String,
    val owner: String?,
    val handler: String?,
    val event: String?,
    val path: String?,
    val sources: List<String> = emptyList(),
)

/** Ребро графа вызовов. */
data class CallEdge(
    val id: Long,
    val callee: String,
    val qualifier: String?,
    val kind: String,
    val line: Int,
    val targetRoutineId: Long?,
    val external: String?,
    val sourceModule: String?,
    val sourceRoutine: String?,
    val targetModule: String?,
    val targetRoutine: String?,
)

/** Обращение к метаданным из конкретного места. */
data class MetadataRefRow(
    val objectName: String,
    val collection: String,
    val context: String,
    val line: Int,
)

/** Право роли в ответе инструмента. */
data class RoleRightRow(
    val role: String,
    val objectRef: String,
    val right: String,
    val value: Boolean,
    val condition: String?,
)
