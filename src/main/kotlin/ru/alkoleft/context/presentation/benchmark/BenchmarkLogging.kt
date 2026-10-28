/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.presentation.benchmark

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import org.slf4j.LoggerFactory

/**
 * В режиме замеров stdout принадлежит JSON-отчёту, поэтому диагностика уходит в stderr.
 *
 * Серверный профиль настраивает logback через `logback-mcp.xml` (файл), а бенчмарк
 * запускается без Spring — здесь appender перенастраивается программно.
 */
object BenchmarkLogging {
    fun routeToStderr() {
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        root.detachAndStopAllAppenders()

        val encoder =
            PatternLayoutEncoder().apply {
                this.context = context
                pattern = "%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n"
                start()
            }
        val appender =
            ConsoleAppender<ILoggingEvent>().apply {
                this.context = context
                target = "System.err"
                this.encoder = encoder
                start()
            }
        root.addAppender(appender)
    }
}
