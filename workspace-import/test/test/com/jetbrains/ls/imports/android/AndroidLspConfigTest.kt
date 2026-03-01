// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.android

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class AndroidLspConfigTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `returns default config when no config file`() {
        val config = AndroidLspConfig.load(tempDir)
        assertEquals("debug", config.activeVariant)
        assertTrue(config.moduleVariants.isEmpty())
    }

    @Test
    fun `parses activeVariant from config file`() {
        tempDir.resolve("kotlin-lsp-config.json").writeText("""
            {
                "android": {
                    "activeVariant": "release"
                }
            }
        """.trimIndent())

        val config = AndroidLspConfig.load(tempDir)
        assertEquals("release", config.activeVariant)
    }

    @Test
    fun `parses moduleVariants from config file`() {
        tempDir.resolve("kotlin-lsp-config.json").writeText("""
            {
                "android": {
                    "activeVariant": "debug",
                    "moduleVariants": {
                        "app": "paidRelease",
                        "feature-login": "freeDebug"
                    }
                }
            }
        """.trimIndent())

        val config = AndroidLspConfig.load(tempDir)
        assertEquals("paidRelease", config.moduleVariants["app"])
        assertEquals("freeDebug", config.moduleVariants["feature-login"])
    }

    @Test
    fun `resolveVariant uses module override over activeVariant`() {
        val config = AndroidLspConfig.Config(
            activeVariant = "debug",
            moduleVariants = mapOf("app" to "paidRelease")
        )

        assertEquals("paidRelease", AndroidLspConfig.resolveVariant(config, "app"))
        assertEquals("debug", AndroidLspConfig.resolveVariant(config, "lib"))
    }

    @Test
    fun `resolveValidVariant falls back to debug when requested not available`() {
        val config = AndroidLspConfig.Config(activeVariant = "freeDebug")
        val available = listOf("debug", "release")

        val result = AndroidLspConfig.resolveValidVariant(config, "app", available)
        assertEquals("debug", result)
    }

    @Test
    fun `resolveValidVariant falls back to first when debug not available`() {
        val config = AndroidLspConfig.Config(activeVariant = "nonexistent")
        val available = listOf("release", "staging")

        val result = AndroidLspConfig.resolveValidVariant(config, "app", available)
        assertEquals("release", result)
    }

    @Test
    fun `resolveValidVariant returns requested when available`() {
        val config = AndroidLspConfig.Config(activeVariant = "freeDebug")
        val available = listOf("debug", "release", "freeDebug", "paidDebug")

        val result = AndroidLspConfig.resolveValidVariant(config, "app", available)
        assertEquals("freeDebug", result)
    }

    @Test
    fun `handles malformed JSON gracefully`() {
        tempDir.resolve("kotlin-lsp-config.json").writeText("not valid json")

        val config = AndroidLspConfig.load(tempDir)
        assertEquals("debug", config.activeVariant)
    }

    @Test
    fun `handles missing android section gracefully`() {
        tempDir.resolve("kotlin-lsp-config.json").writeText("""
            {
                "other": "stuff"
            }
        """.trimIndent())

        val config = AndroidLspConfig.load(tempDir)
        assertEquals("debug", config.activeVariant)
    }

    @Test
    fun `resolveVariantForModule exact match`() {
        val result = AndroidLspConfig.resolveVariantForModule(
            "freeDebug", "debug", listOf("debug", "release", "freeDebug")
        )
        assertEquals("freeDebug", result)
    }

    @Test
    fun `resolveVariantForModule falls back to build type`() {
        val result = AndroidLspConfig.resolveVariantForModule(
            "freeDebug", "debug", listOf("debug", "release")
        )
        assertEquals("debug", result)
    }

    @Test
    fun `resolveVariantForModule falls back to first available`() {
        val result = AndroidLspConfig.resolveVariantForModule(
            "freeDebug", "debug", listOf("staging", "production")
        )
        assertEquals("staging", result)
    }
}
