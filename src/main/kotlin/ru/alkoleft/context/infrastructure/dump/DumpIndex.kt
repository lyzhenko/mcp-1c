/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.infrastructure.dump

import ru.alkoleft.context.business.entities.DumpField
import ru.alkoleft.context.business.entities.DumpInfo
import ru.alkoleft.context.business.entities.DumpModule
import ru.alkoleft.context.business.entities.DumpObject
import ru.alkoleft.context.business.entities.DumpRoutine
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
         * v2 — формы и макеты-ссылки индексируются, переводы строк нормализованы.
         */
        const val SCHEMA_VERSION = 2

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
