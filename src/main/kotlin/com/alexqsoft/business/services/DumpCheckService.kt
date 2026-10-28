/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.business.services

import com.alexqsoft.business.entities.MethodDefinition
import com.alexqsoft.exceptions.DefinitionNotFoundException
import com.alexqsoft.exceptions.InvalidSearchQueryException
import com.alexqsoft.infrastructure.dump.BslParser
import com.alexqsoft.infrastructure.dump.DumpIndex
import com.alexqsoft.infrastructure.dump.DumpStorage
import com.alexqsoft.infrastructure.dump.DumpTextReader
import com.alexqsoft.infrastructure.dump.ParsedMetadataRef
import com.alexqsoft.infrastructure.dump.ParsedRoutine
import com.alexqsoft.infrastructure.search.PlatformCatalog
import java.util.Locale

/** Замечание проверки модуля. */
data class CheckProblem(
    val line: Int,
    val severity: String,
    val code: String,
    val message: String,
    val hint: String? = null,
)

/** Краткая информация о процедуре в ответе проверки. */
data class CheckRoutine(
    val name: String,
    val kind: String,
    val export: Boolean,
    val parameters: List<String>,
    val startLine: Int,
    val endLine: Int,
)

/** Обращение к метаданным, найденное в проверяемом модуле. */
data class CheckMetadataAccess(
    val collection: String,
    val name: String,
    val context: String,
    val line: Int,
)

/** Результат проверки модуля или черновика. */
data class CheckResult(
    val path: String,
    val source: String,
    val lines: Int,
    val routines: List<CheckRoutine>,
    val calls: Int,
    val metadataAccesses: List<CheckMetadataAccess>,
    val problems: List<CheckProblem>,
    val notes: List<String>,
)

/**
 * Проверка модуля BSL без запуска 1С.
 *
 * Проверки намеренно консервативные: неизвестная процедура — ошибка, но только если справка
 * платформы загружена (иначе список глобальных методов не с чем сверять); число аргументов
 * проверяется у своих процедур, методов общих модулей и глобальных методов платформы; обращения
 * к метаданным — только когда открыта выгрузка.
 */
class DumpCheckService(
    private val storage: DumpStorage,
    private val platform: () -> PlatformCatalog?,
) {
    fun check(
        path: String? = null,
        id: Long? = null,
        text: String? = null,
    ): CheckResult {
        val index = if (storage.currentPath() != null) storage.awaitReady() else null
        val modulePath = path?.replace('\\', '/')?.trimStart('/') ?: "draft.bsl"
        val moduleText: String
        val source: String
        if (text != null) {
            moduleText = text
            source = "text"
        } else {
            val openIndex = index ?: throw InvalidSearchQueryException("выгрузка не открыта: передайте text или откройте dump")
            val module =
                when {
                    id != null -> openIndex.module(id) ?: throw DefinitionNotFoundException("модуль #$id")
                    !path.isNullOrBlank() ->
                        openIndex.moduleByPath(modulePath)
                            ?: throw DefinitionNotFoundException("модуль «$modulePath»")

                    else -> throw InvalidSearchQueryException("укажите path, id или text")
                }
            val file = storage.file(module.path) ?: throw DefinitionNotFoundException("файл «${module.path}»")
            moduleText = DumpTextReader.read(file)
            source = "file"
        }

        val parsed = BslParser.parse(moduleText)
        val problems = mutableListOf<CheckProblem>()
        val notes = mutableListOf<String>()
        val catalog = runCatching { platform() }.getOrNull()
        val isFormModule = modulePath.contains("/Ext/Form/Module.bsl") || modulePath.contains("/Ext/CommandModule.bsl")
        val routinesByName = parsed.routines.groupBy { it.name.lowercase(Locale.ROOT) }

        for ((name, routines) in routinesByName) {
            if (routines.size > 1) {
                problems +=
                    CheckProblem(
                        line = routines[1].startLine,
                        severity = "error",
                        code = "duplicate_routine",
                        message = "Процедура «${routines[1].name}» объявлена несколько раз",
                        hint = "Переименуйте или удалите дубль.",
                    )
            }
        }

        val allCalls = parsed.calls + parsed.routines.flatMap { it.calls }
        for (call in parsed.calls) {
            checkCall(call, parsed.routines, index, catalog, problems)
        }
        for (routine in parsed.routines) {
            for (call in routine.calls) {
                checkCall(call, parsed.routines, index, catalog, problems)
            }
        }

        for (routine in parsed.routines) {
            if (routine.kind == "function" && !routine.hasReturn) {
                problems +=
                    CheckProblem(
                        line = routine.startLine,
                        severity = "warning",
                        code = "function_without_return",
                        message = "Функция «${routine.name}» не содержит «Возврат»",
                        hint = "Функция должна вернуть значение или станьте процедурой.",
                    )
            }
            if (!isFormModule) {
                for (unused in routine.unused.filter { it.kind == "parameter" }) {
                    problems +=
                        CheckProblem(
                            line = unused.line,
                            severity = "warning",
                            code = "unused_parameter",
                            message = "Параметр «${unused.name}» не используется",
                            hint = "Уберите параметр или используйте его.",
                        )
                }
            }
            for (unused in routine.unused.filter { it.kind == "local" }) {
                problems +=
                    CheckProblem(
                        line = unused.line,
                        severity = "warning",
                        code = "unused_variable",
                        message = "Переменная «${unused.name}» не используется",
                        hint = "Уберите объявление или используйте переменную.",
                    )
            }
        }

        val accesses = parsed.metadataRefs + parsed.routines.flatMap { it.metadataRefs }
        if (index == null) {
            notes += "Выгрузка не открыта: обращения к метаданным не проверялись."
        } else {
            for (ref in accesses) {
                checkMetadataRef(ref, index, problems)
            }
        }
        if (catalog == null) {
            notes +=
                "Справка платформы не загружена: неизвестные процедуры и число аргументов " +
                "у методов платформы не проверялись."
        }

        return CheckResult(
            path = modulePath,
            source = source,
            lines = parsed.lines,
            routines =
                parsed.routines.map {
                    CheckRoutine(
                        name = it.name,
                        kind = it.kind,
                        export = it.export,
                        parameters = it.parameters.map { parameter -> parameter.name },
                        startLine = it.startLine,
                        endLine = it.endLine,
                    )
                },
            calls = allCalls.size,
            metadataAccesses =
                accesses
                    .distinctBy { "${it.collection}.${it.objectName}:${it.context}:${it.line}" }
                    .map { CheckMetadataAccess(it.collection, it.objectName, it.context, it.line) }
                    .take(100),
            problems = problems.sortedBy { it.line }.take(200),
            notes = notes,
        )
    }

    private fun checkCall(
        call: com.alexqsoft.infrastructure.dump.ParsedCall,
        routines: List<ParsedRoutine>,
        index: DumpIndex?,
        catalog: PlatformCatalog?,
        problems: MutableList<CheckProblem>,
    ) {
        if (call.kind == "new") return
        val lower = call.name.lowercase(Locale.ROOT)

        if (!call.qualified) {
            routines.firstOrNull { it.name.lowercase(Locale.ROOT) == lower }?.let { routine ->
                checkRange(
                    call.line,
                    routine.name,
                    call.arguments,
                    routine.requiredParameters(),
                    routine.parameters.size,
                    problems,
                    code = "argument_count",
                )
                return
            }
        } else {
            if (index != null && !call.qualifier.isNullOrBlank()) {
                val commonModulePath = "CommonModules/${call.qualifier}/Ext/Module.bsl"
                val module = index.moduleByPath(commonModulePath)
                if (module != null) {
                    val routine = index.routines(module.id).firstOrNull { it.name.equals(call.name, ignoreCase = true) }
                    if (routine != null) {
                        val bounds = parameterBounds(routine.parameters)
                        checkRange(call.line, routine.name, call.arguments, bounds.first, bounds.second, problems, "argument_count")
                    }
                }
            }
            return
        }

        if (catalog == null) return
        val method = catalog.findMethod(call.name)
        if (method == null) {
            problems +=
                CheckProblem(
                    line = call.line,
                    severity = "warning",
                    code = "unknown_procedure",
                    message = "Процедура или функция «${call.name}» не найдена",
                    hint = "Проверьте имя: в модуле и справке платформы такого метода нет.",
                )
            return
        }
        checkPlatformArguments(call.line, method, call.arguments, problems)
    }

    private fun checkPlatformArguments(
        line: Int,
        method: MethodDefinition,
        arguments: Int,
        problems: MutableList<CheckProblem>,
    ) {
        if (method.signatures.isEmpty()) return
        val maximum = method.signatures.maxOf { it.parameters.size }
        if (arguments <= maximum) return
        problems +=
            CheckProblem(
                line = line,
                severity = "warning",
                code = "platform_arguments",
                message = "«${method.name}» получает $arguments аргументов, по справке — не больше $maximum",
                hint = "Сверьтесь с сигнатурой метода платформы.",
            )
    }

    private fun checkRange(
        line: Int,
        name: String,
        arguments: Int,
        required: Int,
        total: Int,
        problems: MutableList<CheckProblem>,
        code: String,
    ) {
        if (arguments in required..total) return
        val expected = if (required == total) "$total" else "от $required до $total"
        problems +=
            CheckProblem(
                line = line,
                severity = "error",
                code = code,
                message = "«$name» ожидает $expected аргументов, передано $arguments",
                hint = "Проверьте список параметров процедуры.",
            )
    }

    private fun parameterBounds(parameters: String): Pair<Int, Int> {
        if (parameters.isBlank()) return 0 to 0
        val parts = parameters.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val required = parts.count { !it.contains("=") }
        return required to parts.size
    }

    private fun checkMetadataRef(
        ref: ParsedMetadataRef,
        index: DumpIndex,
        problems: MutableList<CheckProblem>,
    ) {
        val kind = objectKind(ref.collection) ?: return
        if (index.objectByKindAndName(kind, ref.objectName) != null) return
        val suggestions =
            index
                .findObjects(ref.objectName, 50)
                .filter { it.kind == kind }
                .map { it.name }
                .take(3)
        problems +=
            CheckProblem(
                line = ref.line,
                severity = "warning",
                code = "missing_metadata",
                message = "В конфигурации нет объекта «$kind.${ref.objectName}»",
                hint = suggestions.takeIf { it.isNotEmpty() }?.joinToString(", ") { "Похоже: $it" },
            )
    }

    private fun objectKind(collection: String): String? =
        when (collection.lowercase(Locale.ROOT)) {
            "справочники", "справочник", "catalogs", "catalog" -> "Catalog"
            "документы", "документ", "documents", "document" -> "Document"
            "регистрысведений", "регистрсведений" -> "InformationRegister"
            "регистрынакопления", "регистрнакопления" -> "AccumulationRegister"
            "регистрыбухгалтерии", "регистрбухгалтерии" -> "AccountingRegister"
            "регистрырасчета", "регистррасчета" -> "CalculationRegister"
            "планысчетов", "плансчетов" -> "ChartOfAccounts"
            "планывидовхарактеристик", "планвидовхарактеристик" -> "ChartOfCharacteristicTypes"
            "планывидоврасчета", "планвидоврасчета" -> "ChartOfCalculationTypes"
            "планыобмена", "планобмена" -> "ExchangePlan"
            "перечисления", "перечисление" -> "Enum"
            "отчеты", "отчет" -> "Report"
            "обработки", "обработка" -> "DataProcessor"
            "бизнеспроцессы", "бизнеспроцесс" -> "BusinessProcess"
            "задачи", "задача" -> "Task"
            "журналыдокументов", "журналдокументов" -> "DocumentJournal"
            "константы", "константа" -> "Constant"
            else -> null
        }
}
