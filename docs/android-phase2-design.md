# Design: Phase 2 — Variant-Aware Source Sets

**Issue**: kl-7fw
**Epic**: kl-vyx (Phase 2: Build variant awareness and source set resolution)
**Blocked by**: Phase 1 (kl-fi0) — implemented
**Blocks**: Phase 3 (generated source discovery depends on active variant)

---

## Goal

Resolve source files in variant-specific source sets (`src/debug/kotlin/`,
`src/release/java/`, `src/freeDebug/kotlin/`, etc.) and provide the correct
compile classpath per variant. After Phase 2, a user working on
`src/debug/kotlin/DebugLogger.kt` gets full completions and diagnostics, and
switching from `debug` to `release` updates both source roots and classpath.

Phase 1 hardcodes everything to the `debug` variant and the `main` source set.
Phase 2 removes that limitation.

---

## Architecture Overview

Phase 2 extends the Phase 1 pipeline at three points:

```
GradleWorkspaceImporter
  → ProjectMetadataBuilder (BuildAction, runs in Gradle daemon)
      → AndroidModuleInfoModelBuilder now extracts ALL variants (NEW)
  → IdeaProjectMapper.toWorkspaceData(metadata)   [unchanged]
  → AndroidProjectMapper.merge(workspaceData, metadata, projectDir) (CHANGED)
      → selects active variant (default=debug, overridable)
      → merges source sets in correct order
      → uses variant-specific classpath
  → importWorkspaceData() → EntityStorage
```

---

## 1. Extending AndroidModuleInfo with Variant Data

### New interface additions

```java
public interface AndroidModuleInfo extends Serializable {
    // ... existing Phase 1 methods ...

    /** All build variant names, e.g. ["debug", "release", "freeDebug", "paidRelease"]. */
    @NonNull List<String> getVariantNames();

    /** Per-variant data: source directories, compile classpath, build type, flavors. */
    @NonNull Map<String, AndroidVariantInfo> getVariants();
}
```

### New AndroidVariantInfo interface

```java
package com.jetbrains.ls.imports.gradle.android.model;

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
```

### Concrete implementation

`AndroidVariantInfoImpl.java` — standard serializable POJO, same pattern as
`AndroidModuleInfoImpl`. Constructor takes all six fields.

### Backward compatibility

The existing Phase 1 methods (`getDebugCompileClasspath()`, `getMainSourceDirs()`)
remain on the interface for backward compatibility. Phase 3 and later code should
migrate to `getVariants().get(activeVariant)` instead.

---

## 2. Variant Selection Strategy

### Default: `debug`

The debug variant is the sensible default because:
- Every Android project has it (it's built-in, unlike product flavors)
- It's what developers iterate on day-to-day
- It typically has the most permissive classpath (debug-only testing libs, etc.)
- Android Studio also defaults to debug

### Override: `kotlin-lsp-config.json`

A new optional config file at the project root allows overriding the active
variant per module:

```json
{
  "android": {
    "activeVariant": "freeDebug",
    "moduleVariants": {
      "app": "paidRelease",
      "feature-login": "freeDebug"
    }
  }
}
```

Resolution order (first match wins):
1. `moduleVariants[moduleName]` — per-module override
2. `activeVariant` — project-wide override
3. `"debug"` — hardcoded default

### Config loading

Create `workspace-import/src/.../android/AndroidLspConfig.kt`:

```kotlin
object AndroidLspConfig {
    data class Config(
        val activeVariant: String = "debug",
        val moduleVariants: Map<String, String> = emptyMap()
    )

    fun load(projectDir: Path): Config {
        val configFile = projectDir / "kotlin-lsp-config.json"
        if (!configFile.exists()) return Config()

        return try {
            val json = Json.parseToJsonElement(configFile.readText()).jsonObject
            val android = json["android"]?.jsonObject ?: return Config()
            Config(
                activeVariant = android["activeVariant"]?.jsonPrimitive?.content ?: "debug",
                moduleVariants = android["moduleVariants"]?.jsonObject
                    ?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            )
        } catch (e: Exception) {
            System.err.println("[kotlin-lsp] Failed to parse kotlin-lsp-config.json: ${e.message}")
            Config()
        }
    }

    fun resolveVariant(config: Config, moduleName: String): String {
        return config.moduleVariants[moduleName]
            ?: config.activeVariant
    }
}
```

### Validation

If the configured variant doesn't exist in `AndroidModuleInfo.getVariantNames()`,
log a warning and fall back to `"debug"`. If `"debug"` also doesn't exist (very
unusual), fall back to the first variant in `getVariantNames()`.

```kotlin
fun resolveValidVariant(config: Config, moduleName: String, available: List<String>): String {
    val requested = resolveVariant(config, moduleName)
    if (requested in available) return requested

    System.err.println("[kotlin-lsp] Variant '$requested' not found for module '$moduleName'. " +
        "Available: $available. Falling back to 'debug'.")
    if ("debug" in available) return "debug"
    return available.firstOrNull() ?: "debug"
}
```

Phase 5's `kotlin/selectBuildVariant` LSP command will update an in-memory
`AndroidVariantState` that overrides the config file at runtime. Phase 2 lays
the groundwork; Phase 5 adds the live-switching command.

---

## 3. Source Set Merge Order Algorithm

Android source sets merge in a specific order defined by AGP convention. Files
in more-specific source sets shadow files in less-specific ones. For the LSP,
we include ALL source directories (no shadowing at the source root level — the
Kotlin compiler and Analysis API handle declaration conflicts).

### Merge order (most specific → least specific)

For a variant like `freeDebug` (flavor=`free`, buildType=`debug`):

```
1. src/freeDebug/    ← variant-specific (flavor + buildType combined)
2. src/debug/        ← buildType-specific
3. src/free/         ← flavor-specific
4. src/main/         ← common/default
```

For a single-dimension variant like `debug` (no product flavors):

```
1. src/debug/        ← buildType-specific
2. src/main/         ← common/default
```

For multi-flavor variants (e.g., `freeStagingDebug` with dimensions `tier` and
`environment`):

```
1. src/freeStagingDebug/    ← full variant
2. src/debug/               ← buildType
3. src/freeStaging/         ← all flavors combined
4. src/staging/             ← flavor (dimension: environment)
5. src/free/                ← flavor (dimension: tier)
6. src/main/                ← common
```

### Implementation

```kotlin
object SourceSetMergeOrder {

    /**
     * Returns source set names in merge order (most specific first)
     * for the given variant configuration.
     */
    fun computeMergeOrder(
        variantName: String,
        buildType: String,
        productFlavors: List<String>  // in dimension order
    ): List<String> {
        val result = mutableListOf<String>()

        // 1. Variant-specific (only if there are flavors — otherwise it equals the buildType)
        if (productFlavors.isNotEmpty()) {
            result.add(variantName)  // e.g. "freeDebug"
        }

        // 2. BuildType-specific
        result.add(buildType)  // e.g. "debug"

        // 3. Flavor combinations (if multiple flavor dimensions)
        if (productFlavors.size > 1) {
            // Combined flavor name (all flavors concatenated)
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
}
```

### Source directory resolution

For each source set name in the merge order, check for directories:
- `src/{sourceSetName}/java/`
- `src/{sourceSetName}/kotlin/`
- `src/{sourceSetName}/res/` (for Phase 3 R class generation)

Only include directories that actually exist on disk.

```kotlin
fun resolveSourceDirs(moduleDir: Path, mergeOrder: List<String>): List<SourceRootData> {
    val roots = mutableListOf<SourceRootData>()
    for (sourceSetName in mergeOrder) {
        val base = moduleDir / "src" / sourceSetName
        val javaDir = base / "java"
        val kotlinDir = base / "kotlin"

        if (javaDir.exists() && javaDir.isDirectory()) {
            roots.add(SourceRootData(javaDir.toString(), "java-source"))
        }
        if (kotlinDir.exists() && kotlinDir.isDirectory()) {
            roots.add(SourceRootData(kotlinDir.toString(), "kotlin-source"))
        }
    }
    return roots
}
```

---

## 4. Per-Variant Classpath Differences

Different variants have different compile classpaths because Gradle supports
configuration-scoped dependencies:

```groovy
dependencies {
    implementation("com.example:core:1.0")              // all variants
    debugImplementation("com.example:debug-tools:1.0")  // debug only
    releaseImplementation("com.example:proguard:1.0")   // release only
    freeImplementation("com.example:ads:1.0")           // free flavor only
    paidImplementation("com.example:premium:1.0")       // paid flavor only
    freeDebugImplementation("com.example:test-ads:1.0") // freeDebug only
}
```

### How it works in the model

Each `AndroidVariantInfo.getCompileClasspath()` returns the fully resolved
classpath for that specific variant. Gradle's dependency resolution has already
merged the configuration hierarchy:

```
freeDebugCompileClasspath = implementation
                          + debugImplementation
                          + freeImplementation
                          + freeDebugImplementation
                          + api (transitive)
```

The `AndroidModuleInfoModelBuilder` extracts this per-variant by looking up the
compile task for each variant (see Section 6).

### Impact on AndroidProjectMapper

`AndroidProjectMapper.merge()` currently uses `getDebugCompileClasspath()` for
all Android modules. Phase 2 changes this to:

```kotlin
// Instead of:
val classpath = androidInfo.debugCompileClasspath

// Use:
val activeVariant = AndroidLspConfig.resolveValidVariant(config, moduleName, androidInfo.variantNames)
val variantInfo = androidInfo.variants[activeVariant]
val classpath = variantInfo?.compileClasspath ?: androidInfo.debugCompileClasspath  // fallback
```

The library creation logic (`android-dep-{moduleName}-{idx}`) is unchanged —
it just receives a different set of JARs depending on the active variant.

---

## 5. Multi-Module Inter-Variant Dependency Resolution

In multi-module Android projects, modules depend on each other:

```groovy
// :app/build.gradle
dependencies {
    implementation(project(":feature-login"))
    implementation(project(":core"))
}
```

### The problem

When `:app` uses variant `freeDebug`, its dependency on `:feature-login` should
resolve to `:feature-login`'s `freeDebug` variant (or the best matching variant).
AGP handles this via variant-aware dependency resolution — the resolved compile
classpath for `:app:freeDebug` already includes the correct outputs from
`:feature-login:freeDebug`.

### Why this mostly works already

The compile classpath extracted from `compile{Variant}Kotlin.classpath` is
**fully resolved** by Gradle. It includes:
- External dependency JARs (from Maven/local)
- Project dependency outputs (the compiled classes from dependent modules)

So if we select `freeDebug` for `:app`, the classpath already contains
`:feature-login`'s freeDebug output. **No additional inter-module variant
matching logic is needed in the LSP.**

### What we DO need: consistent variant selection across modules

The LSP must present **source roots** from all modules. If `:app` uses
`freeDebug`, the developer expects `:feature-login`'s `src/freeDebug/kotlin/`
source files to also be visible and analyzed.

The `kotlin-lsp-config.json` `activeVariant` field applies project-wide,
ensuring all modules use the same variant (unless overridden per-module via
`moduleVariants`). This is sufficient for most projects.

### Edge case: variant mismatch across modules

If module A has variants `[freeDebug, freeRelease, paidDebug, paidRelease]` but
module B only has `[debug, release]` (no product flavors), requesting
`freeDebug` for module B fails.

Resolution strategy:
1. Try the exact variant name (`freeDebug`)
2. Try the build type only (`debug`) — this handles the common case where
   library modules have no product flavors
3. Fall back to the first available variant

```kotlin
fun resolveVariantForModule(
    requestedVariant: String,
    requestedBuildType: String,
    availableVariants: List<String>
): String {
    // Exact match
    if (requestedVariant in availableVariants) return requestedVariant
    // Build type match
    if (requestedBuildType in availableVariants) return requestedBuildType
    // First available
    return availableVariants.firstOrNull() ?: "debug"
}
```

### Multi-module source navigation

When the user navigates from `:app` code into `:feature-login` source, they
should land in the correct variant's source set. Since both modules' source
roots are registered in the workspace model (with consistent variant selection),
go-to-definition naturally navigates to the correct file.

---

## 6. Changes to AndroidModuleInfoModelBuilder

### Current state (Phase 1)

The builder hardcodes `compileDebugKotlin` / `compileDebugJavaWithJavac` for
classpath extraction and `sourceSets.getByName("main")` for source dirs.

### Phase 2 changes

Extract variant data from AGP's variant API via reflection:

```java
@Override
public Object buildAll(String modelName, Project project) {
    Object androidExt = project.getExtensions().findByName("android");
    if (androidExt == null) return null;

    try {
        // Phase 1 fields (unchanged)
        List<File> bootClasspath = invokeMethod(androidExt, "getBootClasspath");
        String compileSdkVersion = getCompileSdkVersionSafe(androidExt);
        boolean isApp = androidExt.getClass().getName().contains("AppExtension");
        Set<File> mainSourceDirs = getMainSourceDirs(androidExt);
        Set<File> debugCompileClasspath = getVariantCompileClasspath(project, "debug");

        // Phase 2: extract all variants
        List<String> variantNames = new ArrayList<>();
        Map<String, AndroidVariantInfo> variants = new LinkedHashMap<>();

        Object variantList = getApplicationOrLibraryVariants(androidExt, isApp);
        if (variantList instanceof Iterable) {
            for (Object variant : (Iterable<?>) variantList) {
                String name = (String) invokeMethod(variant, "getName");
                String buildType = (String) invokeMethod(
                    invokeMethod(variant, "getBuildType"), "getName");

                List<String> flavors = new ArrayList<>();
                try {
                    Iterable<?> flavorList = invokeMethod(variant, "getProductFlavors");
                    for (Object flavor : flavorList) {
                        flavors.add((String) invokeMethod(flavor, "getName"));
                    }
                } catch (Exception ignored) {}

                Set<File> variantSourceDirs = getVariantSourceDirs(androidExt, name);
                Set<File> variantClasspath = getVariantCompileClasspath(project, name);
                boolean debuggable = getBooleanSafe(variant, "getDebuggable", false);

                variantNames.add(name);
                variants.put(name, new AndroidVariantInfoImpl(
                    name, buildType, flavors, variantSourceDirs, variantClasspath, debuggable
                ));
            }
        }

        return new AndroidModuleInfoImpl(
            bootClasspath, debugCompileClasspath, mainSourceDirs,
            compileSdkVersion, isApp, variantNames, variants
        );
    } catch (Exception e) {
        System.err.println("[kotlin-lsp] Failed to extract Android info: " + e.getMessage());
        return null;
    }
}
```

### Helper methods

```java
/**
 * Get applicationVariants (for app) or libraryVariants (for library).
 */
private static Object getApplicationOrLibraryVariants(Object androidExt, boolean isApp) {
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
private static Set<File> getVariantCompileClasspath(Project project, String variantName) {
    String capitalized = variantName.substring(0, 1).toUpperCase() + variantName.substring(1);
    for (String prefix : List.of("compile" + capitalized + "Kotlin",
                                  "compile" + capitalized + "JavaWithJavac")) {
        Task task = project.getTasks().findByName(prefix);
        if (task != null) {
            try {
                Object classpath = invokeMethod(task, "getClasspath");
                if (classpath instanceof FileCollection) {
                    return resolveFiles(prefix, (FileCollection) classpath);
                }
            } catch (Exception ignored) {}
        }
    }
    return Collections.emptySet();
}

/**
 * Get source directories for a specific variant's source set.
 * Returns dirs from the variant-specific source set only (not merged).
 */
private static Set<File> getVariantSourceDirs(Object androidExt, String variantName) {
    try {
        Object sourceSets = invokeMethod(androidExt, "getSourceSets");
        Object variantSet = invokeMethod(sourceSets, "findByName", variantName);
        if (variantSet == null) return Collections.emptySet();

        Set<File> dirs = new LinkedHashSet<>();
        Object javaSet = invokeMethod(variantSet, "getJava");
        dirs.addAll(invokeMethod(javaSet, "getSrcDirs"));

        try {
            Object kotlinSet = invokeMethod(variantSet, "getKotlin");
            dirs.addAll(invokeMethod(kotlinSet, "getSrcDirs"));
        } catch (Exception ignored) {
            // Kotlin source set extension may not exist
        }

        return dirs;
    } catch (Exception e) {
        return Collections.emptySet();
    }
}
```

### Performance consideration

Extracting all variants adds N variant iterations inside `buildAll()`. Each
variant's classpath resolution (`FileCollection.getFiles()`) is the expensive
part. For projects with many variants (e.g., 4 flavors x 2 build types = 8
variants), this means 8 classpath resolutions vs. the current 1.

**Mitigation**: Only resolve the classpath for variants we'll actually need.
In `buildAll()`, we can lazily skip classpath resolution and let
`AndroidProjectMapper` request only the active variant's classpath. However,
since `buildAll()` runs in the Gradle daemon (which has the full resolution
graph cached), the incremental cost is minimal. We extract all variants eagerly
for simplicity and to support Phase 5's instant variant switching.

---

## New Files

```
gradle-plugin/src/com/jetbrains/ls/imports/gradle/android/
  model/
    AndroidVariantInfo.java          — new serializable interface
    impl/
      AndroidVariantInfoImpl.java    — concrete impl
workspace-import/src/com/jetbrains/ls/imports/android/
  AndroidLspConfig.kt               — kotlin-lsp-config.json loader
  SourceSetMergeOrder.kt             — merge order algorithm
```

---

## Files to Create/Modify — Summary

| Action | File |
|--------|------|
| CREATE | `gradle-plugin/src/.../android/model/AndroidVariantInfo.java` |
| CREATE | `gradle-plugin/src/.../android/model/impl/AndroidVariantInfoImpl.java` |
| MODIFY | `gradle-plugin/src/.../android/model/AndroidModuleInfo.java` — add `getVariantNames()`, `getVariants()` |
| MODIFY | `gradle-plugin/src/.../android/model/impl/AndroidModuleInfoImpl.java` — add new fields |
| MODIFY | `gradle-plugin/src/.../android/model/builder/AndroidModuleInfoModelBuilder.java` — extract all variants |
| CREATE | `workspace-import/src/.../android/AndroidLspConfig.kt` — config file parser |
| CREATE | `workspace-import/src/.../android/SourceSetMergeOrder.kt` — merge order algorithm |
| MODIFY | `workspace-import/src/.../android/AndroidProjectMapper.kt` — use variant-aware source sets and classpath |

---

## AndroidProjectMapper Changes

The core change in `AndroidProjectMapper.merge()`:

```kotlin
fun merge(base: WorkspaceData, metadata: ProjectMetadata, projectDir: Path): WorkspaceData {
    if (metadata.androidModules.isEmpty()) return base

    val config = AndroidLspConfig.load(projectDir)
    val extraLibraries = mutableListOf<LibraryData>()
    val moduleUpdates = mutableMapOf<String, ModuleData>()

    for ((moduleName, androidInfo) in metadata.androidModules) {
        // Determine active variant
        val activeVariant = AndroidLspConfig.resolveValidVariant(
            config, moduleName, androidInfo.variantNames
        )
        val variantInfo = androidInfo.variants[activeVariant]

        // 1. Boot classpath library (unchanged from Phase 1)
        val bootLibrary = createBootLibrary(moduleName, androidInfo)
        extraLibraries.add(bootLibrary)

        // 2. Variant-specific compile classpath
        val classpath = variantInfo?.compileClasspath
            ?: androidInfo.debugCompileClasspath  // Phase 1 fallback
        val bootPaths = androidInfo.bootClasspath.map { it.path }.toSet()
        val depJars = classpath.filter { it.path !in bootPaths && it.exists() && it.extension == "jar" }

        depJars.forEachIndexed { idx, jar ->
            extraLibraries.add(LibraryData(
                name = "android-dep-$moduleName-$idx",
                module = moduleName,
                type = "COMPILE",
                roots = listOf(LibraryRootData(jar.path, "CLASSES"))
            ))
        }

        // 3. Merged source roots from all source sets in merge order
        val buildType = variantInfo?.buildType ?: "debug"
        val flavors = variantInfo?.productFlavors ?: emptyList()
        val mergeOrder = SourceSetMergeOrder.computeMergeOrder(activeVariant, buildType, flavors)
        val moduleDir = resolveModuleDir(projectDir, moduleName)
        val sourceRoots = SourceSetMergeOrder.resolveSourceDirs(moduleDir, mergeOrder)

        if (sourceRoots.isNotEmpty()) {
            moduleUpdates[moduleName] = buildModuleData(
                moduleName, bootLibrary, depJars, sourceRoots, projectDir
            )
        }
    }

    val mergedModules = base.modules.map { m -> moduleUpdates[m.name] ?: m }
    return base.copy(
        modules = mergedModules,
        libraries = base.libraries + extraLibraries
    )
}
```

---

## Edge Cases and Risks

### Risk 1: AGP variant API differences across versions

AGP 7.x uses `applicationVariants`/`libraryVariants` returning
`DomainObjectSet<ApplicationVariant>`. AGP 8.x added the new Variant API
(`androidComponents.onVariants`). We use the legacy API via reflection since
it's still present in AGP 8.x for backward compatibility.
**Mitigation**: try/catch with empty variant list fallback → degrades to
Phase 1 behavior (debug-only).

### Risk 2: Large variant matrices

A project with 4 flavor dimensions x 2 build types could have 16+ variants.
Extracting all classpaths is expensive.
**Mitigation**: Classpath resolution in the Gradle daemon is largely cached.
If performance is an issue, a follow-up optimization can extract only the
active variant's classpath. The variant _names_ and _source dirs_ are cheap
to enumerate regardless.

### Risk 3: kotlin-lsp-config.json doesn't exist yet

This is a new file format. No editor integration exists to create or manage it.
**Mitigation**: Defaults work without the file. Phase 5 adds an LSP command
for variant switching that operates in-memory. The config file is for persistent
preference only.

### Risk 4: Source set directories that don't exist

Many variant source sets are declared by AGP but have no corresponding
directories on disk (e.g., `src/release/kotlin/` often doesn't exist).
**Mitigation**: `resolveSourceDirs()` filters for `exists() && isDirectory()`.

### Risk 5: Serialization size increase

`AndroidModuleInfo` now carries N variants x M files per classpath, serialized
across the Gradle Tooling API boundary.
**Mitigation**: The Tooling API uses Java serialization over a local socket.
Even a large project (8 variants, 200 JARs each) produces ~50KB of serialized
data — well within limits.

### Risk 6: compileSdkVersion inconsistency across source sets

All source sets within a module share the same `compileSdkVersion`. This is
an AGP guarantee — no risk here.

---

## Expected Outcome

After Phase 2:
- `src/debug/kotlin/DebugLogger.kt` resolves — all diagnostics and completions work
- `src/release/java/ReleaseConfig.java` resolves when release variant is selected
- Flavor-specific source sets (`src/free/kotlin/`, `src/paid/kotlin/`) resolve
- Per-variant classpath ensures `debugImplementation` deps appear only in debug analysis
- Multi-module projects use consistent variant selection across all modules
- `kotlin-lsp-config.json` allows persistent variant override without code changes

Still handled by later phases:
- Live variant switching via LSP command — Phase 5
- R class stubs using variant-specific resources — Phase 3
- Compose compiler plugin per variant — Phase 4
