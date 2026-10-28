/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.infrastructure.dump

import io.github.oshai.kotlinlogging.KotlinLogging
import org.w3c.dom.Element
import org.w3c.dom.Node
import ru.alkoleft.context.business.entities.DumpField
import ru.alkoleft.context.business.entities.DumpObject
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

private val logger = KotlinLogging.logger {}

/** Результат разбора одного XML метаданных. */
data class ParsedMetadata(
    val obj: DumpObject,
    val fields: List<DumpField>,
)

/**
 * Разбор XML объекта метаданных (формат «Выгрузить конфигурацию в файлы»).
 *
 * Структура едина для всех видов: `MetaDataObject` → объект (`Catalog`, `Document`, …) →
 * `Properties` (имя, синоним, комментарий) и `ChildObjects` (реквизиты, табличные части, формы,
 * команды, значения перечислений). Парсер намеренно не знает конкретных видов: он берёт локальные
 * имена элементов, поэтому новые виды объектов индексируются без правок кода.
 */
class MetadataXmlParser {
    private val factory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }

    fun parse(
        path: Path,
        relativePath: String,
    ): ParsedMetadata? {
        val document =
            try {
                factory.newDocumentBuilder().parse(path.toFile())
            } catch (error: Exception) {
                logger.warn { "Не разобран $relativePath: ${error.message}" }
                return null
            }

        val root = document.documentElement ?: return null
        if (root.localName != "MetaDataObject") return null
        val objectElement = firstElementChild(root) ?: return null
        val kind = objectElement.localName ?: return null
        val properties = childElement(objectElement, "Properties")
        val name = properties?.let { textOf(childElement(it, "Name")) }.orEmpty().trim()
        if (name.isEmpty() && kind != "Configuration") {
            logger.debug { "В $relativePath нет имени объекта — пропущен" }
            return null
        }

        val obj =
            DumpObject(
                kind = kind,
                name = name.ifEmpty { fileName(relativePath) },
                synonym = properties?.let { synonymOf(it) }.orEmpty(),
                comment = properties?.let { textOf(childElement(it, "Comment")) }.orEmpty().trim(),
                path = relativePath,
            )

        val fields = mutableListOf<DumpField>()
        childElement(objectElement, "ChildObjects")?.let { childObjects ->
            for (field in elementChildren(childObjects)) {
                val fieldKind = field.localName ?: continue
                val fieldProperties = childElement(field, "Properties")
                // Формы, макеты и часть команд в выгрузке — ссылки текстом без свойств.
                val fieldName =
                    if (fieldProperties != null) {
                        textOf(childElement(fieldProperties, "Name")).trim()
                    } else {
                        field.textContent.trim()
                    }
                if (fieldName.isEmpty()) continue

                fields +=
                    DumpField(
                        objectId = 0,
                        name = fieldName,
                        synonym = fieldProperties?.let { synonymOf(it) }.orEmpty(),
                        type = fieldProperties?.let { typeOf(it) }.orEmpty(),
                        fieldKind = fieldKind,
                    )

                if (fieldKind == "TabularSection") {
                    childElement(field, "ChildObjects")?.let { nested ->
                        for (nestedField in elementChildren(nested)) {
                            val nestedProperties = childElement(nestedField, "Properties") ?: continue
                            val nestedName = textOf(childElement(nestedProperties, "Name")).trim()
                            if (nestedName.isEmpty()) continue
                            fields +=
                                DumpField(
                                    objectId = 0,
                                    name = nestedName,
                                    synonym = synonymOf(nestedProperties),
                                    type = typeOf(nestedProperties),
                                    fieldKind = nestedField.localName ?: "Attribute",
                                    tabular = fieldName,
                                )
                        }
                    }
                }
            }
        }

        return ParsedMetadata(obj, fields)
    }

    private fun synonymOf(properties: Element): String {
        val synonym = childElement(properties, "Synonym") ?: return ""
        val items = descendants(synonym, "item")
        if (items.isEmpty()) return ""
        val russian = items.firstOrNull { textOf(childElement(it, "lang")).trim() == "ru" } ?: items.first()
        return textOf(childElement(russian, "content")).trim()
    }

    private fun typeOf(properties: Element): String {
        val typeElement = childElement(properties, "Type") ?: return ""
        return elementChildren(typeElement)
            .filter { it.localName == "Type" }
            .map { it.textContent.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
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

    private fun descendants(
        element: Element,
        localName: String,
    ): List<Element> {
        val result = mutableListOf<Element>()

        fun walk(node: Node) {
            if (node is Element && node.localName == localName) result += node
            val children = node.childNodes
            for (index in 0 until children.length) walk(children.item(index))
        }
        walk(element)
        return result
    }

    private fun textOf(element: Element?): String = element?.textContent ?: ""

    private fun fileName(path: String): String = path.substringAfterLast('/').removeSuffix(".xml").substringAfterLast('\\')
}
