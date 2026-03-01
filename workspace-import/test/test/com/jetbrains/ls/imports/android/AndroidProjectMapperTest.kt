// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.android

import com.jetbrains.ls.imports.gradle.action.ProjectMetadata
import com.jetbrains.ls.imports.gradle.android.model.AndroidModuleInfo
import com.jetbrains.ls.imports.gradle.android.model.AndroidVariantInfo
import com.jetbrains.ls.imports.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

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

    @Test
    fun `uses variant-specific classpath when variant data available`() {
        val debugJar = tempDir.resolve("deps/debug-tools.jar")
            .also { it.parent.createDirectories() }
            .also { it.toFile().createNewFile() }
        val releaseJar = tempDir.resolve("deps/proguard.jar")
            .also { it.parent.createDirectories() }
            .also { it.toFile().createNewFile() }

        val debugVariant = FakeAndroidVariantInfo(
            name = "debug", buildType = "debug", productFlavors = emptyList(),
            sourceDirs = emptySet(), compileClasspath = setOf(debugJar.toFile()), debuggable = true
        )
        val releaseVariant = FakeAndroidVariantInfo(
            name = "release", buildType = "release", productFlavors = emptyList(),
            sourceDirs = emptySet(), compileClasspath = setOf(releaseJar.toFile()), debuggable = false
        )

        val androidInfo = FakeAndroidModuleInfo(
            bootClasspath = emptyList(),
            debugCompileClasspath = setOf(debugJar.toFile()),
            mainSourceDirs = emptySet(),
            compileSdkVersion = "android-34",
            isApplication = true,
            variantNames = listOf("debug", "release"),
            variants = mapOf("debug" to debugVariant, "release" to releaseVariant)
        )

        val base = WorkspaceData(modules = listOf(ModuleData(name = "app")), libraries = emptyList())
        val metadata = ProjectMetadata(
            emptyList(), emptyMap(), emptyMap(), mapOf("app" to androidInfo)
        )

        // Default config selects "debug" variant
        val result = mapper.merge(base, metadata, tempDir)

        // Should have the debug JAR in libraries, not the release JAR
        val hasDebugJar = result.libraries.any { lib ->
            lib.roots.any { it.path == debugJar.toString() }
        }
        val hasReleaseJar = result.libraries.any { lib ->
            lib.roots.any { it.path == releaseJar.toString() }
        }
        assertTrue(hasDebugJar, "Expected debug JAR in classpath")
        assertFalse(hasReleaseJar, "Release JAR should not be in classpath when debug variant is active")
    }

    @Test
    fun `uses config file to select variant`() {
        val releaseJar = tempDir.resolve("deps/release-lib.jar")
            .also { it.parent.createDirectories() }
            .also { it.toFile().createNewFile() }

        // Create variant source dirs
        tempDir.resolve("app/src/release/kotlin").createDirectories()
        tempDir.resolve("app/src/main/kotlin").createDirectories()

        val debugVariant = FakeAndroidVariantInfo(
            name = "debug", buildType = "debug", productFlavors = emptyList(),
            sourceDirs = emptySet(), compileClasspath = emptySet(), debuggable = true
        )
        val releaseVariant = FakeAndroidVariantInfo(
            name = "release", buildType = "release", productFlavors = emptyList(),
            sourceDirs = emptySet(), compileClasspath = setOf(releaseJar.toFile()), debuggable = false
        )

        val androidInfo = FakeAndroidModuleInfo(
            bootClasspath = emptyList(),
            debugCompileClasspath = emptySet(),
            mainSourceDirs = emptySet(),
            compileSdkVersion = "android-34",
            isApplication = true,
            variantNames = listOf("debug", "release"),
            variants = mapOf("debug" to debugVariant, "release" to releaseVariant)
        )

        // Write config file selecting "release" variant
        tempDir.resolve("kotlin-lsp-config.json").writeText("""
            {
                "android": {
                    "activeVariant": "release"
                }
            }
        """.trimIndent())

        val base = WorkspaceData(modules = listOf(ModuleData(name = "app")), libraries = emptyList())
        val metadata = ProjectMetadata(
            emptyList(), emptyMap(), emptyMap(), mapOf("app" to androidInfo)
        )

        val result = mapper.merge(base, metadata, tempDir)

        // Should have the release JAR
        val hasReleaseJar = result.libraries.any { lib ->
            lib.roots.any { it.path == releaseJar.toString() }
        }
        assertTrue(hasReleaseJar, "Expected release JAR when release variant selected via config")
    }

    @Test
    fun `merges source sets in correct order for flavored variant`() {
        // Set up directory structure for freeDebug variant
        val freeDebugKotlin = tempDir.resolve("app/src/freeDebug/kotlin").also { it.createDirectories() }
        val debugKotlin = tempDir.resolve("app/src/debug/kotlin").also { it.createDirectories() }
        val freeJava = tempDir.resolve("app/src/free/java").also { it.createDirectories() }
        val mainJava = tempDir.resolve("app/src/main/java").also { it.createDirectories() }
        val mainKotlin = tempDir.resolve("app/src/main/kotlin").also { it.createDirectories() }

        val freeDebugVariant = FakeAndroidVariantInfo(
            name = "freeDebug", buildType = "debug", productFlavors = listOf("free"),
            sourceDirs = emptySet(), compileClasspath = emptySet(), debuggable = true
        )

        val androidInfo = FakeAndroidModuleInfo(
            bootClasspath = emptyList(),
            debugCompileClasspath = emptySet(),
            mainSourceDirs = setOf(mainJava.toFile()),
            compileSdkVersion = "android-34",
            isApplication = true,
            variantNames = listOf("freeDebug"),
            variants = mapOf("freeDebug" to freeDebugVariant)
        )

        // Config selects freeDebug
        tempDir.resolve("kotlin-lsp-config.json").writeText("""
            {
                "android": {
                    "activeVariant": "freeDebug"
                }
            }
        """.trimIndent())

        val base = WorkspaceData(modules = listOf(ModuleData(name = "app")), libraries = emptyList())
        val metadata = ProjectMetadata(
            emptyList(), emptyMap(), emptyMap(), mapOf("app" to androidInfo)
        )

        val result = mapper.merge(base, metadata, tempDir)

        val appModule = result.modules.find { it.name == "app" }!!
        val sourceRoots = appModule.contentRoots.flatMap { it.sourceRoots }
        val paths = sourceRoots.map { it.path }

        // All existing source dirs should be included
        assertTrue(paths.any { it.contains("freeDebug") }, "freeDebug source should be included")
        assertTrue(paths.any { it.contains("/debug/") }, "debug source should be included")
        assertTrue(paths.any { it.contains("/free/") }, "free source should be included")
        assertTrue(paths.any { it.contains("/main/") }, "main source should be included")

        // Verify merge order: freeDebug before debug before free before main
        val freeDebugIdx = paths.indexOfFirst { it.contains("freeDebug") }
        val debugIdx = paths.indexOfFirst { it.contains("/debug/") }
        val freeIdx = paths.indexOfFirst { it.contains("/free/") }
        val mainIdx = paths.indexOfFirst { it.contains("/main/") }
        assertTrue(freeDebugIdx < debugIdx, "freeDebug should come before debug")
        assertTrue(debugIdx < freeIdx, "debug should come before free")
        assertTrue(freeIdx < mainIdx, "free should come before main")
    }

    @Test
    fun `falls back to debug when configured variant not available`() {
        val debugJar = tempDir.resolve("deps/debug-lib.jar")
            .also { it.parent.createDirectories() }
            .also { it.toFile().createNewFile() }

        val debugVariant = FakeAndroidVariantInfo(
            name = "debug", buildType = "debug", productFlavors = emptyList(),
            sourceDirs = emptySet(), compileClasspath = setOf(debugJar.toFile()), debuggable = true
        )

        val androidInfo = FakeAndroidModuleInfo(
            bootClasspath = emptyList(),
            debugCompileClasspath = setOf(debugJar.toFile()),
            mainSourceDirs = emptySet(),
            compileSdkVersion = "android-34",
            isApplication = true,
            variantNames = listOf("debug"),
            variants = mapOf("debug" to debugVariant)
        )

        // Config selects a variant that doesn't exist
        tempDir.resolve("kotlin-lsp-config.json").writeText("""
            {
                "android": {
                    "activeVariant": "freeDebug"
                }
            }
        """.trimIndent())

        val base = WorkspaceData(modules = listOf(ModuleData(name = "app")), libraries = emptyList())
        val metadata = ProjectMetadata(
            emptyList(), emptyMap(), emptyMap(), mapOf("app" to androidInfo)
        )

        val result = mapper.merge(base, metadata, tempDir)

        // Should fall back to debug and include its JAR
        val hasDebugJar = result.libraries.any { lib ->
            lib.roots.any { it.path == debugJar.toString() }
        }
        assertTrue(hasDebugJar, "Should fall back to debug variant when configured variant not found")
    }

    // Minimal fake implementations for testing
    private class FakeAndroidModuleInfo(
        private val bootClasspath: List<File>,
        private val debugCompileClasspath: Set<File>,
        private val mainSourceDirs: Set<File>,
        private val compileSdkVersion: String,
        private val isApplication: Boolean,
        private val variantNames: List<String> = emptyList(),
        private val variants: Map<String, AndroidVariantInfo> = emptyMap()
    ) : AndroidModuleInfo {
        override fun getBootClasspath(): List<File> = bootClasspath
        override fun getDebugCompileClasspath(): Set<File> = debugCompileClasspath
        override fun getMainSourceDirs(): Set<File> = mainSourceDirs
        override fun getCompileSdkVersion(): String = compileSdkVersion
        override fun isApplication(): Boolean = isApplication
        override fun getVariantNames(): List<String> = variantNames
        override fun getVariants(): Map<String, AndroidVariantInfo> = variants
    }

    private class FakeAndroidVariantInfo(
        private val name: String,
        private val buildType: String,
        private val productFlavors: List<String>,
        private val sourceDirs: Set<File>,
        private val compileClasspath: Set<File>,
        private val debuggable: Boolean
    ) : AndroidVariantInfo {
        override fun getName(): String = name
        override fun getBuildType(): String = buildType
        override fun getProductFlavors(): List<String> = productFlavors
        override fun getSourceDirs(): Set<File> = sourceDirs
        override fun getCompileClasspath(): Set<File> = compileClasspath
        override fun isDebuggable(): Boolean = debuggable
    }
}
