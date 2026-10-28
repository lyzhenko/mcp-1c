/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.infrastructure.dump

import io.github.oshai.kotlinlogging.KotlinLogging
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

private val logger = KotlinLogging.logger {}

/** Точка входа: обработчик формы, подписка, регламентное задание, HTTP/веб-сервис. */
data class ParsedEntryPoint(
    val kind: String,
    val name: String,
    val owner: String? = null,
    val handler: String? = null,
    val event: String? = null,
    val path: String,
    val sources: List<String> = emptyList(),
)

/**
 * Разбор точек входа из XML выгрузки.
 *
 * Формы: `Forms/<Имя>/Ext/Form.xml`, обработчики лежат в `Events/Event`. Подписки и задания —
 * отдельные XML верхнего уровня. HTTP/веб-сервисы — XML с методами и шаблонами URL.
 */
class EntryPointParser {
    private val factory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }

    fun parseForm(
        file: Path,
        relative: String,
    ): List<ParsedEntryPoint> {
        val document = parseDocument(file, relative) ?: return emptyList()
        val formName = formName(relative) ?: return emptyList()
        val owner = formOwner(relative)
        val events = mutableListOf<Pair<String?, String>>()

        val eventNodes = document.getElementsByTagNameNS("*", "Event")
        for (index in 0 until eventNodes.length) {
            val event = eventNodes.item(index) as? Element ?: continue
            val parent = event.parentNode as? Element ?: continue
            if (parent.localName != "Events") continue
            val handler = event.textContent.trim()
            if (handler.isEmpty()) continue
            events += event.getAttribute("name").takeIf { it.isNotBlank() } to handler
        }

        return events.map { (eventName, handler) ->
            ParsedEntryPoint(
                kind = "form",
                name = formName,
                owner = owner,
                handler = handler,
                event = eventName,
                path = relative,
            )
        }
    }

    fun parseSubscription(
        file: Path,
        relative: String,
    ): ParsedEntryPoint? {
        val document = parseDocument(file, relative) ?: return null
        val properties = propertiesOf(document, "EventSubscription") ?: return null
        val name = textOf(properties, "Name").ifBlank { relative.substringAfterLast('/').removeSuffix(".xml") }
        val handler = textOf(properties, "Handler").takeIf { it.isNotBlank() }
        val event = textOf(properties, "Event").takeIf { it.isNotBlank() }
        val sources =
            childElement(properties, "Source")
                ?.let { source ->
                    elementChildren(source)
                        .filter { it.localName == "Type" }
                        .map { normalizeSource(it.textContent.trim()) }
                        .filter { it.isNotBlank() }
                }.orEmpty()
        return ParsedEntryPoint(
            kind = "subscription",
            name = name,
            handler = handler,
            event = event,
            path = relative,
            sources = sources,
        )
    }

    fun parseJob(
        file: Path,
        relative: String,
    ): ParsedEntryPoint? {
        val document = parseDocument(file, relative) ?: return null
        val properties = propertiesOf(document, "ScheduledJob") ?: return null
        val name = textOf(properties, "Name").ifBlank { relative.substringAfterLast('/').removeSuffix(".xml") }
        val handler = textOf(properties, "MethodName").takeIf { it.isNotBlank() }
        return ParsedEntryPoint(
            kind = "job",
            name = name,
            handler = handler,
            path = relative,
        )
    }

    fun parseService(
        file: Path,
        relative: String,
        kind: String,
    ): List<ParsedEntryPoint> {
        val document = parseDocument(file, relative) ?: return emptyList()
        val rootName = if (kind == "http") "HTTPService" else "WebService"
        val properties = propertiesOf(document, rootName) ?: return emptyList()
        val name = textOf(properties, "Name").ifBlank { relative.substringAfterLast('/').removeSuffix(".xml") }
        val url = textOf(properties, "RootURL").takeIf { it.isNotBlank() }
        val modulePath = relative.removeSuffix(".xml") + "/Ext/Module.bsl"
        val methods = mutableListOf<ParsedEntryPoint>()

        val objectElement = firstElementChild(document.documentElement) ?: return emptyList()
        childElement(objectElement, "ChildObjects")?.let { childObjects ->
            for (method in elementChildren(childObjects)) {
                if (method.localName != "Method") continue
                val methodProperties = childElement(method, "Properties") ?: continue
                val methodName = textOf(methodProperties, "Name")
                if (methodName.isBlank()) continue
                val template = textOf(methodProperties, "Template").takeIf { it.isNotBlank() }
                methods +=
                    ParsedEntryPoint(
                        kind = kind,
                        name = "$name.$methodName",
                        handler = modulePath,
                        event = template ?: url,
                        path = relative,
                    )
            }
        }
        if (methods.isNotEmpty()) return methods
        return listOf(
            ParsedEntryPoint(
                kind = kind,
                name = name,
                handler = modulePath,
                event = url,
                path = relative,
            ),
        )
    }

    private fun parseDocument(
        file: Path,
        relative: String,
    ): org.w3c.dom.Document? =
        try {
            factory.newDocumentBuilder().parse(file.toFile())
        } catch (error: Exception) {
            logger.warn { "Не разобрана точка входа $relative: ${error.message}" }
            null
        }

    private fun propertiesOf(
        document: org.w3c.dom.Document,
        rootName: String,
    ): Element? {
        val root = document.documentElement ?: return null
        val objectElement =
            if (root.localName == "MetaDataObject") firstElementChild(root) else root
        if (objectElement?.localName != rootName) return null
        return childElement(objectElement, "Properties")
    }

    private fun formName(relative: String): String? {
        val segments = relative.split('/')
        val formsIndex = segments.indexOf("Forms")
        if (formsIndex > 0) return segments.getOrNull(formsIndex + 1)
        if (segments.firstOrNull() == "CommonForms") return segments.getOrNull(1)
        return null
    }

    private fun formOwner(relative: String): String? {
        val segments = relative.split('/')
        if (segments.firstOrNull() == "CommonForms") {
            return segments.getOrNull(1)?.let { "CommonForm.$it" }
        }
        val formsIndex = segments.indexOf("Forms")
        if (formsIndex >= 2) {
            val kind = singularKind(segments[0]) ?: return null
            return "$kind.${segments[1]}"
        }
        return null
    }

    private fun singularKind(folder: String): String? =
        when (folder) {
            "Catalogs" -> "Catalog"
            "Documents" -> "Document"
            "DataProcessors" -> "DataProcessor"
            "Reports" -> "Report"
            "ChartsOfCharacteristicTypes" -> "ChartOfCharacteristicTypes"
            "ChartsOfAccounts" -> "ChartOfAccounts"
            "ChartsOfCalculationTypes" -> "ChartOfCalculationTypes"
            "BusinessProcesses" -> "BusinessProcess"
            "Tasks" -> "Task"
            "DocumentJournals" -> "DocumentJournal"
            "ExchangePlans" -> "ExchangePlan"
            else -> null
        }

    private fun normalizeSource(type: String): String {
        val cleaned = type.removePrefix("cfg:")
        val dot = cleaned.indexOf('.')
        if (dot < 0) return cleaned
        val kind = cleaned.substring(0, dot)
        val name = cleaned.substring(dot + 1)
        val normalizedKind =
            kind
                .removeSuffix("Object")
                .removeSuffix("Ref")
        return if (name.isBlank()) normalizedKind else "$normalizedKind.$name"
    }

    private fun firstElementChild(element: Element): Element? = elementChildren(element).firstOrNull()

    private fun childElement(
        element: Element,
        localName: String,
    ): Element? = elementChildren(element).firstOrNull { it.localName == localName }

    private fun elementChildren(element: Element): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = element.childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node.nodeType == Node.ELEMENT_NODE) result += node as Element
        }
        return result
    }

    private fun textOf(
        element: Element,
        localName: String,
    ): String = childElement(element, localName)?.textContent?.trim().orEmpty()
}
