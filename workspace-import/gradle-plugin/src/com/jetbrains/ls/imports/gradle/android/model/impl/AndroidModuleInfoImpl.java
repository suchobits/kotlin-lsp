// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.android.model.impl;

import com.jetbrains.ls.imports.gradle.android.model.AndroidModuleInfo;
import com.jetbrains.ls.imports.gradle.android.model.AndroidVariantInfo;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("IO_FILE_USAGE")
public final class AndroidModuleInfoImpl implements AndroidModuleInfo {

    private final @NonNull List<File> bootClasspath;
    private final @NonNull Set<File> debugCompileClasspath;
    private final @NonNull Set<File> mainSourceDirs;
    private final @NonNull String compileSdkVersion;
    private final boolean application;
    private final @NonNull List<String> variantNames;
    private final @NonNull Map<String, AndroidVariantInfo> variants;

    public AndroidModuleInfoImpl(
            @NonNull List<File> bootClasspath,
            @NonNull Set<File> debugCompileClasspath,
            @NonNull Set<File> mainSourceDirs,
            @NonNull String compileSdkVersion,
            boolean application
    ) {
        this(bootClasspath, debugCompileClasspath, mainSourceDirs,
             compileSdkVersion, application,
             Collections.emptyList(), Collections.emptyMap());
    }

    public AndroidModuleInfoImpl(
            @NonNull List<File> bootClasspath,
            @NonNull Set<File> debugCompileClasspath,
            @NonNull Set<File> mainSourceDirs,
            @NonNull String compileSdkVersion,
            boolean application,
            @NonNull List<String> variantNames,
            @NonNull Map<String, AndroidVariantInfo> variants
    ) {
        this.bootClasspath = bootClasspath;
        this.debugCompileClasspath = debugCompileClasspath;
        this.mainSourceDirs = mainSourceDirs;
        this.compileSdkVersion = compileSdkVersion;
        this.application = application;
        this.variantNames = variantNames;
        this.variants = variants;
    }

    @Override
    public @NonNull List<File> getBootClasspath() {
        return bootClasspath;
    }

    @Override
    public @NonNull Set<File> getDebugCompileClasspath() {
        return debugCompileClasspath;
    }

    @Override
    public @NonNull Set<File> getMainSourceDirs() {
        return mainSourceDirs;
    }

    @Override
    public @NonNull String getCompileSdkVersion() {
        return compileSdkVersion;
    }

    @Override
    public boolean isApplication() {
        return application;
    }

    @Override
    public @NonNull List<String> getVariantNames() {
        return variantNames;
    }

    @Override
    public @NonNull Map<String, AndroidVariantInfo> getVariants() {
        return variants;
    }
}
