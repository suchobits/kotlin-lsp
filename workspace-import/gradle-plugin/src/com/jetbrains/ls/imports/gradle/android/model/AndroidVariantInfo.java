// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.android.model;

import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.Serializable;
import java.util.List;
import java.util.Set;

/**
 * Per-variant data extracted from the Android Gradle Plugin.
 * Each Android module has one or more variants (e.g. "debug", "release", "freeDebug").
 */
@SuppressWarnings("IO_FILE_USAGE")
public interface AndroidVariantInfo extends Serializable {

    /** Variant name, e.g. "freeDebug". */
    @NonNull String getName();

    /** Build type name, e.g. "debug" or "release". */
    @NonNull String getBuildType();

    /** Product flavor names in dimension order, e.g. ["free"]. Empty if no flavors. */
    @NonNull List<String> getProductFlavors();

    /**
     * Source directories for this variant's own source set (NOT merged).
     * e.g. src/freeDebug/java, src/freeDebug/kotlin.
     */
    @NonNull Set<File> getSourceDirs();

    /**
     * Fully resolved compile classpath for this variant.
     * Includes debugImplementation, freeImplementation, freeDebugImplementation, etc.
     */
    @NonNull Set<File> getCompileClasspath();

    /** Whether this is the debuggable (debug) variant. */
    boolean isDebuggable();
}
