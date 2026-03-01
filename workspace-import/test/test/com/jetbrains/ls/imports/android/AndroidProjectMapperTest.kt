// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.android

import com.jetbrains.ls.imports.gradle.action.ProjectMetadata
import com.jetbrains.ls.imports.gradle.android.model.AndroidModuleInfo
import com.jetbrains.ls.imports.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.createDirectories

class AndroidProjectMapperTest {

    @TempDir
    lateinit var tempDir: Path

    private val mapper = AndroidProjectMapper()

    @Test
    fun `returns base unchanged when no android modules`() {
        val base = WorkspaceData(
            modules = listOf(ModuleData(name = "app")),
            libraries = emptyList()
        )
        val metadata = ProjectMetadata(
            emptyList(), emptyMap(), emptyMap(), emptyMap()
        )

        val result = mapper.merge(base, metadata, tempDir)

        assertSame(base, result)
    }

    @Test
    fun `adds boot classpath library for android module`() {
        val bootJar = tempDir.resolve("android-sdk/platforms/android-34/android.jar")
            .also { it.parent.createDirectories() }
            .also { it.toFile().createNewFile() }

        val sourcesDir = tempDir.resolve("app/src/main/java")
            .also { it.createDirectories() }

        val androidInfo = FakeAndroidModuleInfo(
            bootClasspath = listOf(bootJar.toFile()),
            debugCompileClasspath = emptySet(),
            mainSourceDirs = setOf(sourcesDir.toFile()),
            compileSdkVersion = "android-34",
            isApplication = true
        )

        val base = WorkspaceData(
            modules = listOf(ModuleData(name = "app")),
            libraries = emptyList()
        )
        val metadata = ProjectMetadata(
            emptyList(), emptyMap(), emptyMap(), mapOf("app" to androidInfo)
        )

        val result = mapper.merge(base, metadata, tempDir)

        // Should have the boot classpath library
        val bootLib = result.libraries.find { it.name.contains("android-34") }
        assertNotNull(bootLib, "Expected a boot classpath library")
        assertEquals(1, bootLib!!.roots.size)
        assertTrue(bootLib.roots.first().path.contains("android.jar"))
    }

    @Test
    fun `filters out AAR files from dependency classpath`() {
        val depJar = tempDir.resolve("deps/lib.jar")
            .also { it.parent.createDirectories() }
            .also { it.toFile().createNewFile() }
        val depAar = tempDir.resolve("deps/lib.aar")
            .also { it.parent.createDirectories() }
            .also { it.toFile().createNewFile() }

        val androidInfo = FakeAndroidModuleInfo(
            bootClasspath = emptyList(),
            debugCompileClasspath = setOf(depJar.toFile(), depAar.toFile()),
            mainSourceDirs = emptySet(),
            compileSdkVersion = "android-34",
            isApplication = true
        )

        val base = WorkspaceData(
            modules = listOf(ModuleData(name = "app")),
            libraries = emptyList()
        )
        val metadata = ProjectMetadata(
            emptyList(), emptyMap(), emptyMap(), mapOf("app" to androidInfo)
        )

        val result = mapper.merge(base, metadata, tempDir)

        // Only the JAR should appear as a library — AAR filtered out
        val depLibs = result.libraries.filter { lib ->
            lib.roots.any { it.path.endsWith(".jar") }
        }
        val aarLibs = result.libraries.filter { lib ->
            lib.roots.any { it.path.endsWith(".aar") }
        }
        assertTrue(depLibs.isNotEmpty(), "Expected JAR dependency libraries")
        assertTrue(aarLibs.isEmpty(), "AAR files should be filtered out")
    }

    @Test
    fun `replaces module data with android-specific content root`() {
        val srcDir = tempDir.resolve("app/src/main/java")
            .also { it.createDirectories() }

        val androidInfo = FakeAndroidModuleInfo(
            bootClasspath = emptyList(),
            debugCompileClasspath = emptySet(),
            mainSourceDirs = setOf(srcDir.toFile()),
            compileSdkVersion = "android-34",
            isApplication = true
        )

        val originalModule = ModuleData(
            name = "app",
            contentRoots = listOf(ContentRootData(path = tempDir.toString()))
        )
        val base = WorkspaceData(modules = listOf(originalModule), libraries = emptyList())
        val metadata = ProjectMetadata(
            emptyList(), emptyMap(), emptyMap(), mapOf("app" to androidInfo)
        )

        val result = mapper.merge(base, metadata, tempDir)

        val appModule = result.modules.find { it.name == "app" }
        assertNotNull(appModule)
        val sourceRoots = appModule!!.contentRoots.flatMap { it.sourceRoots }
        assertTrue(sourceRoots.any { it.path == srcDir.toString() },
            "Expected source root at $srcDir but got ${sourceRoots.map { it.path }}")
    }

    @Test
    fun `deduplicates boot classpath from compile classpath`() {
        val androidJar = tempDir.resolve("sdk/android.jar")
            .also { it.parent.createDirectories() }
            .also { it.toFile().createNewFile() }

        val androidInfo = FakeAndroidModuleInfo(
            bootClasspath = listOf(androidJar.toFile()),
            // android.jar also appears in compile classpath (Gradle includes it)
            debugCompileClasspath = setOf(androidJar.toFile()),
            mainSourceDirs = emptySet(),
            compileSdkVersion = "android-34",
            isApplication = true
        )

        val base = WorkspaceData(modules = listOf(ModuleData(name = "app")), libraries = emptyList())
        val metadata = ProjectMetadata(
            emptyList(), emptyMap(), emptyMap(), mapOf("app" to androidInfo)
        )

        val result = mapper.merge(base, metadata, tempDir)

        // android.jar should appear exactly once (in boot library, not in dep libs)
        val androidJarLibs = result.libraries.filter { lib ->
            lib.roots.any { it.path == androidJar.toString() }
        }
        assertEquals(1, androidJarLibs.size,
            "android.jar should appear exactly once, not duplicated in dep libs")
    }

    @Test
    fun `does not regress jvm module data`() {
        val jvmModule = ModuleData(
            name = "util",
            contentRoots = listOf(ContentRootData(
                path = tempDir.toString(),
                sourceRoots = listOf(SourceRootData("src/main/kotlin", "kotlin-source"))
            ))
        )
        val androidModule = ModuleData(name = "app")

        val androidInfo = FakeAndroidModuleInfo(
            bootClasspath = emptyList(),
            debugCompileClasspath = emptySet(),
            mainSourceDirs = emptySet(),
            compileSdkVersion = "android-34",
            isApplication = true
        )

        val base = WorkspaceData(modules = listOf(jvmModule, androidModule), libraries = emptyList())
        val metadata = ProjectMetadata(
            emptyList(), emptyMap(), emptyMap(), mapOf("app" to androidInfo)
        )

        val result = mapper.merge(base, metadata, tempDir)

        // JVM module should be completely unchanged
        val resultJvmModule = result.modules.find { it.name == "util" }
        assertNotNull(resultJvmModule)
        assertEquals(jvmModule.contentRoots, resultJvmModule!!.contentRoots)
        assertEquals(jvmModule.dependencies, resultJvmModule.dependencies)
    }

    // Minimal fake implementation for testing
    private class FakeAndroidModuleInfo(
        private val bootClasspath: List<File>,
        private val debugCompileClasspath: Set<File>,
        private val mainSourceDirs: Set<File>,
        private val compileSdkVersion: String,
        private val isApplication: Boolean
    ) : AndroidModuleInfo {
        override fun getBootClasspath(): List<File> = bootClasspath
        override fun getDebugCompileClasspath(): Set<File> = debugCompileClasspath
        override fun getMainSourceDirs(): Set<File> = mainSourceDirs
        override fun getCompileSdkVersion(): String = compileSdkVersion
        override fun isApplication(): Boolean = isApplication
    }
}
