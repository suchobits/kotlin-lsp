// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.android.model;

import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.Serializable;
import java.util.List;
import java.util.Set;

/**
 * Tooling model for Android modules. Extracted from the Gradle daemon via
 * {@link com.jetbrains.ls.imports.gradle.android.model.builder.AndroidModuleInfoModelBuilder}
 * using reflection to avoid a compile-time dependency on the Android Gradle Plugin.
 */
@SuppressWarnings("IO_FILE_USAGE")
public interface AndroidModuleInfo extends Serializable {

    /**
     * Boot classpath: contains android.jar and optional platform library JARs.
     * Obtained from {@code android.bootClasspath} on the Android extension.
     */
    @NonNull List<File> getBootClasspath();

    /**
     * Fully resolved compile classpath for the debug variant. Gradle's transform
     * pipeline has already extracted {@code classes.jar} from AAR dependencies,
     * so all entries are regular JARs ready to add to the analysis classpath.
     */
    @NonNull Set<File> getDebugCompileClasspath();

    /**
     * Source directories from the main Android source set (java/ and kotlin/).
     * Obtained from {@code android.sourceSets.main.java.srcDirs}.
     */
    @NonNull Set<File> getMainSourceDirs();

    /**
     * The compile SDK version string, e.g. {@code "android-34"}.
     * Obtained from {@code android.compileSdkVersion}.
     */
    @NonNull String getCompileSdkVersion();

    /**
     * {@code true} if this is an application module ({@code com.android.application}),
     * {@code false} if it is a library module ({@code com.android.library}).
     */
    boolean isApplication();
}
