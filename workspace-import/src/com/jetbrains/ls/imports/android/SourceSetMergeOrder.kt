// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.android

import com.jetbrains.ls.imports.json.SourceRootData
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Computes the Android source set merge order for a given variant configuration.
 * Source sets are ordered from most-specific to least-specific following AGP conventions.
 */
internal object SourceSetMergeOrder {

    /**
     * Returns source set names in merge order (most specific first)
     * for the given variant configuration.
     *
     * For `freeDebug` (flavor=free, buildType=debug):
     *   freeDebug → debug → free → main
     *
     * For `freeStagingDebug` (flavors=[free,staging], buildType=debug):
     *   freeStagingDebug → debug → freeStaging → staging → free → main
     */
    fun computeMergeOrder(
        variantName: String,
        buildType: String,
        productFlavors: List<String>
    ): List<String> {
        val result = mutableListOf<String>()

        // 1. Variant-specific (only if there are flavors — otherwise it equals the buildType)
        if (productFlavors.isNotEmpty()) {
            result.add(variantName)
        }

        // 2. BuildType-specific
        result.add(buildType)

        // 3. Flavor combinations (if multiple flavor dimensions)
        if (productFlavors.size > 1) {
            val combinedFlavor = productFlavors.joinToString("") { it.replaceFirstChar(Char::uppercase) }
                .replaceFirstChar(Char::lowercase)
            result.add(combinedFlavor)
        }

        // 4. Individual flavors (in reverse dimension order — most specific dimension first)
        for (flavor in productFlavors.reversed()) {
            result.add(flavor)
        }

        // 5. Main (always last)
        result.add("main")

        return result.distinct()
    }

    /**
     * Resolves actual source directories for the given merge order.
     * For each source set name, checks for java/ and kotlin/ subdirectories under src/.
     * Only includes directories that exist on disk.
     */
    fun resolveSourceDirs(moduleDir: Path, mergeOrder: List<String>): List<SourceRootData> {
        val roots = mutableListOf<SourceRootData>()
        for (sourceSetName in mergeOrder) {
            val base = moduleDir.resolve("src").resolve(sourceSetName)
            val javaDir = base.resolve("java")
            val kotlinDir = base.resolve("kotlin")

            if (javaDir.exists() && javaDir.isDirectory()) {
                roots.add(SourceRootData(javaDir.toString(), "java-source"))
            }
            if (kotlinDir.exists() && kotlinDir.isDirectory()) {
                roots.add(SourceRootData(kotlinDir.toString(), "kotlin-source"))
            }
        }
        return roots
    }
}
