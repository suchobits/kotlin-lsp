# Design: Phase 1 — Basic Android Classpath Resolution

**Issue**: kl-fi0
**Epic**: kl-dd4 (Phase 1: Basic Android classpath resolution)
**Status**: Design

---

## Goal

Eliminate `UNRESOLVED_REFERENCE` errors for `android.*`, `androidx.*`, and third-party
library APIs in Android Gradle projects. After Phase 1, completions and go-to-definition
will work for the Android framework and library APIs. R class, BuildConfig, and Compose
references will still error (Phase 3 and 4).

---

## Architecture Overview

The current JVM flow through GradleWorkspaceImporter:

```
GradleWorkspaceImporter
  → ProjectMetadataBuilder (BuildAction, runs in Gradle daemon)
      → fetches IdeaProject, KotlinModule, ModuleSourceSets per module
  → IdeaProjectMapper.toWorkspaceData(metadata)
      → splitModulePerSourceSet() → ContentRootData with source dirs + classpath
  → importWorkspaceData() → EntityStorage
```

Android modules currently fail at `ModuleSourceSetsModelBuilder.buildAll()` because
Android projects have no `SourceSetContainer` extension — only AGP's
`AndroidSourceSet`. The result: empty source sets, no classpath entries, all Android
API references unresolved.

The Phase 1 fix adds a parallel Android path:

```
GradleWorkspaceImporter
  → ProjectMetadataBuilder (BuildAction, runs in Gradle daemon)
      → also fetches AndroidModuleInfo per module (NEW)
  → IdeaProjectMapper.toWorkspaceData(metadata)   [unchanged for JVM modules]
  → AndroidProjectMapper.mergeAndroidData(workspaceData, metadata) (NEW)
      → adds LibraryData for android.jar and dependency JARs
      → adds SourceRootData for src/main/kotlin and src/main/java
  → importWorkspaceData() → EntityStorage
```

---

## Key Design Decision: No Compile-Time AGP Dependency

The `gradle-plugin` module (JVM target 1.8) runs inside the Gradle daemon, where the
AGP JARs are already on the buildscript classpath. We access Android-specific data via
two mechanisms:

1. **`project.extensions.findByName("android")`** — returns the Android extension
   object if AGP is applied; null otherwise. We access its properties via reflection.
2. **Task-based classpath extraction** — the `compileDebugKotlin` task's `classpath`
   `FileCollection` is already fully resolved by Gradle's transform pipeline, containing
   extracted `.jar` files from all dependencies including AARs. No manual AAR extraction
   needed.

This avoids any compile-time dependency on `com.android.tools.build:gradle-api` in
`BUILD.bazel`, which would require modifying the IntelliJ Bazel workspace.

---

## New Files

### gradle-plugin module

```
gradle-plugin/src/com/jetbrains/ls/imports/gradle/android/
  model/
    AndroidModuleInfo.java          — serializable interface
    impl/
      AndroidModuleInfoImpl.java    — concrete impl
  model/builder/
    AndroidModuleInfoModelBuilder.java  — ToolingModelBuilder
```

### workspace-import module

```
workspace-import/src/com/jetbrains/ls/imports/android/
  AndroidSdkDiscovery.kt      — SDK path resolution
  AndroidProjectMapper.kt     — AGP data → WorkspaceData additions
```

---

## Interface Definitions

### `AndroidModuleInfo.java`

```java
package com.jetbrains.ls.imports.gradle.android.model;

import java.io.File;
import java.io.Serializable;
import java.util.List;
import java.util.Set;

public interface AndroidModuleInfo extends Serializable {
    /** Boot classpath: android.jar and optional platform extras. */
    @NonNull List<File> getBootClasspath();

    /** Fully resolved compile classpath for the debug variant.
     *  Gradle has already extracted classes.jar from AARs via transforms. */
    @NonNull Set<File> getDebugCompileClasspath();

    /** Source directories from the main source set (java + kotlin). */
    @NonNull Set<File> getMainSourceDirs();

    /** e.g. "android-34" — used to locate android.jar in the SDK. */
    @NonNull String getCompileSdkVersion();

    /** true = com.android.application, false = com.android.library */
    boolean isApplication();
}
```

### `AndroidModuleInfoImpl.java`

Standard serializable POJO implementing `AndroidModuleInfo`. Follows the pattern of
`ModuleSourceSetImpl`.

---

## AndroidModuleInfoModelBuilder

Location: `gradle-plugin/src/.../android/model/builder/AndroidModuleInfoModelBuilder.java`

```java
public final class AndroidModuleInfoModelBuilder implements ToolingModelBuilder {

    private static final String TARGET_MODEL = AndroidModuleInfo.class.getName();

    @Override
    public boolean canBuild(String modelName) {
        return TARGET_MODEL.equals(modelName);
    }

    @Override
    public Object buildAll(String modelName, Project project) {
        Object androidExt = project.getExtensions().findByName("android");
        if (androidExt == null) return null;  // Not an Android module

        try {
            List<File> bootClasspath = invokeMethod(androidExt, "getBootClasspath");
            String compileSdkVersion = invokeMethod(androidExt, "getCompileSdkVersion");
            boolean isApp = androidExt.getClass().getName()
                .contains("AppExtension");  // vs LibraryExtension

            // Get compile classpath from compileDebugKotlin task
            Set<File> compileClasspath = getDebugCompileClasspath(project);

            // Get main source dirs
            Set<File> mainSourceDirs = getMainSourceDirs(androidExt);

            return new AndroidModuleInfoImpl(
                bootClasspath, compileClasspath, mainSourceDirs, compileSdkVersion, isApp
            );
        } catch (Exception e) {
            System.err.println("[kotlin-lsp] Failed to extract Android info for "
                + project.getName() + ": " + e.getMessage());
            return null;
        }
    }

    private static Set<File> getDebugCompileClasspath(Project project) {
        // Try compileDebugKotlin first, fall back to compileDebugJavaWithJavac
        for (String taskName : List.of("compileDebugKotlin", "compileDebugJavaWithJavac")) {
            Task task = project.getTasks().findByName(taskName);
            if (task != null) {
                try {
                    Object classpath = invokeMethod(task, "getClasspath");
                    if (classpath instanceof FileCollection) {
                        return resolveFiles(taskName, (FileCollection) classpath);
                    }
                } catch (Exception ignored) {}
            }
        }
        return Collections.emptySet();
    }

    private static Set<File> getMainSourceDirs(Object androidExt) {
        // androidExt.sourceSets.getByName("main").java.srcDirs
        // + androidExt.sourceSets.getByName("main").kotlin?.srcDirs (if Kotlin extension)
        try {
            Object sourceSets = invokeMethod(androidExt, "getSourceSets");
            Object mainSet = invokeMethod(sourceSets, "getByName", "main");
            Object javaSet = invokeMethod(mainSet, "getJava");
            Set<File> srcDirs = invokeMethod(javaSet, "getSrcDirs");
            // Kotlin source dirs may be in kotlin extension or same java dirs
            return srcDirs;
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokeMethod(Object obj, String methodName, Object... args) throws Exception {
        // Find method by name, handling type erasure and inheritance
        for (Class<?> cls = obj.getClass(); cls != null; cls = cls.getSuperclass()) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return (T) m.invoke(obj, args);
                }
            }
        }
        throw new NoSuchMethodException(methodName + " on " + obj.getClass().getName());
    }

    private static Set<File> resolveFiles(String context, FileCollection fc) {
        try {
            return fc.getFiles();
        } catch (Exception e) {
            System.err.println("[kotlin-lsp] Could not resolve " + context + ": " + e.getMessage());
            return Collections.emptySet();
        }
    }
}
```

---

## ProjectMetadata + ProjectMetadataBuilder Changes

### `ProjectMetadata.java` — add field

```java
public final class ProjectMetadata implements Serializable {
    // ... existing fields ...
    private final @NonNull Map<String, AndroidModuleInfo> androidModules;  // NEW

    public @NonNull Map<String, AndroidModuleInfo> getAndroidModules() {
        return androidModules;
    }
}
```

### `ProjectMetadataBuilder.java` — fetch AndroidModuleInfo

In `fetchProjectData()`, after fetching `KotlinModule` and `ModuleSourceSets`:

```java
AndroidModuleInfo androidInfo =
    unwrapFetchedModel(controller.fetch(module, AndroidModuleInfo.class));
if (androidInfo != null) {
    androidModules.put(moduleFqdn, androidInfo);
}
```

---

## IdeaGradleLspPlugin.java — register new builder

```java
@Override
public void apply(@NonNull Gradle target) {
    registry.register(new KotlinMetadataModelBuilder());
    registry.register(new ModuleSourceSetsModelBuilder());
    registry.register(new AndroidModuleInfoModelBuilder());  // NEW
}
```

---

## Server Side: AndroidSdkDiscovery.kt

```kotlin
package com.jetbrains.ls.imports.android

object AndroidSdkDiscovery {

    fun findSdk(projectDirectory: Path): Path? {
        return fromLocalProperties(projectDirectory)
            ?: fromEnv("ANDROID_HOME")
            ?: fromEnv("ANDROID_SDK_ROOT")
            ?: platformDefault()
    }

    private fun fromLocalProperties(projectRoot: Path): Path? {
        val props = projectRoot / "local.properties"
        if (!props.exists()) return null
        val properties = Properties().apply {
            props.toFile().bufferedReader().use { load(it) }
        }
        return properties.getProperty("sdk.dir")?.let { Path.of(it) }?.takeIf { it.exists() }
    }

    private fun fromEnv(varName: String): Path? =
        System.getenv(varName)?.let { Path.of(it) }?.takeIf { it.exists() }

    private fun platformDefault(): Path? {
        val candidates = when {
            System.getProperty("os.name").contains("Mac") ->
                listOf(Path.of(System.getProperty("user.home"), "Library", "Android", "sdk"))
            System.getProperty("os.name").contains("Windows") -> {
                val localAppData = System.getenv("LOCALAPPDATA") ?: return null
                listOf(Path.of(localAppData, "Android", "Sdk"))
            }
            else -> listOf(Path.of(System.getProperty("user.home"), "Android", "Sdk"))
        }
        return candidates.firstOrNull { it.exists() }
    }
}
```

---

## Server Side: AndroidProjectMapper.kt

This class takes `ProjectMetadata` (which now includes `androidModules`) and merges
Android-specific `WorkspaceData` into the existing `WorkspaceData`.

```kotlin
package com.jetbrains.ls.imports.android

class AndroidProjectMapper {

    fun merge(base: WorkspaceData, metadata: ProjectMetadata, projectDir: Path): WorkspaceData {
        if (metadata.androidModules.isEmpty()) return base

        val extraLibraries = mutableListOf<LibraryData>()
        val moduleUpdates = mutableMapOf<String, ModuleData>()

        for ((moduleName, androidInfo) in metadata.androidModules) {
            // 1. Add boot classpath JARs (android.jar etc.) as a library
            val bootLibrary = LibraryData(
                name = "android-sdk-${androidInfo.compileSdkVersion}",
                module = moduleName,
                type = "COMPILE",
                roots = androidInfo.bootClasspath.map { LibraryRootData(it.path, "CLASSES") }
            )
            extraLibraries.add(bootLibrary)

            // 2. Add all compile classpath JARs (extracted AARs + regular JARs)
            //    Skip entries already in the boot classpath to avoid duplicates
            val bootPaths = androidInfo.bootClasspath.map { it.path }.toSet()
            val depJars = androidInfo.debugCompileClasspath
                .filter { it.path !in bootPaths }
                .filter { it.exists() && it.extension == "jar" }

            depJars.forEachIndexed { idx, jar ->
                extraLibraries.add(
                    LibraryData(
                        name = "android-dep-${moduleName}-${idx}",
                        module = moduleName,
                        type = "COMPILE",
                        roots = listOf(LibraryRootData(jar.path, "CLASSES"))
                    )
                )
            }

            // 3. Build source roots for the module
            //    Find or create the module in base.modules, replace/augment its contentRoots
            val sourceRoots = androidInfo.mainSourceDirs
                .filter { it.exists() && it.isDirectory }
                .map { SourceRootData(it.path, "java-source") }

            if (sourceRoots.isNotEmpty()) {
                val commonRoot = sourceRoots.map { it.path }.findCommonPrefix()
                    .ifEmpty { "$projectDir/src/main" }

                // Build dependency references
                val libDeps = mutableListOf<DependencyData>(
                    DependencyData.Library(bootLibrary.name, DependencyDataScope.COMPILE, false)
                )
                depJars.forEachIndexed { idx, _ ->
                    libDeps.add(DependencyData.Library(
                        "android-dep-${moduleName}-${idx}", DependencyDataScope.COMPILE, false
                    ))
                }
                libDeps.add(DependencyData.InheritedSdk)
                libDeps.add(DependencyData.ModuleSource)

                moduleUpdates[moduleName] = ModuleData(
                    name = moduleName,
                    dependencies = libDeps,
                    contentRoots = listOf(ContentRootData(
                        path = commonRoot,
                        sourceRoots = sourceRoots
                    ))
                )
            }
        }

        // Merge: replace android modules in base, add extra libraries
        val mergedModules = base.modules.map { m ->
            moduleUpdates[m.name] ?: m
        }
        return base.copy(
            modules = mergedModules,
            libraries = base.libraries + extraLibraries
        )
    }
}
```

---

## GradleWorkspaceImporter Changes

After `IdeaProjectMapper().toWorkspaceData(gradleProjectData)` produces the base
`WorkspaceData`, apply Android mapper if Android modules are present:

```kotlin
// In GradleWorkspaceImporter.importWorkspace():
val baseWorkspaceData = IdeaProjectMapper().toWorkspaceData(gradleProjectData)
val workspaceData = if (gradleProjectData.androidModules.isNotEmpty()) {
    AndroidProjectMapper().merge(baseWorkspaceData, gradleProjectData, projectDirectory)
} else {
    baseWorkspaceData
}
return MutableEntityStorage.create().apply {
    importWorkspaceData(
        postProcessWorkspaceData(workspaceData, projectDirectory, onUnresolvedDependency),
        ...
    )
    fixMissingProjectSdk(defaultSdkPath, virtualFileUrlManager)
}
```

---

## BUILD.bazel Changes

### gradle-plugin/BUILD.bazel

No new dependencies required — we use reflection to access AGP types. The
`@ultimate_lib//:gradle-provided` already includes the Gradle Plugin API needed to
access `Project`, `Task`, and `FileCollection`.

### workspace-import/BUILD.bazel

No new external dependencies — `AndroidProjectMapper` and `AndroidSdkDiscovery` only
use JDK standard library types plus types already in scope
(`com.jetbrains.ls.imports.json.*`, `com.intellij.*`).

---

## IML Changes

### gradle-plugin IML

No changes needed (no new compile-time deps).

### workspace-import IML

No changes needed.

---

## Edge Cases and Risks

### Risk 1: Reflection failures across AGP versions

The reflection approach is fragile if method signatures change across AGP versions.
**Mitigation**: wrap all reflection calls in try/catch, log warnings, return empty
collections on failure. The worst case is an Android module with no classpath (same as
today — no regression).

### Risk 2: compileDebugKotlin task may not exist at configuration time

Some projects defer task creation. `tasks.findByName()` may return null.
**Mitigation**: fall back to `compileDebugJavaWithJavac` → then to empty set with warning.

### Risk 3: Gradle configuration cache / lazy tasks

On Gradle 8+ with configuration cache enabled, resolving `FileCollection.getFiles()`
inside a `BuildAction` triggers configuration cache problems.
**Mitigation**: use try/catch with `resolveFiles()` helper. On failure, fall back to
IdeaProject dependency listing (the same fallback used by `resolveDependenciesFromIdeaModule`).

### Risk 4: Multi-module projects where library modules are `com.android.library`

`isApplication()` check is purely informational for Phase 1. Both app and library
modules get the same classpath treatment.
**No issue** — the approach is identical for both.

### Risk 5: AAR files in the compile classpath

Gradle's transform pipeline should resolve AARs to JARs before `compileDebugKotlin`
runs. If for some reason a raw `.aar` appears, we filter it out (`it.extension == "jar"`).
R classes from AARs will still be missing — handled in Phase 3.

### Risk 6: compileSdkVersion as "android-34" string

`getCompileSdkVersion()` on `BaseExtension` returns `"android-34"` as a string. The
`AndroidSdkDiscovery` needs to parse this to locate `$ANDROID_HOME/platforms/android-34/android.jar`.
The bootClasspath returned by `getBootClasspath()` already includes the full path to
`android.jar` so `AndroidSdkDiscovery` is a fallback / informational only in Phase 1.

---

## Files to Create/Modify — Summary

| Action | File |
|--------|------|
| CREATE | `gradle-plugin/src/.../android/model/AndroidModuleInfo.java` |
| CREATE | `gradle-plugin/src/.../android/model/impl/AndroidModuleInfoImpl.java` |
| CREATE | `gradle-plugin/src/.../android/model/builder/AndroidModuleInfoModelBuilder.java` |
| MODIFY | `gradle-plugin/src/.../gradle/IdeaGradleLspPlugin.java` — register new builder |
| MODIFY | `gradle-plugin/src/.../gradle/action/ProjectMetadata.java` — add androidModules field |
| MODIFY | `gradle-plugin/src/.../gradle/action/ProjectMetadataBuilder.java` — fetch AndroidModuleInfo |
| CREATE | `workspace-import/src/.../android/AndroidSdkDiscovery.kt` |
| CREATE | `workspace-import/src/.../android/AndroidProjectMapper.kt` |
| MODIFY | `workspace-import/src/.../gradle/GradleWorkspaceImporter.kt` — delegate to AndroidProjectMapper |

---

## Expected Outcome

After Phase 1:
- `android.*` and `androidx.*` APIs resolve — no more `UNRESOLVED_REFERENCE` for framework types
- Third-party library types (Retrofit, Room entity stubs, etc.) resolve
- Go-to-definition works for Android framework and library classes
- Standard Kotlin diagnostics work correctly

Still broken (to be fixed in later phases):
- `R.string.*`, `R.layout.*`, `BuildConfig.*` — Phase 3
- `@Composable` type errors — Phase 4
- Variant-specific source sets (src/debug/kotlin/) — Phase 2
