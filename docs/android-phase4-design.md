# Design: Phase 4 — Compose Compiler Plugin Integration

**Epic**: Phase 4
**Blocked by**: Phase 3 submit
**Risk**: Very high — riskiest phase. Read fallback section carefully.

---

## Goal

Eliminate Compose type errors (`@Composable` calling convention errors, Modifier type
mismatches, composable lambda inference failures). After Phase 4, Compose code
analyzes correctly and `@Composable` annotation semantics are enforced by the
Analysis API.

---

## Root Cause

The kotlin-lsp uses IntelliJ's Analysis API (not standalone mode). The Analysis API
discovers compiler plugins through the project model: specifically via
`KotlinSettingsData.compilerArguments`, which encodes `pluginClasspaths` — the list of
compiler plugin JARs found on the Kotlin compile task's classpath.

For JVM modules, `IdeaProjectMapper.calculateKotlinSettings()` already correctly
encodes `pluginClasspaths` from `KotlinCompilerSettings.pluginClasspaths` (extracted
by `KotlinMetadataModelBuilder`). Android modules currently get no
`KotlinSettingsData` at all, so Compose (and all other compiler plugins) are invisible.

## The Fix: Extend AndroidModuleInfo + Emit KotlinSettingsData

**No new IntelliJ platform infrastructure needed.** The solution is:
1. Extend `AndroidModuleInfo` to expose `pluginClasspaths` and `pluginOptions` from `compileDebugKotlin`
2. In `AndroidProjectMapper`, generate `KotlinSettingsData` entries for Android modules (parallel to what `IdeaProjectMapper.calculateKotlinSettings()` does for JVM modules)
3. The existing machinery in the Analysis API session then loads the Compose FIR extensions from the plugin JAR path

---

## Integration Points

- `KotlinMetadataModelBuilder.java` (gradle-plugin) — extracts plugin classpath via `PLUGIN_CLASSPATH_PROPERTY_NAME = "pluginClasspath"`; same approach for Android
- `IdeaProjectMapper.calculateKotlinSettings()` (workspace-import) — the JVM equivalent; we replicate for Android
- `KotlinSettingsData` (model.kt) — the data class consumed by IntelliJ's Kotlin facet configuration
- `KotlinCompilerSettings` in `IdeaProjectMapper.kt` — the serialized JSON structure

---

## 1. Extend AndroidModuleInfo

Add to interface and impl:

```java
/** Compiler plugin JARs from compileDebugKotlin.pluginClasspath, e.g. Compose compiler plugin. */
@NonNull List<String> getPluginClasspaths();

/** Compiler plugin options from compileDebugKotlin (e.g. Compose feature flags). */
@NonNull List<String> getPluginOptions();

/** JVM target from compileDebugKotlin, e.g. "17". */
@Nullable String getJvmTarget();
```

---

## 2. Extend AndroidModuleInfoModelBuilder

In `buildAll()`, after extracting bootClasspath and compile classpath, also extract plugin data from `compileDebugKotlin`:

```java
private static List<String> getPluginClasspaths(Task task) {
    // Mirror KotlinMetadataModelBuilder approach
    try {
        Object compilerOptions = invokeMethod(task, "getCompilerOptions");
        Object pluginClasspath = invokeMethod(compilerOptions, "getPluginClasspath");
        // pluginClasspath is a ConfigurableFileCollection
        if (pluginClasspath instanceof FileCollection) {
            return resolveFileCollection("pluginClasspath", (FileCollection) pluginClasspath)
                .stream().map(File::getPath).collect(Collectors.toList());
        }
    } catch (Exception e) {
        // older KGP: try direct property access
        try {
            Object pluginClasspath = invokeMethod(task, "getPluginClasspath");
            if (pluginClasspath instanceof FileCollection) {
                return resolveFileCollection("pluginClasspath", (FileCollection) pluginClasspath)
                    .stream().map(File::getPath).collect(Collectors.toList());
            }
        } catch (Exception ignored) {}
    }
    return Collections.emptyList();
}

private static List<String> getPluginOptions(Task task) {
    // pluginOptions from compilerOptions (KGP 2.x) or direct property (KGP 1.x)
    try {
        Object compilerOptions = invokeMethod(task, "getCompilerOptions");
        Object pluginOptions = invokeMethod(compilerOptions, "getPluginOptions");
        // pluginOptions is ListProperty<String>
        Object get = invokeMethod(pluginOptions, "get");
        if (get instanceof List) return (List<String>) get;
    } catch (Exception ignored) {}
    return Collections.emptyList();
}

private static String getJvmTarget(Task task) {
    try {
        Object compilerOptions = invokeMethod(task, "getCompilerOptions");
        Object jvmTarget = invokeMethod(compilerOptions, "getJvmTarget");
        Object value = invokeMethod(jvmTarget, "get");
        // JvmTarget enum — call toString()
        return value != null ? value.toString() : null;
    } catch (Exception ignored) { return null; }
}
```

---

## 3. Generate KotlinSettingsData in AndroidProjectMapper

In `AndroidProjectMapper.merge()`, after building the module replacement, also emit
`KotlinSettingsData` for each Android module:

```kotlin
// Parallel to IdeaProjectMapper.calculateKotlinSettings()
private fun buildKotlinSettings(
    moduleName: String,
    sourceRoots: List<SourceRootData>,
    androidInfo: AndroidModuleInfo
): KotlinSettingsData? {
    if (sourceRoots.isEmpty()) return null
    if (androidInfo.pluginClasspaths.isEmpty() && androidInfo.jvmTarget == null) return null

    val compilerSettings = KotlinJvmCompilerArguments(
        jvmTarget = androidInfo.jvmTarget,
        pluginOptions = androidInfo.pluginOptions,
        pluginClasspaths = androidInfo.pluginClasspaths
    )

    return KotlinSettingsData(
        name = "Kotlin",
        sourceRoots = sourceRoots.map { it.path },
        configFileItems = emptyList(),
        module = moduleName,
        useProjectSettings = false,
        implementedModuleNames = emptyList(),
        dependsOnModuleNames = emptyList(),
        additionalVisibleModuleNames = emptySet(),
        productionOutputPath = null,
        testOutputPath = null,
        sourceSetNames = emptyList(),
        isTestModule = false,
        externalProjectId = moduleName,
        isHmppEnabled = true,
        pureKotlinSourceFolders = emptyList(),
        kind = KotlinSettingsData.KotlinModuleKind.DEFAULT,
        compilerArguments = "J${Json.encodeToString(compilerSettings)}",
        additionalArguments = "",
        scriptTemplates = null,
        scriptTemplatesClasspath = null,
        copyJsLibraryFiles = false,
        outputDirectoryForJsLibraryFiles = null,
        targetPlatform = null,
        externalSystemRunTasks = emptyList(),
        version = 5,
        flushNeeded = false
    )
}
```

Add the resulting `KotlinSettingsData` to `base.copy(kotlinSettings = ...)`.

---

## 4. Compose Plugin Detection (for logging/diagnostics)

Add a helper to detect if Compose is in the plugin classpath:

```kotlin
fun List<String>.hasComposePlugin(): Boolean =
    any { it.contains("kotlin-compose-compiler-plugin") || it.contains("compose-compiler-plugin") }
```

Log a message when Compose is detected: `"Compose compiler plugin detected: {jarPath}"`.

---

## Fallback: Standalone Analysis API

If the `KotlinSettingsData` approach doesn't work (IntelliJ's Kotlin facet
infrastructure doesn't pick up the plugin classpath for Android modules), fall back to:

1. Locate the Compose plugin JAR from `pluginClasspaths`
2. Manually call `KotlinCompilerPluginsProvider` (IntelliJ service) or find an
   `ApplicationInitEntry`/`ProjectInitEntry` hook to register it
3. Last resort: document the issue and expose `pluginClasspaths` via the workspace
   export JSON, allowing a follow-up issue for deeper IntelliJ integration

The `WorkspaceImportLanguageServerExtension` in
`features-impl/kotlin/src/com/jetbrains/ls/imports/WorkspaceImportLanguageServerExtension.kt`
is the `LanguageServerExtension` entry point for adding configuration pieces.

---

## Files to Create/Modify

| Action | File |
|--------|------|
| MODIFY | `gradle-plugin/src/.../android/model/AndroidModuleInfo.java` — add pluginClasspaths, pluginOptions, jvmTarget |
| MODIFY | `gradle-plugin/src/.../android/model/impl/AndroidModuleInfoImpl.java` |
| MODIFY | `gradle-plugin/src/.../android/model/builder/AndroidModuleInfoModelBuilder.java` — extract plugin data |
| MODIFY | `workspace-import/src/.../android/AndroidProjectMapper.kt` — emit KotlinSettingsData |

---

## Verification

After implementation, open a Compose project. Confirm:
- `@Composable` calling convention errors appear correctly (calling composable from non-composable)
- `Modifier`, `Column`, `Row`, `Text` types resolve without errors
- No spurious `@Composable` type mismatch errors on correctly written code
- `KotlinSettingsData.compilerArguments` includes the Compose plugin JAR path (verify via "Export Workspace" VS Code command)

---

## Expected Outcome

- Compose `@Composable` annotation semantics enforced by the Analysis API
- Completion inside `@Composable` functions works
- Type errors from missing Compose plugin eliminated
- All other compiler plugins (KSP, serialization, etc.) also benefit automatically
