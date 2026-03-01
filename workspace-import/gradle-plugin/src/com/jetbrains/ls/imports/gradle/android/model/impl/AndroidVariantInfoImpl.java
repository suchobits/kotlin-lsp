// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.android.model.impl;

import com.jetbrains.ls.imports.gradle.android.model.AndroidVariantInfo;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.util.List;
import java.util.Set;

@SuppressWarnings("IO_FILE_USAGE")
public final class AndroidVariantInfoImpl implements AndroidVariantInfo {

    private final @NonNull String name;
    private final @NonNull String buildType;
    private final @NonNull List<String> productFlavors;
    private final @NonNull Set<File> sourceDirs;
    private final @NonNull Set<File> compileClasspath;
    private final boolean debuggable;

    public AndroidVariantInfoImpl(
            @NonNull String name,
            @NonNull String buildType,
            @NonNull List<String> productFlavors,
            @NonNull Set<File> sourceDirs,
            @NonNull Set<File> compileClasspath,
            boolean debuggable
    ) {
        this.name = name;
        this.buildType = buildType;
        this.productFlavors = productFlavors;
        this.sourceDirs = sourceDirs;
        this.compileClasspath = compileClasspath;
        this.debuggable = debuggable;
    }

    @Override
    public @NonNull String getName() {
        return name;
    }

    @Override
    public @NonNull String getBuildType() {
        return buildType;
    }

    @Override
    public @NonNull List<String> getProductFlavors() {
        return productFlavors;
    }

    @Override
    public @NonNull Set<File> getSourceDirs() {
        return sourceDirs;
    }

    @Override
    public @NonNull Set<File> getCompileClasspath() {
        return compileClasspath;
    }

    @Override
    public boolean isDebuggable() {
        return debuggable;
    }
}
