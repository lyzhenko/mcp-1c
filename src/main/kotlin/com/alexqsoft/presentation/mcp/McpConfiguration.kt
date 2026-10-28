/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-platform-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package com.alexqsoft.presentation.mcp

import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class McpConfiguration {
    @Bean
    fun platformTools(
        platformMcp: PlatformContextMcpController,
        dumpMcp: DumpMcpController,
    ): ToolCallbackProvider =
        MethodToolCallbackProvider
            .builder()
            .toolObjects(platformMcp, dumpMcp)
            .build()
}
