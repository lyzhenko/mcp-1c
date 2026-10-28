/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.infrastructure.dump

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DumpStorageTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var dump: Path
    private val storages = mutableListOf<DumpStorage>()

    @BeforeEach
    fun setUp() {
        dump = Path.of(javaClass.getResource("/dump")!!.toURI())
    }

    @AfterEach
    fun tearDown() {
        storages.forEach { it.close() }
        storages.clear()
    }

    @Test
    fun `открывает выгрузку и строит индекс`() {
        val storage = storage()
        storage.open(dump.toString())
        val index = assertNotNull(storage.awaitReady())
        assertEquals(4, index.info().objects)

        val snapshot = storage.snapshot()
        assertEquals(DumpStorage.State.READY, snapshot.state)
        assertTrue(snapshot.indexFile != null && Files.isRegularFile(snapshot.indexFile))
    }

    @Test
    fun `повторное открытие переиспользует готовый индекс`() {
        val first = storage()
        first.open(dump.toString())
        assertNotNull(first.awaitReady())
        val builtAt = first.snapshot().info?.indexedAt
        first.close()

        val second = storage()
        second.open(dump.toString())
        val index = assertNotNull(second.awaitReady())
        assertEquals(builtAt, index.info().indexedAt)
    }

    @Test
    fun `reload перестраивает индекс`() {
        val storage = storage()
        storage.open(dump.toString())
        assertNotNull(storage.awaitReady())
        storage.reload()
        val index = assertNotNull(storage.awaitReady())
        assertEquals(4, index.info().objects)
    }

    @Test
    fun `несуществующий каталог даёт failed`() {
        val storage = storage()
        storage.open(directory.resolve("нет-такого").toString())
        assertEquals(DumpStorage.State.FAILED, storage.snapshot().state)
    }

    private fun storage(): DumpStorage =
        DumpStorage(
            DumpOptions(indexDir = directory.toString(), waitTimeoutMs = 60_000),
        ).also { storages += it }
}
