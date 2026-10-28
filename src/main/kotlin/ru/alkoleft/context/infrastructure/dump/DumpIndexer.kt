/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.infrastructure.dump

import io.github.oshai.kotlinlogging.KotlinLogging
import ru.alkoleft.context.business.entities.DumpInfo
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
        onProgress: (DumpBuildProgress) -> Unit = {},
    ): DumpInfo {
        val started = System.currentTimeMillis()
        val root = dump.toAbsolutePath().normalize()
        require(Files.isDirectory(root)) { "Каталог выгрузки не найден: $root" }

        Files.createDirectories(indexFile.toAbsolutePath().parent)
        val building = indexFile.resolveSibling("${indexFile.fileName}.building")
        Files.deleteIfExists(building)

        val configuration = readConfiguration(root)
        val xmlFiles = collectMetadataFiles(root)
        val objectDirs = mutableMapOf<String, Long>()
        val objects = mutableListOf<ParsedMetadata>()

        runParallel(xmlFiles, "метаданные", onProgress) { file ->
            val relative = relative(root, file)
            val element = MetadataXmlParser().parse(file, relative) ?: return@runParallel null
            element
        }.filterNotNull().forEach { objects += it }
        objects.forEachIndexed { index, element -> element.obj.let { objectDirs[it.path.removeSuffix(".xml")] = index + 1L } }

        val bslFiles = collectModuleFiles(root)
        val modules = mutableListOf<ScannedModule>()
        runParallel(bslFiles, "модули", onProgress) { file ->
            val relative = relative(root, file)
            val bytes = runCatching { Files.size(file) }.getOrDefault(0)
            val text =
                runCatching { DumpTextReader.read(file) }
                    .getOrElse { error ->
                        logger.warn { "Не прочитан модуль $relative: ${error.message}" }
                        return@runParallel null
                    }
            val ownerId = ownerId(relative, objectDirs)
            ScannedModule(
                path = relative,
                bytes = bytes,
                lines = DumpTextReader.countLines(text),
                kind = moduleKind(relative, ownerId, objectDirs),
                objectId = ownerId,
                routines = BslModuleScanner.scan(text),
            )
        }.filterNotNull().forEach { modules += it }

        val info =
            writeIndex(
                indexFile = building,
                root = root,
                configuration = configuration,
                objects = objects,
                modules = modules,
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

    private fun writeIndex(
        indexFile: Path,
        root: Path,
        configuration: ConfigurationInfo,
        objects: List<ParsedMetadata>,
        modules: List<ScannedModule>,
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
            connection
                .prepareStatement(
                    "INSERT INTO modules(id, object_id, path, kind, lines, bytes) VALUES (?, ?, ?, ?, ?, ?)",
                ).use { moduleStatement ->
                    connection
                        .prepareStatement(
                            "INSERT INTO routines(id, module_id, name, name_lower, kind, is_export, parameters, start_line, end_line) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        ).use { routineStatement ->
                            connection.autoCommit = false
                            for (module in modules) {
                                moduleId++
                                moduleStatement.setLong(1, moduleId)
                                module.objectId?.let { moduleStatement.setLong(2, it) }
                                    ?: moduleStatement.setNull(2, java.sql.Types.INTEGER)
                                moduleStatement.setString(3, module.path)
                                moduleStatement.setString(4, module.kind)
                                moduleStatement.setInt(5, module.lines)
                                moduleStatement.setLong(6, module.bytes)
                                moduleStatement.addBatch()

                                for (routine in module.routines) {
                                    routineId++
                                    routineCount++
                                    routineStatement.setLong(1, routineId)
                                    routineStatement.setLong(2, moduleId)
                                    routineStatement.setString(3, routine.name)
                                    routineStatement.setString(4, routine.name.lowercase(Locale.ROOT))
                                    routineStatement.setString(5, routine.kind)
                                    routineStatement.setInt(6, if (routine.export) 1 else 0)
                                    routineStatement.setString(7, routine.parameters)
                                    routineStatement.setInt(8, routine.startLine)
                                    routineStatement.setInt(9, routine.endLine)
                                    routineStatement.addBatch()
                                }
                            }
                            moduleStatement.executeBatch()
                            routineStatement.executeBatch()
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
                    "dump.path" to root.toString(),
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
                path = root.toString(),
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

    private fun <T> runParallel(
        files: List<Path>,
        phase: String,
        onProgress: (DumpBuildProgress) -> Unit,
        parse: (Path) -> T?,
    ): List<T> {
        if (files.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(parallelism)
        val done = AtomicInteger(0)
        return try {
            val tasks =
                files.map { file ->
                    Callable {
                        val result = runCatching { parse(file) }.getOrNull()
                        val processed = done.incrementAndGet()
                        if (processed % 128 == 0 || processed == files.size) {
                            onProgress(DumpBuildProgress(phase, processed, files.size))
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

    private fun collectMetadataFiles(root: Path): List<Path> =
        Files.walk(root, 2).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".xml", ignoreCase = true) }
                .filter { file ->
                    val relative = relative(root, file)
                    val name = file.fileName.toString()
                    relative.count { it == '/' } == 1 && name !in IGNORED_FILES
                }.toList()
        }

    private fun collectModuleFiles(root: Path): List<Path> =
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".bsl", ignoreCase = true) }
                .toList()
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

    private fun readConfiguration(root: Path): ConfigurationInfo {
        val file = root.resolve("Configuration.xml")
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
        val routines: List<ScannedRoutine>,
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
            )
    }
}
