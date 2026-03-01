// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.android.model.builder;

import com.jetbrains.ls.imports.gradle.android.model.AndroidModuleInfo;
import com.jetbrains.ls.imports.gradle.android.model.AndroidVariantInfo;
import com.jetbrains.ls.imports.gradle.android.model.impl.AndroidModuleInfoImpl;
import com.jetbrains.ls.imports.gradle.android.model.impl.AndroidVariantInfoImpl;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            // Phase 1 fields (unchanged)
            List<File> bootClasspath = getBootClasspath(androidExt);
            String compileSdkVersion = getCompileSdkVersion(androidExt);
            boolean isApp = androidExt.getClass().getName().contains("AppExtension");
            Set<File> mainSourceDirs = getMainSourceDirs(androidExt);
            Set<File> debugCompileClasspath = getVariantCompileClasspath(project, "debug");

            // Phase 2: extract all variants
            List<String> variantNames = new ArrayList<>();
            Map<String, AndroidVariantInfo> variants = new LinkedHashMap<>();

            Object variantList = getApplicationOrLibraryVariants(androidExt, isApp);
            if (variantList instanceof Iterable) {
                for (Object variant : (Iterable<?>) variantList) {
                    try {
                        String name = (String) invokeMethod(variant, "getName");
                        String buildType = getBuildTypeName(variant);

                        List<String> flavors = getProductFlavorNames(variant);

                        Set<File> variantSourceDirs = getVariantSourceDirs(androidExt, name);
                        Set<File> variantClasspath = getVariantCompileClasspath(project, name);
                        boolean debuggable = getBooleanSafe(variant, "getDebuggable");

                        variantNames.add(name);
                        variants.put(name, new AndroidVariantInfoImpl(
                                name, buildType, flavors, variantSourceDirs, variantClasspath, debuggable
                        ));
                    } catch (Exception e) {
                        System.err.println("[kotlin-lsp] Failed to extract variant info: " + e.getMessage());
                    }
                }
            }

            return new AndroidModuleInfoImpl(
                    bootClasspath,
                    debugCompileClasspath,
                    mainSourceDirs,
                    compileSdkVersion,
                    isApp,
                    variantNames,
                    variants
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

    /**
     * Get applicationVariants (for app) or libraryVariants (for library).
     */
    private static @Nullable Object getApplicationOrLibraryVariants(
            @NonNull Object androidExt, boolean isApp) {
        try {
            String method = isApp ? "getApplicationVariants" : "getLibraryVariants";
            return invokeMethod(androidExt, method);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Get compile classpath for a specific variant by finding its compile task.
     */
    private static @NonNull Set<File> getVariantCompileClasspath(
            @NonNull Project project, @NonNull String variantName) {
        String capitalized = variantName.substring(0, 1).toUpperCase() + variantName.substring(1);
        for (String prefix : List.of("compile" + capitalized + "Kotlin",
                                      "compile" + capitalized + "JavaWithJavac")) {
            Task task = project.getTasks().findByName(prefix);
            if (task != null) {
                Set<File> files = getClasspathFromTask(task, prefix);
                if (!files.isEmpty()) {
                    return files;
                }
            }
        }
        return Collections.emptySet();
    }

    private static @NonNull Set<File> getClasspathFromTask(@NonNull Task task, @NonNull String taskName) {
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
            Object sourceSets = invokeMethod(androidExt, "getSourceSets");
            Object mainSet = invokeNamedMethod(sourceSets, "getByName", "main");
            if (mainSet == null) return Collections.emptySet();

            Set<File> allDirs = new HashSet<>();

            Object javaSet = invokeMethod(mainSet, "getJava");
            if (javaSet != null) {
                Object srcDirs = invokeMethod(javaSet, "getSrcDirs");
                if (srcDirs instanceof Set) {
                    allDirs.addAll((Set<File>) srcDirs);
                }
            }

            try {
                Object kotlinSet = invokeMethod(mainSet, "getKotlin");
                if (kotlinSet != null) {
                    Object srcDirs = invokeMethod(kotlinSet, "getSrcDirs");
                    if (srcDirs instanceof Set) {
                        allDirs.addAll((Set<File>) srcDirs);
                    }
                }
            } catch (Exception ignored) {
            }

            return allDirs;
        } catch (Exception e) {
            System.err.println("[kotlin-lsp] Could not get mainSourceDirs: " + e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * Get source directories for a specific variant's source set.
     * Returns dirs from the variant-specific source set only (not merged).
     */
    @SuppressWarnings("unchecked")
    private static @NonNull Set<File> getVariantSourceDirs(
            @NonNull Object androidExt, @NonNull String variantName) {
        try {
            Object sourceSets = invokeMethod(androidExt, "getSourceSets");
            Object variantSet = invokeNamedMethod(sourceSets, "findByName", variantName);
            if (variantSet == null) return Collections.emptySet();

            Set<File> dirs = new HashSet<>();

            Object javaSet = invokeMethod(variantSet, "getJava");
            if (javaSet != null) {
                Object srcDirs = invokeMethod(javaSet, "getSrcDirs");
                if (srcDirs instanceof Set) {
                    dirs.addAll((Set<File>) srcDirs);
                }
            }

            try {
                Object kotlinSet = invokeMethod(variantSet, "getKotlin");
                if (kotlinSet != null) {
                    Object srcDirs = invokeMethod(kotlinSet, "getSrcDirs");
                    if (srcDirs instanceof Set) {
                        dirs.addAll((Set<File>) srcDirs);
                    }
                }
            } catch (Exception ignored) {
            }

            return dirs;
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    private static @NonNull String getBuildTypeName(@NonNull Object variant) {
        try {
            Object buildType = invokeMethod(variant, "getBuildType");
            if (buildType instanceof String) {
                return (String) buildType;
            }
            return (String) invokeMethod(buildType, "getName");
        } catch (Exception e) {
            return "debug";
        }
    }

    @SuppressWarnings("unchecked")
    private static @NonNull List<String> getProductFlavorNames(@NonNull Object variant) {
        List<String> flavors = new ArrayList<>();
        try {
            Iterable<?> flavorList = (Iterable<?>) invokeMethod(variant, "getProductFlavors");
            for (Object flavor : flavorList) {
                flavors.add((String) invokeMethod(flavor, "getName"));
            }
        } catch (Exception ignored) {
        }
        return flavors;
    }

    private static boolean getBooleanSafe(@NonNull Object obj, @NonNull String methodName) {
        try {
            Object result = invokeMethod(obj, methodName);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
        } catch (Exception ignored) {
        }
        return false;
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
