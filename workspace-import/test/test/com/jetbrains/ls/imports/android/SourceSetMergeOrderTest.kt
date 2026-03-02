// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.android

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories

class SourceSetMergeOrderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `debug variant with no flavors`() {
        val order = SourceSetMergeOrder.computeMergeOrder("debug", "debug", emptyList())
        assertEquals(listOf("debug", "main"), order)
    }

    @Test
    fun `release variant with no flavors`() {
        val order = SourceSetMergeOrder.computeMergeOrder("release", "release", emptyList())
        assertEquals(listOf("release", "main"), order)
    }

    @Test
    fun `freeDebug variant with single flavor`() {
        val order = SourceSetMergeOrder.computeMergeOrder("freeDebug", "debug", listOf("free"))
        assertEquals(listOf("freeDebug", "debug", "free", "main"), order)
    }

    @Test
    fun `paidRelease variant with single flavor`() {
        val order = SourceSetMergeOrder.computeMergeOrder("paidRelease", "release", listOf("paid"))
        assertEquals(listOf("paidRelease", "release", "paid", "main"), order)
    }

    @Test
    fun `multi-flavor variant includes combined flavor`() {
        val order = SourceSetMergeOrder.computeMergeOrder(
            "freeStagingDebug", "debug", listOf("free", "staging")
        )
        // Expected: freeStagingDebug → debug → freeStaging → staging → free → main
        assertEquals(
            listOf("freeStagingDebug", "debug", "freeStaging", "staging", "free", "main"),
            order
        )
    }

    @Test
    fun `resolveSourceDirs only includes existing directories`() {
        val moduleDir = tempDir.resolve("app")
        moduleDir.resolve("src/main/java").createDirectories()
        moduleDir.resolve("src/main/kotlin").createDirectories()
        moduleDir.resolve("src/debug/kotlin").createDirectories()
        // freeDebug dirs do NOT exist

        val mergeOrder = listOf("freeDebug", "debug", "free", "main")
        val roots = SourceSetMergeOrder.resolveSourceDirs(moduleDir, mergeOrder)

        assertEquals(3, roots.size)
        assertTrue(roots.any { it.path.contains("debug") && it.type == "kotlin-source" })
        assertTrue(roots.any { it.path.contains("main") && it.type == "java-source" })
        assertTrue(roots.any { it.path.contains("main") && it.type == "kotlin-source" })
        // No freeDebug or free dirs should appear
        assertFalse(roots.any { it.path.contains("freeDebug") })
        assertFalse(roots.any { it.path.contains("/free/") })
    }

    @Test
    fun `resolveSourceDirs returns correct types`() {
        val moduleDir = tempDir.resolve("app")
        moduleDir.resolve("src/main/java").createDirectories()
        moduleDir.resolve("src/main/kotlin").createDirectories()

        val roots = SourceSetMergeOrder.resolveSourceDirs(moduleDir, listOf("main"))

        val javaRoot = roots.find { it.path.endsWith("java") }
        val kotlinRoot = roots.find { it.path.endsWith("kotlin") }
        assertNotNull(javaRoot)
        assertNotNull(kotlinRoot)
        assertEquals("java-source", javaRoot!!.type)
        assertEquals("kotlin-source", kotlinRoot!!.type)
    }

    @Test
    fun `three flavor dimensions produces correct merge order`() {
        val order = SourceSetMergeOrder.computeMergeOrder(
            "freeProStagingDebug", "debug", listOf("free", "pro", "staging")
        )
        // Expected: variant → buildType → combinedFlavors → individual flavors (reverse) → main
        assertEquals(
            listOf("freeProStagingDebug", "debug", "freeProStaging", "staging", "pro", "free", "main"),
            order
        )
    }

    @Test
    fun `resolveSourceDirs preserves merge order when all dirs exist`() {
        val moduleDir = tempDir.resolve("app")
        moduleDir.resolve("src/freeDebug/kotlin").createDirectories()
        moduleDir.resolve("src/debug/java").createDirectories()
        moduleDir.resolve("src/debug/kotlin").createDirectories()
        moduleDir.resolve("src/free/java").createDirectories()
        moduleDir.resolve("src/main/java").createDirectories()
        moduleDir.resolve("src/main/kotlin").createDirectories()

        val mergeOrder = listOf("freeDebug", "debug", "free", "main")
        val roots = SourceSetMergeOrder.resolveSourceDirs(moduleDir, mergeOrder)

        // Should be: freeDebug/kotlin, debug/java, debug/kotlin, free/java, main/java, main/kotlin
        assertEquals(6, roots.size)
        val paths = roots.map { it.path }
        val freeDebugIdx = paths.indexOfFirst { it.contains("freeDebug") }
        val debugIdx = paths.indexOfFirst { it.contains("/debug/") }
        val freeIdx = paths.indexOfFirst { it.contains("/free/") }
        val mainIdx = paths.indexOfFirst { it.contains("/main/") }
        assertTrue(freeDebugIdx < debugIdx, "freeDebug dirs should come before debug dirs")
        assertTrue(debugIdx < freeIdx, "debug dirs should come before free dirs")
        assertTrue(freeIdx < mainIdx, "free dirs should come before main dirs")
    }

    @Test
    fun `resolveSourceDirs returns empty list when no dirs exist`() {
        val moduleDir = tempDir.resolve("app").also { it.createDirectories() }
        val roots = SourceSetMergeOrder.resolveSourceDirs(moduleDir, listOf("debug", "main"))
        assertTrue(roots.isEmpty())
    }
}
