// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.android

import com.intellij.openapi.diagnostic.logger
import com.jetbrains.ls.imports.gradle.action.ProjectMetadata
import com.jetbrains.ls.imports.json.ContentRootData
import com.jetbrains.ls.imports.json.DependencyData
import com.jetbrains.ls.imports.json.DependencyDataScope
import com.jetbrains.ls.imports.json.LibraryData
import com.jetbrains.ls.imports.json.LibraryRootData
import com.jetbrains.ls.imports.json.ModuleData
import com.jetbrains.ls.imports.json.SourceRootData
import com.jetbrains.ls.imports.json.WorkspaceData
import java.io.File
import java.nio.file.Path

private val LOG = logger<AndroidProjectMapper>()

/**
 * Merges Android-specific workspace data (from [ProjectMetadata.androidModules]) into the
 * base [WorkspaceData] produced by [com.jetbrains.ls.imports.gradle.IdeaProjectMapper].
 *
 * For each Android module this adds:
 * - A [LibraryData] for the boot classpath (android.jar and optional platform libs)
 * - [LibraryData] entries for all resolved dependency JARs (AARs already extracted by Gradle)
 * - [SourceRootData] entries for the module's main source directories
 * - Corresponding [DependencyData.Library] references on the [ModuleData]
 */
internal class AndroidProjectMapper {

    fun merge(base: WorkspaceData, metadata: ProjectMetadata, projectDirectory: Path): WorkspaceData {
        if (metadata.androidModules.isEmpty()) return base

        val extraLibraries = mutableListOf<LibraryData>()
        val moduleReplacements = mutableMapOf<String, ModuleData>()

        for ((moduleName, androidInfo) in metadata.androidModules) {
            LOG.info("Processing Android module: $moduleName (compileSdk=${androidInfo.compileSdkVersion})")

            val moduleDependencies = mutableListOf<DependencyData>()

            // 1. Boot classpath library (android.jar + optional extras)
            val bootJars = androidInfo.bootClasspath.filter { it.exists() }
            if (bootJars.isNotEmpty()) {
                val bootLibName = "android-sdk-${sanitizeLibName(moduleName)}-${androidInfo.compileSdkVersion}"
                extraLibraries.add(
                    LibraryData(
                        name = bootLibName,
                        type = "COMPILE",
                        roots = bootJars.map { LibraryRootData(it.path, "CLASSES") }
                    )
                )
                moduleDependencies.add(DependencyData.Library(bootLibName, DependencyDataScope.COMPILE))
                LOG.info("  Boot classpath: ${bootJars.size} JARs")
            } else {
                LOG.warn("  No boot classpath JARs found for $moduleName")
            }

            // 2. Dependency JARs from compile classpath
            //    Gradle's transform pipeline has already extracted classes.jar from AARs.
            //    Filter out boot classpath entries to avoid duplicates.
            val bootPaths = androidInfo.bootClasspath.map { it.path }.toSet()
            val depJars = androidInfo.debugCompileClasspath
                .filter { it.exists() && it.extension == "jar" && it.path !in bootPaths }

            depJars.forEachIndexed { index, jar ->
                val libName = "android-dep-${sanitizeLibName(moduleName)}-$index"
                extraLibraries.add(
                    LibraryData(
                        name = libName,
                        type = "COMPILE",
                        roots = listOf(LibraryRootData(jar.path, "CLASSES"))
                    )
                )
                moduleDependencies.add(DependencyData.Library(libName, DependencyDataScope.COMPILE))
            }
            LOG.info("  Dependency JARs: ${depJars.size}")

            // 3. SDK and module source markers
            moduleDependencies.add(DependencyData.InheritedSdk)
            moduleDependencies.add(DependencyData.ModuleSource)

            // 4. Source roots from main source set
            val sourceDirs = androidInfo.mainSourceDirs.filter { it.exists() && it.isDirectory }
            val sourceRoots = sourceDirs.map { dir ->
                SourceRootData(dir.path, sourceRootType(dir))
            }

            if (sourceRoots.isEmpty()) {
                LOG.warn("  No source directories found for $moduleName")
            } else {
                LOG.info("  Source dirs: ${sourceDirs.map { it.name }}")
            }

            val contentRootPath = sourceDirs
                .map { it.path }
                .findCommonPrefix()
                .ifEmpty { "$projectDirectory${File.separator}src${File.separator}main" }

            moduleReplacements[moduleName] = ModuleData(
                name = moduleName,
                dependencies = moduleDependencies,
                contentRoots = listOf(
                    ContentRootData(
                        path = contentRootPath,
                        sourceRoots = sourceRoots
                    )
                )
            )
        }

        // Merge: replace android modules in the base list; append extra libraries
        val mergedModules = base.modules.map { module ->
            moduleReplacements[module.name] ?: module
        }

        // Add any Android modules not yet in the base list (shouldn't normally happen,
        // but guards against IdeaProject model omitting modules)
        val existingModuleNames = base.modules.map { it.name }.toSet()
        val newModules = moduleReplacements.filterKeys { it !in existingModuleNames }.values

        return base.copy(
            modules = mergedModules + newModules,
            libraries = base.libraries + extraLibraries
        )
    }

    private fun sourceRootType(dir: File): String {
        return when (dir.name.lowercase()) {
            "kotlin" -> "kotlin-source"
            else -> "java-source"
        }
    }

    private fun sanitizeLibName(moduleName: String): String =
        moduleName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
}

private fun List<String>.findCommonPrefix(): String {
    if (isEmpty()) return ""
    var prefix = first()
    for (s in drop(1)) {
        while (!s.startsWith(prefix)) {
            prefix = prefix.dropLast(1)
            if (prefix.isEmpty()) return ""
        }
    }
    // Trim to last path separator so we don't return partial directory names
    val lastSep = prefix.lastIndexOf(File.separatorChar)
    return if (lastSep > 0) prefix.substring(0, lastSep) else prefix
}
