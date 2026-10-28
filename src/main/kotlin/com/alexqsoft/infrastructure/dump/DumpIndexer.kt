/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.infrastructure.dump

import com.alexqsoft.business.entities.DumpInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/** Прогресс построения индекса для `status`. */
data class DumpBuildProgress(
    val phase: String,
    val done: Int,
    val total: Int,
)

/**
 * Построение SQLite-индекса выгрузки конфигурации.
 *
 * Индекс собирается в `<файл>.building` и подменяет готовый файл только после успешной записи:
 * прерванный разбор не оставляет полупустую базу. Метаданные и модули разбираются параллельно,
 * запись идёт одним потоком (SQLite не любит конкурирующих писателей).
 */
class DumpIndexer(
    private val parallelism: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8),
) {
    fun build(
        dump: Path,
        indexFile: Path,
        overlays: List<Path> = emptyList(),
        onProgress: (DumpBuildProgress) -> Unit = {},
    ): DumpInfo {
        val started = System.currentTimeMillis()
        val root = dump.toAbsolutePath().normalize()
        require(Files.isDirectory(root)) { "Каталог выгрузки не найден: $root" }
        val roots = listOf(root) + overlays.map { it.toAbsolutePath().normalize() }
        roots.drop(1).forEach { require(Files.isDirectory(it)) { "Каталог расширения не найден: $it" } }
        val sources = effectiveSources(roots)
        val rootLabel = roots.joinToString(";")

        Files.createDirectories(indexFile.toAbsolutePath().parent)
        val building = indexFile.resolveSibling("${indexFile.fileName}.building")
        Files.deleteIfExists(building)

        val configuration =
            readConfiguration(sources["Configuration.xml"] ?: throw IllegalStateException("нет Configuration.xml"))
        val xmlFiles = collectMetadataFiles(sources)
        val objectDirs = mutableMapOf<String, Long>()
        val objects = mutableListOf<ParsedMetadata>()

        runParallel(xmlFiles, "метаданные", onProgress) { source ->
            MetadataXmlParser().parse(source.file, source.relative) ?: return@runParallel null
        }.filterNotNull().forEach { objects += it }
        objects.forEachIndexed { index, element -> element.obj.let { objectDirs[it.path.removeSuffix(".xml")] = index + 1L } }

        val bslFiles = collectModuleFiles(sources)
        val modules = mutableListOf<ScannedModule>()
        runParallel(bslFiles, "модули", onProgress) { source ->
            val bytes = runCatching { Files.size(source.file) }.getOrDefault(0)
            val text =
                runCatching { DumpTextReader.read(source.file) }
                    .getOrElse { error ->
                        logger.warn { "Не прочитан модуль ${source.relative}: ${error.message}" }
                        return@runParallel null
                    }
            val ownerId = ownerId(source.relative, objectDirs)
            val parsed = BslParser.parse(text)
            ScannedModule(
                path = source.relative,
                bytes = bytes,
                lines = parsed.lines,
                kind = moduleKind(source.relative, ownerId, objectDirs),
                objectId = ownerId,
                routines = parsed.routines,
                metadataRefs = parsed.metadataRefs + parsed.routines.flatMap { it.metadataRefs },
                calls = parsed.calls + parsed.routines.flatMap { it.calls },
            )
        }.filterNotNull().forEach { modules += it }

        val entryPoints = collectEntryPoints(sources, onProgress)
        val roles = collectRoles(sources, onProgress)

        val info =
            writeIndex(
                indexFile = building,
                root = rootLabel,
                configuration = configuration,
                objects = objects,
                modules = modules,
                entryPoints = entryPoints,
                roles = roles,
                started = started,
            )

        Files.deleteIfExists(indexFile)
        try {
            Files.move(building, indexFile, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(building, indexFile, StandardCopyOption.REPLACE_EXISTING)
        }
        onProgress(DumpBuildProgress("готово", 1, 1))
        logger.info {
            "Индекс выгрузки готов за ${System.currentTimeMillis() - started} мс: " +
                "${info.objects} объектов, ${info.modules} модулей, ${info.routines} процедур"
        }
        return info.copy(indexBytes = runCatching { Files.size(indexFile) }.getOrDefault(0))
    }

    /**
     * Частично обновить индекс: перечитать изменившиеся и удалить исчезнувшие модули `.bsl`.
     *
     * Метаданные, формы, роли и точки входа при изменении требуют полной перестройки —
     * этим занимается [DumpStorage]. Возвращает число обновлённых модулей.
     */
    fun updateModules(
        indexFile: Path,
        roots: List<Path>,
        changed: Set<String>,
        deleted: Set<String> = emptySet(),
    ): Int {
        require(Files.isRegularFile(indexFile)) { "Индекс не найден: $indexFile" }
        val sources = effectiveSources(roots)
        var updated = 0
        DriverManager.getConnection("jdbc:sqlite:${indexFile.toAbsolutePath()}").use { connection ->
            val objectDirs = mutableMapOf<String, Long>()
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id, path FROM objects").use { rows ->
                    while (rows.next()) {
                        objectDirs[rows.getString("path").removeSuffix(".xml")] = rows.getLong("id")
                    }
                }
            }
            connection.autoCommit = false
            connection
                .prepareStatement("SELECT id, name FROM routines WHERE module_id = (SELECT id FROM modules WHERE path = ?)")
                .use { oldRoutinesStatement ->
                    connection
                        .prepareStatement(
                            "SELECT r.id FROM routines r JOIN modules m ON m.id = r.module_id WHERE m.path = ? AND r.name_lower = ?",
                        ).use { targetStatement ->
                            for (path in changed + deleted) {
                                val moduleId =
                                    connection.prepareStatement("SELECT id FROM modules WHERE path = ?").use { statement ->
                                        statement.setString(1, path)
                                        statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
                                    }
                                val oldRoutines =
                                    moduleId
                                        ?.let {
                                            oldRoutinesStatement.setString(1, path)
                                            oldRoutinesStatement.executeQuery().use { rows ->
                                                buildList {
                                                    while (rows.next()) add(rows.getLong(1) to rows.getString(2))
                                                }
                                            }
                                        }.orEmpty()

                                if (moduleId != null) {
                                    for (table in listOf("calls", "metadata_refs", "routines")) {
                                        connection.prepareStatement("DELETE FROM $table WHERE module_id = ?").use { statement ->
                                            statement.setLong(1, moduleId)
                                            statement.executeUpdate()
                                        }
                                    }
                                    connection.prepareStatement("DELETE FROM modules WHERE id = ?").use { statement ->
                                        statement.setLong(1, moduleId)
                                        statement.executeUpdate()
                                    }
                                }

                                val source = sources[path]
                                if (source == null || path in deleted) {
                                    for ((routineId, name) in oldRoutines) {
                                        connection
                                            .prepareStatement(
                                                "UPDATE calls SET target_routine_id = NULL, external = COALESCE(external, ?) " +
                                                    "WHERE target_routine_id = ?",
                                            ).use { statement ->
                                                statement.setString(1, "$path:$name")
                                                statement.setLong(2, routineId)
                                                statement.executeUpdate()
                                            }
                                    }
                                    continue
                                }

                                val text = DumpTextReader.read(source)
                                val parsed = BslParser.parse(text)
                                val ownerId = ownerId(path, objectDirs)
                                connection
                                    .prepareStatement(
                                        "INSERT INTO modules(object_id, path, kind, lines, bytes) VALUES (?, ?, ?, ?, ?)",
                                    ).use { statement ->
                                        ownerId?.let { statement.setLong(1, it) }
                                            ?: statement.setNull(1, java.sql.Types.INTEGER)
                                        statement.setString(2, path)
                                        statement.setString(3, moduleKind(path, ownerId, objectDirs))
                                        statement.setInt(4, parsed.lines)
                                        statement.setLong(5, runCatching { Files.size(source) }.getOrDefault(0))
                                        statement.executeUpdate()
                                    }
                                val newModuleId = lastInsertId(connection)
                                val routineIds = mutableListOf<Long>()
                                val routineIdsByName = mutableMapOf<String, Long>()
                                for (routine in parsed.routines) {
                                    connection
                                        .prepareStatement(
                                            "INSERT INTO routines(" +
                                                "module_id, name, name_lower, kind, is_export, parameters, " +
                                                "start_line, end_line) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                                        ).use { statement ->
                                            statement.setLong(1, newModuleId)
                                            statement.setString(2, routine.name)
                                            statement.setString(3, routine.name.lowercase(Locale.ROOT))
                                            statement.setString(4, routine.kind)
                                            statement.setInt(5, if (routine.export) 1 else 0)
                                            statement.setString(
                                                6,
                                                routine.parameters.joinToString(", ") {
                                                    if (it.hasDefault) "${it.name}=" else it.name
                                                },
                                            )
                                            statement.setInt(7, routine.startLine)
                                            statement.setInt(8, routine.endLine)
                                            statement.executeUpdate()
                                        }
                                    val id = lastInsertId(connection)
                                    routineIds += id
                                    routineIdsByName[routine.name.lowercase(Locale.ROOT)] = id
                                }
                                for (ref in parsed.metadataRefs + parsed.routines.flatMap { it.metadataRefs }) {
                                    val index = parsed.routines.indexOfFirst { ref.line in it.startLine..it.endLine }
                                    connection
                                        .prepareStatement(
                                            "INSERT INTO metadata_refs(" +
                                                "module_id, routine_id, object_name, object_name_lower, " +
                                                "collection, context, line) VALUES (?, ?, ?, ?, ?, ?, ?)",
                                        ).use { statement ->
                                            statement.setLong(1, newModuleId)
                                            if (index >= 0) {
                                                statement.setLong(2, routineIds[index])
                                            } else {
                                                statement.setNull(2, java.sql.Types.INTEGER)
                                            }
                                            statement.setString(3, ref.objectName)
                                            statement.setString(4, ref.objectName.lowercase(Locale.ROOT))
                                            statement.setString(5, ref.collection)
                                            statement.setString(6, ref.context)
                                            statement.setInt(7, ref.line)
                                            statement.executeUpdate()
                                        }
                                }
                                for (call in parsed.calls + parsed.routines.flatMap { it.calls }) {
                                    val routineIndex = parsed.routines.indexOfFirst { call.line in it.startLine..it.endLine }
                                    var target: Long? = null
                                    var external: String? = null
                                    if (call.kind == "new") {
                                        external = "Новый ${call.name}"
                                    } else if (!call.qualified) {
                                        target = routineIdsByName[call.name.lowercase(Locale.ROOT)]
                                    } else if (!call.qualifier.isNullOrBlank()) {
                                        targetStatement.setString(
                                            1,
                                            "CommonModules/${call.qualifier}/Ext/Module.bsl",
                                        )
                                        targetStatement.setString(2, call.name.lowercase(Locale.ROOT))
                                        target =
                                            targetStatement.executeQuery().use { rows ->
                                                if (rows.next()) rows.getLong(1) else null
                                            }
                                    }
                                    if (target == null && external == null) {
                                        external =
                                            if (call.qualified) {
                                                "${call.qualifier ?: ""}.${call.name}"
                                            } else {
                                                call.name
                                            }
                                    }
                                    connection
                                        .prepareStatement(
                                            "INSERT INTO calls(" +
                                                "module_id, routine_id, callee, callee_lower, qualifier, kind, " +
                                                "line, target_routine_id, external) " +
                                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                        ).use { statement ->
                                            statement.setLong(1, newModuleId)
                                            if (routineIndex >= 0) {
                                                statement.setLong(2, routineIds[routineIndex])
                                            } else {
                                                statement.setNull(2, java.sql.Types.INTEGER)
                                            }
                                            statement.setString(3, call.name)
                                            statement.setString(4, call.name.lowercase(Locale.ROOT))
                                            statement.setString(5, call.qualifier)
                                            statement.setString(6, call.kind)
                                            statement.setInt(7, call.line)
                                            target?.let { statement.setLong(8, it) }
                                                ?: statement.setNull(8, java.sql.Types.INTEGER)
                                            statement.setString(9, external)
                                            statement.executeUpdate()
                                        }
                                }
                                for ((oldId, name) in oldRoutines) {
                                    val newId = routineIdsByName[name.lowercase(Locale.ROOT)]
                                    connection
                                        .prepareStatement(
                                            "UPDATE calls SET target_routine_id = ?, external = ? " +
                                                "WHERE target_routine_id = ? AND module_id <> ?",
                                        ).use { statement ->
                                            if (newId != null) {
                                                statement.setLong(1, newId)
                                                statement.setString(2, null)
                                            } else {
                                                statement.setNull(1, java.sql.Types.INTEGER)
                                                statement.setString(2, "$path:$name")
                                            }
                                            statement.setLong(3, oldId)
                                            statement.setLong(4, newModuleId)
                                            statement.executeUpdate()
                                        }
                                }
                                updated++
                            }
                        }
                }
            connection.prepareStatement("INSERT OR REPLACE INTO meta(key, value) VALUES ('dump.indexedAt', ?)").use {
                it.setString(1, System.currentTimeMillis().toString())
                it.executeUpdate()
            }
            connection.commit()
        }
        return updated
    }

    private fun lastInsertId(connection: java.sql.Connection): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT last_insert_rowid()").use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }

    private fun writeIndex(
        indexFile: Path,
        root: String,
        configuration: ConfigurationInfo,
        objects: List<ParsedMetadata>,
        modules: List<ScannedModule>,
        entryPoints: List<ParsedEntryPoint>,
        roles: List<ParsedRole>,
        started: Long,
    ): DumpInfo {
        DriverManager.getConnection("jdbc:sqlite:${indexFile.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode = OFF")
                statement.execute("PRAGMA synchronous = OFF")
                SCHEMA.forEach(statement::execute)
            }

            var objectId = 0L
            var fieldId = 0L
            connection
                .prepareStatement(
                    "INSERT INTO objects(id, kind, name, name_lower, synonym, synonym_lower, comment, path) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { objectStatement ->
                    connection
                        .prepareStatement(
                            "INSERT INTO fields(id, object_id, name, name_lower, synonym, synonym_lower, type, field_kind, tabular) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        ).use { fieldStatement ->
                            connection.autoCommit = false
                            for (element in objects) {
                                objectId++
                                val obj = element.obj.copy(id = objectId)
                                objectStatement.setLong(1, obj.id)
                                objectStatement.setString(2, obj.kind)
                                objectStatement.setString(3, obj.name)
                                objectStatement.setString(4, obj.name.lowercase(Locale.ROOT))
                                objectStatement.setString(5, obj.synonym)
                                objectStatement.setString(6, obj.synonym.lowercase(Locale.ROOT))
                                objectStatement.setString(7, obj.comment)
                                objectStatement.setString(8, obj.path)
                                objectStatement.addBatch()

                                for (field in element.fields) {
                                    fieldId++
                                    fieldStatement.setLong(1, fieldId)
                                    fieldStatement.setLong(2, obj.id)
                                    fieldStatement.setString(3, field.name)
                                    fieldStatement.setString(4, field.name.lowercase(Locale.ROOT))
                                    fieldStatement.setString(5, field.synonym)
                                    fieldStatement.setString(6, field.synonym.lowercase(Locale.ROOT))
                                    fieldStatement.setString(7, field.type)
                                    fieldStatement.setString(8, field.fieldKind)
                                    fieldStatement.setString(9, field.tabular)
                                    fieldStatement.addBatch()
                                }
                            }
                            objectStatement.executeBatch()
                            fieldStatement.executeBatch()
                            connection.commit()
                            connection.autoCommit = true
                        }
                }

            var moduleId = 0L
            var routineId = 0L
            var routineCount = 0
            val pendingCalls = mutableListOf<PendingCall>()
            val moduleIdByPath = mutableMapOf<String, Long>()
            val routineIdsByModule = mutableMapOf<Long, Map<String, Long>>()
            connection
                .prepareStatement(
                    "INSERT INTO modules(id, object_id, path, kind, lines, bytes) VALUES (?, ?, ?, ?, ?, ?)",
                ).use { moduleStatement ->
                    connection
                        .prepareStatement(
                            "INSERT INTO routines(id, module_id, name, name_lower, kind, is_export, parameters, start_line, end_line) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        ).use { routineStatement ->
                            connection
                                .prepareStatement(
                                    "INSERT INTO metadata_refs(" +
                                        "module_id, routine_id, object_name, object_name_lower, " +
                                        "collection, context, line) VALUES (?, ?, ?, ?, ?, ?, ?)",
                                ).use { refStatement ->
                                    connection.autoCommit = false
                                    for (module in modules) {
                                        moduleId++
                                        moduleIdByPath[module.path] = moduleId
                                        moduleStatement.setLong(1, moduleId)
                                        module.objectId?.let { moduleStatement.setLong(2, it) }
                                            ?: moduleStatement.setNull(2, java.sql.Types.INTEGER)
                                        moduleStatement.setString(3, module.path)
                                        moduleStatement.setString(4, module.kind)
                                        moduleStatement.setInt(5, module.lines)
                                        moduleStatement.setLong(6, module.bytes)
                                        moduleStatement.addBatch()

                                        val routineIds = mutableListOf<Long>()
                                        val routineIdsByName = mutableMapOf<String, Long>()
                                        for (routine in module.routines) {
                                            routineId++
                                            routineCount++
                                            routineIds += routineId
                                            routineIdsByName[routine.name.lowercase(Locale.ROOT)] = routineId
                                            routineStatement.setLong(1, routineId)
                                            routineStatement.setLong(2, moduleId)
                                            routineStatement.setString(3, routine.name)
                                            routineStatement.setString(4, routine.name.lowercase(Locale.ROOT))
                                            routineStatement.setString(5, routine.kind)
                                            routineStatement.setInt(6, if (routine.export) 1 else 0)
                                            routineStatement.setString(
                                                7,
                                                routine.parameters.joinToString(", ") {
                                                    if (it.hasDefault) "${it.name}=" else it.name
                                                },
                                            )
                                            routineStatement.setInt(8, routine.startLine)
                                            routineStatement.setInt(9, routine.endLine)
                                            routineStatement.addBatch()
                                        }

                                        for (ref in module.metadataRefs) {
                                            val routineIndex =
                                                module.routines.indexOfFirst { ref.line in it.startLine..it.endLine }
                                            refStatement.setLong(1, moduleId)
                                            if (routineIndex >= 0) {
                                                refStatement.setLong(2, routineIds[routineIndex])
                                            } else {
                                                refStatement.setNull(2, java.sql.Types.INTEGER)
                                            }
                                            refStatement.setString(3, ref.objectName)
                                            refStatement.setString(4, ref.objectName.lowercase(Locale.ROOT))
                                            refStatement.setString(5, ref.collection)
                                            refStatement.setString(6, ref.context)
                                            refStatement.setInt(7, ref.line)
                                            refStatement.addBatch()
                                        }

                                        for (call in module.calls) {
                                            val routineIndex =
                                                module.routines.indexOfFirst { call.line in it.startLine..it.endLine }
                                            pendingCalls +=
                                                PendingCall(
                                                    moduleId = moduleId,
                                                    routineId = routineIds.getOrNull(routineIndex),
                                                    call = call,
                                                )
                                        }
                                        routineIdsByModule[moduleId] = routineIdsByName
                                    }
                                    moduleStatement.executeBatch()
                                    routineStatement.executeBatch()
                                    refStatement.executeBatch()
                                    connection.commit()
                                    connection.autoCommit = true
                                }
                        }
                }

            connection
                .prepareStatement(
                    "INSERT INTO calls(module_id, routine_id, callee, callee_lower, qualifier, kind, line, " +
                        "target_routine_id, external) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { callStatement ->
                    connection.autoCommit = false
                    for (pending in pendingCalls) {
                        val (targetRoutineId, external) =
                            resolveCall(pending, moduleIdByPath, routineIdsByModule)
                        callStatement.setLong(1, pending.moduleId)
                        pending.routineId?.let { callStatement.setLong(2, it) }
                            ?: callStatement.setNull(2, java.sql.Types.INTEGER)
                        callStatement.setString(3, pending.call.name)
                        callStatement.setString(4, pending.call.name.lowercase(Locale.ROOT))
                        callStatement.setString(5, pending.call.qualifier)
                        callStatement.setString(6, pending.call.kind)
                        callStatement.setInt(7, pending.call.line)
                        targetRoutineId?.let { callStatement.setLong(8, it) }
                            ?: callStatement.setNull(8, java.sql.Types.INTEGER)
                        callStatement.setString(9, external)
                        callStatement.addBatch()
                    }
                    callStatement.executeBatch()
                    connection.commit()
                    connection.autoCommit = true
                }

            connection
                .prepareStatement(
                    "INSERT INTO roles(name, name_lower, path) VALUES (?, ?, ?)",
                ).use { roleStatement ->
                    connection
                        .prepareStatement(
                            "INSERT INTO role_rights(role_id, object_ref, object_ref_lower, right_name, value, condition) " +
                                "VALUES (?, ?, ?, ?, ?, ?)",
                        ).use { rightStatement ->
                            connection.autoCommit = false
                            var roleId = 0L
                            for (role in roles) {
                                roleId++
                                roleStatement.setString(1, role.name)
                                roleStatement.setString(2, role.name.lowercase(Locale.ROOT))
                                roleStatement.setString(3, role.path)
                                roleStatement.executeUpdate()
                                for (right in role.rights) {
                                    rightStatement.setLong(1, roleId)
                                    rightStatement.setString(2, right.objectRef)
                                    rightStatement.setString(3, right.objectRef.lowercase(Locale.ROOT))
                                    rightStatement.setString(4, right.right)
                                    rightStatement.setInt(5, if (right.value) 1 else 0)
                                    rightStatement.setString(6, right.condition)
                                    rightStatement.addBatch()
                                }
                            }
                            rightStatement.executeBatch()
                            connection.commit()
                            connection.autoCommit = true
                        }
                }

            connection
                .prepareStatement(
                    "INSERT INTO entrypoints(kind, name, owner, handler, event, path) VALUES (?, ?, ?, ?, ?, ?)",
                ).use { entryStatement ->
                    connection
                        .prepareStatement(
                            "INSERT INTO entrypoint_sources(entrypoint_id, source) VALUES (?, ?)",
                        ).use { sourceStatement ->
                            connection.autoCommit = false
                            for (entryPoint in entryPoints) {
                                entryStatement.setString(1, entryPoint.kind)
                                entryStatement.setString(2, entryPoint.name)
                                entryStatement.setString(3, entryPoint.owner)
                                entryStatement.setString(4, entryPoint.handler)
                                entryStatement.setString(5, entryPoint.event)
                                entryStatement.setString(6, entryPoint.path)
                                entryStatement.executeUpdate()

                                val entryPointId =
                                    connection.createStatement().use { statement ->
                                        statement.executeQuery("SELECT last_insert_rowid()").use { rows ->
                                            rows.next()
                                            rows.getLong(1)
                                        }
                                    }
                                for (source in entryPoint.sources) {
                                    sourceStatement.setLong(1, entryPointId)
                                    sourceStatement.setString(2, source)
                                    sourceStatement.addBatch()
                                }
                            }
                            sourceStatement.executeBatch()
                            connection.commit()
                            connection.autoCommit = true
                        }
                }

            connection.createStatement().use { statement ->
                INDICES.forEach(statement::execute)
            }

            val indexedAt = System.currentTimeMillis()
            val fields = objects.sumOf { it.fields.size }
            putMeta(
                connection,
                mapOf(
                    "schema" to DumpIndex.SCHEMA_VERSION.toString(),
                    "dump.path" to root,
                    "dump.configuration" to configuration.name,
                    "dump.version" to configuration.version,
                    "dump.objects" to objects.size.toString(),
                    "dump.fields" to fields.toString(),
                    "dump.modules" to modules.size.toString(),
                    "dump.routines" to routineCount.toString(),
                    "dump.indexedAt" to indexedAt.toString(),
                    "dump.buildMillis" to (indexedAt - started).toString(),
                ),
            )

            return DumpInfo(
                path = root,
                configuration = configuration.name,
                version = configuration.version,
                objects = objects.size,
                fields = fields,
                modules = modules.size,
                routines = routineCount,
                indexedAt = indexedAt,
                buildMillis = indexedAt - started,
            )
        }
    }

    private fun putMeta(
        connection: Connection,
        values: Map<String, String>,
    ) {
        connection.prepareStatement("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)").use { statement ->
            for ((key, value) in values) {
                statement.setString(1, key)
                statement.setString(2, value)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun <I, T> runParallel(
        items: List<I>,
        phase: String,
        onProgress: (DumpBuildProgress) -> Unit,
        parse: (I) -> T?,
    ): List<T> {
        if (items.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(parallelism)
        val done = AtomicInteger(0)
        return try {
            val tasks =
                items.map { item ->
                    Callable {
                        val result = runCatching { parse(item) }.getOrNull()
                        val processed = done.incrementAndGet()
                        if (processed % 128 == 0 || processed == items.size) {
                            onProgress(DumpBuildProgress(phase, processed, items.size))
                        }
                        result
                    }
                }
            pool.invokeAll(tasks).mapNotNull { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.MINUTES)
        }
    }

    private fun effectiveSources(roots: List<Path>): Map<String, Path> {
        val result = LinkedHashMap<String, Path>()
        for (root in roots) {
            Files.walk(root).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .forEach { file -> result[relative(root, file)] = file }
            }
        }
        return result
    }

    private fun collectMetadataFiles(sources: Map<String, Path>): List<SourceFile> =
        sources.entries
            .filter { (relative, _) ->
                relative.count { it == '/' } == 1 &&
                    relative.endsWith(".xml", ignoreCase = true) &&
                    relative.substringAfterLast('/') !in IGNORED_FILES &&
                    relative.substringBefore('/') !in ENTRYPOINT_SECTIONS
            }.map { SourceFile(it.key, it.value) }

    private fun collectModuleFiles(sources: Map<String, Path>): List<SourceFile> =
        sources.entries
            .filter { it.key.endsWith(".bsl", ignoreCase = true) }
            .map { SourceFile(it.key, it.value) }

    private fun collectRoles(
        sources: Map<String, Path>,
        onProgress: (DumpBuildProgress) -> Unit,
    ): List<ParsedRole> {
        val files =
            sources.entries
                .filter { it.key.startsWith("Roles/") && it.key.endsWith("/Ext/Rights.xml") }
                .map { SourceFile(it.key, it.value) }
        return runParallel(files, "роли", onProgress) { source ->
            val roleName = source.relative.split('/').getOrNull(1) ?: return@runParallel null
            RightsParser().parse(source.file, source.relative, roleName)
        }.filterNotNull()
    }

    private fun collectEntryPoints(
        sources: Map<String, Path>,
        onProgress: (DumpBuildProgress) -> Unit,
    ): List<ParsedEntryPoint> {
        val parser = EntryPointParser()
        val formFiles =
            sources.entries
                .filter { (relative, _) ->
                    relative.endsWith("/Form.xml") &&
                        (relative.startsWith("CommonForms/") || relative.contains("/Forms/"))
                }.map { SourceFile(it.key, it.value) }
        val forms =
            runParallel(formFiles, "формы", onProgress) { source ->
                parser.parseForm(source.file, source.relative)
            }.flatten()

        val result = forms.toMutableList()
        for ((prefix, parserAction) in listOf<Pair<String, (SourceFile) -> ParsedEntryPoint?>>(
            "EventSubscriptions/" to { source -> parser.parseSubscription(source.file, source.relative) },
            "ScheduledJobs/" to { source -> parser.parseJob(source.file, source.relative) },
        )) {
            sources.entries
                .filter { it.key.startsWith(prefix) && it.key.count { char -> char == '/' } == 1 }
                .map { SourceFile(it.key, it.value) }
                .forEach { source -> parserAction(source)?.let { result += it } }
        }
        for ((directory, kind) in listOf("HTTPServices" to "http", "WebServices" to "web")) {
            sources.entries
                .filter { it.key.startsWith("$directory/") && it.key.count { char -> char == '/' } == 1 }
                .map { SourceFile(it.key, it.value) }
                .forEach { source -> result += parser.parseService(source.file, source.relative, kind) }
        }
        return result
    }

    private fun ownerId(
        relative: String,
        objectDirs: Map<String, Long>,
    ): Long? {
        val parts = relative.split('/')
        for (size in (parts.size - 1) downTo 2) {
            val prefix = parts.take(size).joinToString("/")
            objectDirs[prefix]?.let { return it }
        }
        return null
    }

    private fun moduleKind(
        relative: String,
        ownerId: Long?,
        objectDirs: Map<String, Long>,
    ): String {
        val parts = relative.split('/')
        var ownerPrefix = ""
        for (size in (parts.size - 1) downTo 2) {
            val prefix = parts.take(size).joinToString("/")
            if (objectDirs.containsKey(prefix)) {
                ownerPrefix = prefix
                break
            }
        }
        val suffix = relative.removePrefix(ownerPrefix).removePrefix("/")
        return when {
            suffix.contains("Forms/") -> "Form." + suffix.substringAfter("Forms/").substringBefore('/')
            suffix.contains("Commands/") -> "Command." + suffix.substringAfter("Commands/").substringBefore('/')
            suffix.endsWith("Ext/ObjectModule.bsl") -> "ObjectModule"
            suffix.endsWith("Ext/ManagerModule.bsl") -> "ManagerModule"
            suffix.endsWith("Ext/RecordSetModule.bsl") -> "RecordSetModule"
            suffix.endsWith("Ext/ValueManagerModule.bsl") -> "ValueManagerModule"
            suffix.endsWith("Ext/CommandModule.bsl") -> "CommandModule"
            suffix.endsWith("Ext/Module.bsl") -> "Module"
            else -> "Module"
        }
    }

    private fun resolveCall(
        pending: PendingCall,
        moduleIdByPath: Map<String, Long>,
        routineIdsByModule: Map<Long, Map<String, Long>>,
    ): Pair<Long?, String?> {
        val call = pending.call
        val lower = call.name.lowercase(Locale.ROOT)
        if (call.kind == "new") return null to "Новый ${call.name}"
        if (!call.qualified) {
            val target = routineIdsByModule[pending.moduleId]?.get(lower)
            return target to if (target == null) call.name else null
        }
        val qualifier = call.qualifier
        if (!qualifier.isNullOrBlank()) {
            val targetModule = moduleIdByPath["CommonModules/$qualifier/Ext/Module.bsl"]
            if (targetModule != null) {
                val target = routineIdsByModule[targetModule]?.get(lower)
                if (target != null) return target to null
            }
            return null to "$qualifier.${call.name}"
        }
        return null to call.name
    }

    private fun readConfiguration(file: Path): ConfigurationInfo {
        if (!Files.isRegularFile(file)) return ConfigurationInfo("", "")
        val text = runCatching { DumpTextReader.read(file) }.getOrDefault("")
        val name =
            Regex("""<Name>([^<]+)</Name>""")
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.trim()
                .orEmpty()
        val version =
            Regex("""<Version>([^<]+)</Version>""")
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.trim()
                .orEmpty()
        return ConfigurationInfo(name, version)
    }

    private fun relative(
        root: Path,
        file: Path,
    ): String = root.relativize(file).toString().replace('\\', '/')

    private data class SourceFile(
        val relative: String,
        val file: Path,
    )

    private data class ConfigurationInfo(
        val name: String,
        val version: String,
    )

    private data class ScannedModule(
        val path: String,
        val bytes: Long,
        val lines: Int,
        val kind: String,
        val objectId: Long?,
        val routines: List<ParsedRoutine>,
        val metadataRefs: List<ParsedMetadataRef>,
        val calls: List<ParsedCall>,
    )

    private data class PendingCall(
        val moduleId: Long,
        val routineId: Long?,
        val call: ParsedCall,
    )

    companion object {
        private val IGNORED_FILES =
            setOf(
                "Configuration.xml",
                "ConfigDumpInfo.xml",
                "Rights.xml",
                "Form.xml",
                "Help.xml",
                "Template.xml",
                "Picture.xml",
                "Package.xml",
                "CommandInterface.xml",
                "ClientApplicationInterface.xml",
                "MainSectionCommandInterface.xml",
                "HomePageWorkArea.xml",
                "Logo.xml",
                "Splash.xml",
                "MainSectionPicture.xml",
                "Schedule.xml",
                "Flowchart.xml",
                "DCS.xml",
            )

        private val ENTRYPOINT_SECTIONS =
            setOf("EventSubscriptions", "ScheduledJobs", "HTTPServices", "WebServices")

        private val SCHEMA =
            listOf(
                "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
                "CREATE TABLE objects(" +
                    "id INTEGER PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL, name_lower TEXT NOT NULL, " +
                    "synonym TEXT, synonym_lower TEXT, comment TEXT, path TEXT NOT NULL)",
                "CREATE TABLE fields(" +
                    "id INTEGER PRIMARY KEY, object_id INTEGER NOT NULL, name TEXT NOT NULL, name_lower TEXT NOT NULL, " +
                    "synonym TEXT, synonym_lower TEXT, type TEXT, field_kind TEXT NOT NULL, tabular TEXT)",
                "CREATE TABLE modules(" +
                    "id INTEGER PRIMARY KEY, object_id INTEGER, path TEXT NOT NULL, kind TEXT NOT NULL, " +
                    "lines INTEGER, bytes INTEGER)",
                "CREATE TABLE routines(" +
                    "id INTEGER PRIMARY KEY, module_id INTEGER NOT NULL, name TEXT NOT NULL, name_lower TEXT NOT NULL, " +
                    "kind TEXT NOT NULL, is_export INTEGER, parameters TEXT, start_line INTEGER, end_line INTEGER)",
                "CREATE TABLE metadata_refs(" +
                    "id INTEGER PRIMARY KEY, module_id INTEGER NOT NULL, routine_id INTEGER, object_name TEXT NOT NULL, " +
                    "object_name_lower TEXT NOT NULL, collection TEXT NOT NULL, context TEXT NOT NULL, line INTEGER NOT NULL)",
                "CREATE TABLE entrypoints(" +
                    "id INTEGER PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL, owner TEXT, handler TEXT, event TEXT, path TEXT)",
                "CREATE TABLE entrypoint_sources(" +
                    "id INTEGER PRIMARY KEY, entrypoint_id INTEGER NOT NULL, source TEXT NOT NULL)",
                "CREATE TABLE calls(" +
                    "id INTEGER PRIMARY KEY, module_id INTEGER NOT NULL, routine_id INTEGER, callee TEXT NOT NULL, " +
                    "callee_lower TEXT NOT NULL, qualifier TEXT, kind TEXT, line INTEGER, " +
                    "target_routine_id INTEGER, external TEXT)",
                "CREATE TABLE roles(" +
                    "id INTEGER PRIMARY KEY, name TEXT NOT NULL, name_lower TEXT NOT NULL, path TEXT)",
                "CREATE TABLE role_rights(" +
                    "id INTEGER PRIMARY KEY, role_id INTEGER NOT NULL, object_ref TEXT NOT NULL, " +
                    "object_ref_lower TEXT NOT NULL, right_name TEXT NOT NULL, value INTEGER, condition TEXT)",
            )

        private val INDICES =
            listOf(
                "CREATE INDEX idx_objects_name ON objects(name_lower)",
                "CREATE INDEX idx_objects_kind ON objects(kind)",
                "CREATE INDEX idx_fields_object ON fields(object_id)",
                "CREATE INDEX idx_fields_name ON fields(name_lower)",
                "CREATE INDEX idx_modules_object ON modules(object_id)",
                "CREATE INDEX idx_modules_path ON modules(path)",
                "CREATE INDEX idx_routines_module ON routines(module_id)",
                "CREATE INDEX idx_routines_name ON routines(name_lower)",
                "CREATE INDEX idx_refs_object ON metadata_refs(object_name_lower)",
                "CREATE INDEX idx_refs_module ON metadata_refs(module_id)",
                "CREATE INDEX idx_entrypoints_kind ON entrypoints(kind)",
                "CREATE INDEX idx_entrypoints_owner ON entrypoints(owner)",
                "CREATE INDEX idx_entrypoint_sources_source ON entrypoint_sources(source)",
                "CREATE INDEX idx_calls_module ON calls(module_id)",
                "CREATE INDEX idx_calls_routine ON calls(routine_id)",
                "CREATE INDEX idx_calls_target ON calls(target_routine_id)",
                "CREATE INDEX idx_calls_name ON calls(callee_lower)",
                "CREATE INDEX idx_roles_name ON roles(name_lower)",
                "CREATE INDEX idx_role_rights_role ON role_rights(role_id)",
                "CREATE INDEX idx_role_rights_object ON role_rights(object_ref_lower)",
            )
    }
}
