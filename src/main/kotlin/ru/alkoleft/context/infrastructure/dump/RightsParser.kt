/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.infrastructure.dump

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/** Право роли на объект. */
data class ParsedRight(
    val objectRef: String,
    val right: String,
    val value: Boolean,
    val condition: String?,
)

/** Разобранная роль. */
data class ParsedRole(
    val name: String,
    val path: String,
    val rights: List<ParsedRight>,
)

/** Разбор `Roles/<Имя>/Ext/Rights.xml`: права и условия RLS. */
class RightsParser {
    private val factory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        }

    fun parse(
        file: Path,
        relative: String,
        roleName: String,
    ): ParsedRole? {
        val document =
            runCatching { factory.newDocumentBuilder().parse(file.toFile()) }
                .getOrNull() ?: return null
        val root = document.documentElement ?: return null
        if (root.localName != "Rights") return null
        val rights = mutableListOf<ParsedRight>()
        for (objectElement in elementChildren(root)) {
            if (objectElement.localName != "object") continue
            val objectRef = childText(objectElement, "name")
            if (objectRef.isBlank()) continue
            for (rightElement in elementChildren(objectElement)) {
                if (rightElement.localName != "right") continue
                val right = childText(rightElement, "name")
                if (right.isBlank()) continue
                val value = childText(rightElement, "value").equals("true", ignoreCase = true)
                val condition =
                    childElement(rightElement, "restrictionByCondition")
                        ?.let { childText(it, "condition") }
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                rights += ParsedRight(objectRef, right, value, condition)
            }
        }
        return ParsedRole(roleName, relative, rights)
    }

    private fun childText(
        element: Element,
        localName: String,
    ): String = childElement(element, localName)?.textContent?.trim().orEmpty()

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
}
