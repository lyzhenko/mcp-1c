/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-1c project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.infrastructure.dump

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Чтение текстовых файлов выгрузки.
 *
 * 1С пишет выгрузку в UTF-8, но встречаются UTF-16 и старые файлы в windows-1251. BOM снимается,
 * битые байты не роняют чтение: файл декодируется с заменой символов, а предупреждение уходит в лог.
 */
object DumpTextReader {
    private val WINDOWS_1251: Charset = Charset.forName("windows-1251")

    fun read(path: Path): String = decode(Files.readAllBytes(path))

    fun decode(bytes: ByteArray): String = normalize(decodeRaw(bytes))

    private fun decodeRaw(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            logger.debug { "Файл не в UTF-8, читаем как windows-1251" }
            String(bytes, WINDOWS_1251)
        }
    }

    /** Привести переводы строк к `\n`: иначе номера строк и текст модуля выглядят рвано. */
    private fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    /** Число строк без учёта завершающего перевода строки. */
    fun countLines(text: String): Int {
        if (text.isEmpty()) return 0
        var lines = 1
        for (character in text) {
            if (character == '\n') lines++
        }
        return if (text.endsWith('\n')) lines - 1 else lines
    }
}
