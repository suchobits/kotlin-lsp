// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.android.model.builder;

import com.jetbrains.ls.imports.gradle.android.model.AndroidModuleInfo;
import com.jetbrains.ls.imports.gradle.android.model.impl.AndroidModuleInfoImpl;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.gradle.tooling.provider.model.ToolingModelBuilder;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Extracts Android-specific project metadata from the Gradle daemon using reflection,
 * avoiding a compile-time dependency on the Android Gradle Plugin. Returns null for
 * non-Android modules (those without an "android" Gradle extension).
 */
@SuppressWarnings("IO_FILE_USAGE")
public final class AndroidModuleInfoModelBuilder implements ToolingModelBuilder {

    private static final String TARGET_MODEL = AndroidModuleInfo.class.getName();

    @Override
    public boolean canBuild(@NonNull String modelName) {
        return TARGET_MODEL.equals(modelName);
    }

    @Override
    public @Nullable Object buildAll(@NonNull String modelName, @NonNull Project project) {
        Object androidExt = project.getExtensions().findByName("android");
        if (androidExt == null) {
            return null; // Not an Android module
        }

        try {
            List<File> bootClasspath = getBootClasspath(androidExt);
            String compileSdkVersion = getCompileSdkVersion(androidExt);
            boolean isApp = androidExt.getClass().getName().contains("AppExtension");
            Set<File> compileClasspath = getDebugCompileClasspath(project);
            Set<File> mainSourceDirs = getMainSourceDirs(androidExt);

            return new AndroidModuleInfoImpl(
                    bootClasspath,
                    compileClasspath,
                    mainSourceDirs,
                    compileSdkVersion,
                    isApp
            );
        } catch (Exception e) {
            System.err.println("[kotlin-lsp] Failed to extract Android info for "
                    + project.getName() + ": " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static @NonNull List<File> getBootClasspath(@NonNull Object androidExt) {
        try {
            Object result = invokeMethod(androidExt, "getBootClasspath");
            if (result instanceof List) {
                return (List<File>) result;
            }
        } catch (Exception e) {
            System.err.println("[kotlin-lsp] Could not get bootClasspath: " + e.getMessage());
        }
        return Collections.emptyList();
    }

    private static @NonNull String getCompileSdkVersion(@NonNull Object androidExt) {
        // AGP 8.x uses compileSdk (int) + compileSdkVersion (String, deprecated)
        // Try compileSdkVersion string first, then build it from compileSdk int
        for (String methodName : new String[]{"getCompileSdkVersion", "getCompileSdk"}) {
            try {
                Object result = invokeMethod(androidExt, methodName);
                if (result instanceof String) {
                    return (String) result;
                }
                if (result instanceof Integer) {
                    return "android-" + result;
                }
            } catch (Exception ignored) {
            }
        }
        return "android-34"; // safe fallback
    }

    private static @NonNull Set<File> getDebugCompileClasspath(@NonNull Project project) {
        // Try compileDebugKotlin first (Kotlin projects), then compileDebugJavaWithJavac
        for (String taskName : new String[]{"compileDebugKotlin", "compileDebugJavaWithJavac"}) {
            Task task = project.getTasks().findByName(taskName);
            if (task != null) {
                Set<File> files = getClasspathFromTask(task, taskName);
                if (!files.isEmpty()) {
                    return files;
                }
            }
        }
        return Collections.emptySet();
    }

    private static @NonNull Set<File> getClasspathFromTask(@NonNull Task task, @NonNull String taskName) {
        // Try "getClasspath" (KotlinCompile), then "getOptions().getBootstrapClasspath()"
        for (String methodName : new String[]{"getClasspath", "getLibraries"}) {
            try {
                Object result = invokeMethod(task, methodName);
                if (result instanceof FileCollection) {
                    return resolveFileCollection(taskName, (FileCollection) result);
                }
            } catch (Exception ignored) {
            }
        }
        return Collections.emptySet();
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Set<File> getMainSourceDirs(@NonNull Object androidExt) {
        try {
            // androidExt.sourceSets.getByName("main").java.srcDirs
            Object sourceSets = invokeMethod(androidExt, "getSourceSets");
            Object mainSet = invokeNamedMethod(sourceSets, "getByName", "main");
            if (mainSet == null) return Collections.emptySet();

            Set<File> allDirs = new HashSet<>();

            // java source dirs
            Object javaSet = invokeMethod(mainSet, "getJava");
            if (javaSet != null) {
                Object srcDirs = invokeMethod(javaSet, "getSrcDirs");
                if (srcDirs instanceof Set) {
                    allDirs.addAll((Set<File>) srcDirs);
                }
            }

            // kotlin source dirs (AGP adds "kotlin" extension to AndroidSourceSet in recent versions)
            try {
                Object kotlinSet = invokeMethod(mainSet, "getKotlin");
                if (kotlinSet != null) {
                    Object srcDirs = invokeMethod(kotlinSet, "getSrcDirs");
                    if (srcDirs instanceof Set) {
                        allDirs.addAll((Set<File>) srcDirs);
                    }
                }
            } catch (Exception ignored) {
                // kotlin extension may not exist on older AGP, that's fine
            }

            return allDirs;
        } catch (Exception e) {
            System.err.println("[kotlin-lsp] Could not get mainSourceDirs: " + e.getMessage());
            return Collections.emptySet();
        }
    }

    private static @NonNull Set<File> resolveFileCollection(
            @NonNull String context,
            @NonNull FileCollection fc
    ) {
        try {
            return fc.getFiles();
        } catch (Exception e) {
            System.err.println("[kotlin-lsp] Could not resolve FileCollection for "
                    + context + ": " + e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * Invokes a zero-or-more-argument method on obj by name, walking the class hierarchy.
     * Uses the first matching method by parameter count.
     */
    @SuppressWarnings("unchecked")
    private static <T> T invokeMethod(@NonNull Object obj, @NonNull String methodName, Object... args)
            throws Exception {
        for (Class<?> cls = obj.getClass(); cls != null; cls = cls.getSuperclass()) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return (T) m.invoke(obj, args);
                }
            }
        }
        // Also check interfaces
        for (Class<?> iface : getAllInterfaces(obj.getClass())) {
            for (Method m : iface.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return (T) m.invoke(obj, args);
                }
            }
        }
        throw new NoSuchMethodException("No method " + methodName + "(" + args.length + " args) on "
                + obj.getClass().getName());
    }

    /**
     * Variant of invokeMethod for single String argument (common pattern: getByName("main")).
     */
    private static @Nullable Object invokeNamedMethod(@NonNull Object obj, @NonNull String methodName,
            @NonNull String name) {
        try {
            return invokeMethod(obj, methodName, name);
        } catch (Exception e) {
            return null;
        }
    }

    private static @NonNull List<Class<?>> getAllInterfaces(@NonNull Class<?> cls) {
        List<Class<?>> result = new ArrayList<>();
        for (Class<?> iface : cls.getInterfaces()) {
            result.add(iface);
            result.addAll(getAllInterfaces(iface));
        }
        if (cls.getSuperclass() != null) {
            result.addAll(getAllInterfaces(cls.getSuperclass()));
        }
        return result;
    }
}
