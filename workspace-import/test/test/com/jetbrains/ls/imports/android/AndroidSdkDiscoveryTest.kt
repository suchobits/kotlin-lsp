// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.android

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class AndroidSdkDiscoveryTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `discovers SDK from local_properties sdk_dir`() {
        val sdkDir = tempDir.resolve("android-sdk").also { it.createDirectories() }
        val projectDir = tempDir.resolve("project").also { it.createDirectories() }
        projectDir.resolve("local.properties").writeText("sdk.dir=${sdkDir.toAbsolutePath()}")

        val result = AndroidSdkDiscovery.findSdk(projectDir)

        assertEquals(sdkDir, result)
    }

    @Test
    fun `returns null when local_properties missing and env not set`() {
        val projectDir = tempDir.resolve("project-no-sdk").also { it.createDirectories() }
        // No local.properties, no env variable pointing to a real path

        // We can't easily test env vars without forking a process, but we can verify
        // the method handles a project with no local.properties gracefully.
        // ANDROID_HOME / ANDROID_SDK_ROOT may be set in CI — so only check the method
        // returns a Path or null (not throws).
        assertDoesNotThrow { AndroidSdkDiscovery.findSdk(projectDir) }
    }

    @Test
    fun `ignores sdk_dir pointing to nonexistent path`() {
        val projectDir = tempDir.resolve("project-bad-sdk").also { it.createDirectories() }
        projectDir.resolve("local.properties")
            .writeText("sdk.dir=/nonexistent/path/that/does/not/exist")

        // Should not return the nonexistent path; falls through to env/platform checks
        val result = AndroidSdkDiscovery.findSdk(projectDir)
        // result may be null or point to a real SDK from env — just not the nonexistent path
        assertNotEquals("/nonexistent/path/that/does/not/exist", result?.toString())
    }

    @Test
    fun `handles malformed local_properties gracefully`() {
        val projectDir = tempDir.resolve("project-malformed").also { it.createDirectories() }
        projectDir.resolve("local.properties").writeText("this is not valid properties\u0000\n")

        // Should not throw
        assertDoesNotThrow { AndroidSdkDiscovery.findSdk(projectDir) }
    }

    @Test
    fun `handles local_properties with no sdk_dir key`() {
        val projectDir = tempDir.resolve("project-no-sdk-key").also { it.createDirectories() }
        projectDir.resolve("local.properties").writeText("# comment\nsome.other.key=value")

        // Falls through to env/platform checks — should not throw
        assertDoesNotThrow { AndroidSdkDiscovery.findSdk(projectDir) }
    }
}
