// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Loads variant configuration from `kotlin-lsp-config.json` at the project root.
 * Supports project-wide and per-module variant overrides.
 */
internal object AndroidLspConfig {

    data class Config(
        val activeVariant: String = "debug",
        val moduleVariants: Map<String, String> = emptyMap()
    )

    fun load(projectDir: Path): Config {
        val configFile = projectDir.resolve("kotlin-lsp-config.json")
        if (!configFile.exists()) return Config()

        return try {
            val json = Json.parseToJsonElement(configFile.readText()).jsonObject
            val android = json["android"]?.jsonObject ?: return Config()
            Config(
                activeVariant = android["activeVariant"]?.jsonPrimitive?.content ?: "debug",
                moduleVariants = android["moduleVariants"]?.jsonObject
                    ?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            )
        } catch (e: Exception) {
            System.err.println("[kotlin-lsp] Failed to parse kotlin-lsp-config.json: ${e.message}")
            Config()
        }
    }

    fun resolveVariant(config: Config, moduleName: String): String {
        return config.moduleVariants[moduleName]
            ?: config.activeVariant
    }

    /**
     * Resolves the variant to use, falling back if the requested variant is not available.
     * Falls back to "debug", then to the first available variant.
     */
    fun resolveValidVariant(config: Config, moduleName: String, available: List<String>): String {
        val requested = resolveVariant(config, moduleName)
        if (requested in available) return requested

        System.err.println(
            "[kotlin-lsp] Variant '$requested' not found for module '$moduleName'. " +
                "Available: $available. Falling back to 'debug'."
        )
        if ("debug" in available) return "debug"
        return available.firstOrNull() ?: "debug"
    }

    /**
     * Resolves a variant for a module that may have different available variants
     * than the requesting module (inter-module variant matching).
     */
    fun resolveVariantForModule(
        requestedVariant: String,
        requestedBuildType: String,
        availableVariants: List<String>
    ): String {
        if (requestedVariant in availableVariants) return requestedVariant
        if (requestedBuildType in availableVariants) return requestedBuildType
        return availableVariants.firstOrNull() ?: "debug"
    }
}
