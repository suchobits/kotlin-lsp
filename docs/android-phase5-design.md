# Design: Phase 5 — Advanced Features and Polish

**Epic**: Phase 5
**Blocked by**: Phase 4 submit

---

## Goal

Feature parity with basic Android Studio code intelligence. After Phase 5,
kotlin-lsp provides: resource reference completion, AndroidManifest awareness,
build variant switching via LSP command, file change re-sync, and progress
reporting during import.

---

## Integration Points Reference

From codebase exploration:

- **Custom LSP commands**: Implement `LSCommandDescriptorProvider`, register in `WorkspaceImportLanguageServerExtension.kt` entries list
- **File watchers**: Use `AnalyzerContainerBuilder` init hooks; LSP `workspace/didChangeWatchedFiles` is available via `LspHandlersBuilder`
- **Completion providers**: Implement `LSCompletionProvider` in `api.features`
- **LSP progress**: Use `LspHandlerContext` to send `$/progress` notifications

Key files:
- `features-impl/kotlin/src/com/jetbrains/ls/imports/WorkspaceImportLanguageServerExtension.kt` — add new entries here
- `api.features/src/com/jetbrains/ls/api/features/commands/LSCommandDescriptorProvider.kt` — command interface
- `kotlin-lsp/src/com/jetbrains/ls/kotlinLsp/requests/features.kt` — where LSP features are routed
- `kotlin-lsp/src/com/jetbrains/ls/kotlinLsp/KotlinLspServer.kt` — top-level config composition

---

## Feature 1: `kotlin/selectBuildVariant` Custom Command

Allows editors to switch the active Android build variant without a full re-import.

### Implementation

Create `workspace-import/src/.../android/commands/AndroidBuildVariantCommandProvider.kt`:

```kotlin
object AndroidBuildVariantCommandProvider : LSCommandDescriptorProvider {
    override val commandDescriptors = listOf(
        LSCommandDescriptor(
            title = "Select Android Build Variant",
            name = "kotlin/selectBuildVariant",
            executor = { arguments ->
                // arguments[0] = module name, arguments[1] = variant name
                val moduleName = arguments.getOrNull(0)?.jsonPrimitive?.content
                    ?: return@LSCommandDescriptor JsonNull
                val variantName = arguments.getOrNull(1)?.jsonPrimitive?.content
                    ?: return@LSCommandDescriptor JsonNull
                AndroidVariantState.setActiveVariant(moduleName, variantName)
                // Trigger workspace re-import for the affected module
                buildJsonObject { put("success", true) }
            }
        )
    )
}
```

Create `AndroidVariantState.kt` — a simple in-memory registry:
```kotlin
object AndroidVariantState {
    private val variants = ConcurrentHashMap<String, String>()  // moduleName -> variantName

    fun getActiveVariant(moduleName: String): String =
        variants.getOrDefault(moduleName, "debug")

    fun setActiveVariant(moduleName: String, variant: String) {
        variants[moduleName] = variant
    }
}
```

Register in `WorkspaceImportLanguageServerExtension`:
```kotlin
WorkspaceImporterEntry(GradleWorkspaceImporter),
...
AndroidBuildVariantCommandProvider,   // ADD
```

---

## Feature 2: `kotlin/syncProject` Custom Command

Re-triggers workspace import. Useful after adding dependencies or changing build scripts.

```kotlin
LSCommandDescriptor(
    title = "Sync Android Project",
    name = "kotlin/syncProject",
    executor = { _ ->
        // Signal the analyzer to re-run workspace import
        // Use the existing workspace invalidation mechanism
        workspaceInvalidator.invalidate()
        buildJsonObject { put("success", true) }
    }
)
```

Investigate `workspaceInits` / invalidation hooks in `LSConfiguration.configFor()` for
the correct API to trigger re-import.

---

## Feature 3: File Watcher Integration

Watch Android-specific files and trigger re-sync when they change.

### Files to watch
- `build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts`
- `local.properties`
- `AndroidManifest.xml` (all module locations)
- `res/values/**/*.xml` (for R class stub regeneration)
- `gradle/libs.versions.toml`

### Implementation

The existing `FileWatcher` (native binary) handles low-level file system events.
Register watched paths by implementing `InvalidationHookEntry` or `ProjectInitEntry`:

```kotlin
// Register via LSConfigurationPiece in WorkspaceImportLanguageServerExtension
class AndroidFileWatchEntry : ProjectInitEntry {
    override fun initProject(builder: AnalyzerContainerBuilder, project: Project) {
        // Register Android-specific paths for re-import on change
        // Look at existing FileWatcher usage in KotlinLspServer for API
    }
}
```

As a simpler alternative: advertise `workspace/didChangeWatchedFiles` capability
with Android patterns and handle the notification to trigger re-import:

```kotlin
// In features.kt or a new android/features.kt
notification(WorkspaceSync.DidChangeWatchedFiles) { params ->
    val hasAndroidChange = params.changes.any { change ->
        change.uri.endsWith("build.gradle") ||
        change.uri.endsWith("build.gradle.kts") ||
        change.uri.endsWith("AndroidManifest.xml") ||
        change.uri.contains("/res/values/")
    }
    if (hasAndroidChange) {
        // Trigger re-sync
    }
}
```

---

## Feature 4: AndroidManifest.xml Awareness

Parse `AndroidManifest.xml` to extract package name and component declarations.

### What to extract
- `package` attribute from root `<manifest>` → cross-check / supplement `applicationId`
- `<activity>`, `<service>`, `<receiver>`, `<provider>` elements → for navigation awareness
- `<uses-permission>` → for completion of permission constants

### Implementation

Create `workspace-import/src/.../android/ManifestParser.kt`:

```kotlin
object ManifestParser {
    data class ManifestInfo(
        val packageName: String?,
        val activities: List<String>,
        val services: List<String>,
        val permissions: List<String>
    )

    fun parse(manifestPath: Path): ManifestInfo? {
        if (!manifestPath.exists()) return null
        val content = manifestPath.readText()

        val packageName = """<manifest[^>]+package="([^"]+)"""".toRegex()
            .find(content)?.groupValues?.get(1)

        val activities = """<activity[^>]+android:name="([^"]+)"""".toRegex()
            .findAll(content).map { it.groupValues[1] }.toList()

        val services = """<service[^>]+android:name="([^"]+)"""".toRegex()
            .findAll(content).map { it.groupValues[1] }.toList()

        val permissions = """<uses-permission[^>]+android:name="([^"]+)"""".toRegex()
            .findAll(content).map { it.groupValues[1] }.toList()

        return ManifestInfo(packageName, activities, services, permissions)
    }
}
```

Use in `AndroidProjectMapper` to supplement or override `applicationId` from AGP model.

---

## Feature 5: KSP/KAPT Generated Source Discovery (Extension of Phase 3)

Phase 3's `GeneratedSourceDiscovery` already includes KSP/KAPT patterns. Phase 5
extends this by also checking for Hilt and Room specifically:

```kotlin
// Additional patterns for Feature 5
"generated/hilt/component_sources/{variant}"     to "java-source",
"generated/hilt/root_code_sources"               to "java-source",
```

No new infrastructure needed — just add patterns to `GeneratedSourceDiscovery.PATTERNS`.

---

## Feature 6: Navigation Safe Args Support

Safe Args generates `*Directions` and `*Args` classes from navigation XML.
Already covered by Phase 3's `generated/source/navigation-args/{variant}` pattern.

For zero-build-required support: create `SafeArgsStubGenerator` that:
1. Scans `res/navigation/*.xml`
2. Parses `<fragment>`, `<action>`, `<argument>` elements
3. Generates stub `*Directions.kt` and `*Args.kt` classes

This is complex — treat as stretch goal for Phase 5.

---

## Feature 7: LSP Import Progress Reporting

Report import progress via `$/progress` during workspace import.

The `GradleWorkspaceImporter.importWorkspace()` is a suspend function with access
to the coroutine context. Use LSP `$/progress` notifications:

```kotlin
// In GradleWorkspaceImporter or AndroidProjectMapper
// Look for existing progress reporting in initialize.kt (~line 94-277)
// The initialize handler likely has LSP progress infrastructure
```

Check `initialize.kt` lines 94–277 for existing `$/progress` usage to find the
correct API for sending progress tokens.

---

## Feature 8: Resource Reference Completion

Provide completions for `R.string.`, `R.drawable.`, etc. with actual resource names.

This requires implementing a completion provider in `api.features`. The Phase 3
`RClassStubGenerator` already generates the R class stubs, so this may be
automatically handled by the existing completion infrastructure once the stubs
are in the source roots. If not:

Investigate `features-impl/kotlin/` for `LSKotlinCompletionProvider` and whether
Android resource names need explicit completion registration beyond what the stub
approach provides.

---

## Files to Create/Modify

| Action | File |
|--------|------|
| CREATE | `workspace-import/src/.../android/commands/AndroidBuildVariantCommandProvider.kt` |
| CREATE | `workspace-import/src/.../android/commands/AndroidSyncCommandProvider.kt` |
| CREATE | `workspace-import/src/.../android/AndroidVariantState.kt` |
| CREATE | `workspace-import/src/.../android/ManifestParser.kt` |
| MODIFY | `workspace-import/src/.../android/GeneratedSourceDiscovery.kt` — add Hilt patterns |
| MODIFY | `features-impl/kotlin/src/com/jetbrains/ls/imports/WorkspaceImportLanguageServerExtension.kt` — register new entries |
| MODIFY | `workspace-import/src/.../android/AndroidProjectMapper.kt` — use ManifestParser, AndroidVariantState |

---

## Priority Within Phase 5

Implement in this order (highest value first):
1. `kotlin/selectBuildVariant` command — immediately useful for developers
2. AndroidManifest.xml parsing — supplements applicationId
3. `kotlin/syncProject` command — quality of life
4. File watcher integration — quality of life
5. KSP/KAPT patterns (Hilt) — extends Phase 3
6. LSP progress reporting — polish
7. Safe Args stub generator — stretch goal

---

## Expected Outcome

- Editors can switch build variants without restarting LSP
- R class resources resolve from stub even before a build
- File changes to build.gradle/AndroidManifest.xml trigger re-sync
- Import progress visible in editor status bar
- KSP/KAPT annotations (Room, Hilt) have generated stubs discoverable
