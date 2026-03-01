// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.android

import com.intellij.openapi.diagnostic.logger
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

private val LOG = logger<AndroidSdkDiscovery>()

/**
 * Locates the Android SDK installation using the standard discovery chain:
 * 1. `local.properties` sdk.dir in the project root
 * 2. `ANDROID_HOME` environment variable
 * 3. `ANDROID_SDK_ROOT` environment variable (deprecated)
 * 4. Platform-specific default install location
 */
object AndroidSdkDiscovery {

    fun findSdk(projectDirectory: Path): Path? {
        return fromLocalProperties(projectDirectory)
            ?: fromEnv("ANDROID_HOME")
            ?: fromEnv("ANDROID_SDK_ROOT")
            ?: platformDefault()
    }

    private fun fromLocalProperties(projectRoot: Path): Path? {
        val localProps = projectRoot / "local.properties"
        if (!localProps.isRegularFile()) return null
        return try {
            val properties = Properties().apply {
                localProps.toFile().bufferedReader().use { load(it) }
            }
            properties.getProperty("sdk.dir")
                ?.let { Path.of(it) }
                ?.takeIf { it.exists() }
                ?.also { LOG.info("Android SDK found via local.properties: $it") }
        } catch (e: Exception) {
            LOG.warn("Failed to parse local.properties: ${e.message}")
            null
        }
    }

    private fun fromEnv(varName: String): Path? =
        System.getenv(varName)
            ?.let { Path.of(it) }
            ?.takeIf { it.exists() }
            ?.also { LOG.info("Android SDK found via $varName: $it") }

    private fun platformDefault(): Path? {
        val os = System.getProperty("os.name", "").lowercase()
        val home = System.getProperty("user.home") ?: return null
        val candidates: List<Path> = when {
            os.contains("mac") -> listOf(Path.of(home, "Library", "Android", "sdk"))
            os.contains("win") -> {
                val localAppData = System.getenv("LOCALAPPDATA") ?: return null
                listOf(Path.of(localAppData, "Android", "Sdk"))
            }
            else -> listOf(
                Path.of(home, "Android", "Sdk"),
                Path.of(home, ".android", "sdk")
            )
        }
        return candidates.firstOrNull { it.exists() }
            ?.also { LOG.info("Android SDK found at platform default: $it") }
    }
}
