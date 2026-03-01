# Design: Phase 3 — Generated Source Discovery and R Class Support

**Epic**: Phase 3
**Blocked by**: Phase 2 submit

---

## Goal

Resolve `R.string.app_name`, `R.layout.activity_main`, `BuildConfig.DEBUG`, and
ViewBinding/DataBinding classes. Two strategies: (a) discover already-generated
sources from `build/generated/` after a prior build, and (b) generate lightweight
stubs on-the-fly from resource files so LSP works without a prior build.

---

## Integration Point

`AndroidProjectMapper.merge()` (Phase 1/2) produces the base `WorkspaceData`.
Phase 3 extends it by appending additional `SourceRootData` entries and virtual
source root directories to each Android module's `ContentRootData`.

All new logic lives in the `com.jetbrains.ls.imports.android` package.

---

## New Files

```
workspace-import/src/com/jetbrains/ls/imports/android/
  GeneratedSourceDiscovery.kt   — scans build/generated/ for known patterns
  RClassStubGenerator.kt        — generates R.kt stub from res/ directories
  BuildConfigStubGenerator.kt   — generates BuildConfig.kt stub from AGP metadata
```

`AndroidModuleInfo` interface needs one new field (see below).
`AndroidProjectMapper` calls the new generators after the Phase 1/2 processing.

---

## 1. Extend AndroidModuleInfo

Add to interface (and impl):

```java
/** Application ID (package name), e.g. "com.example.myapp". Used for BuildConfig. */
@NonNull String getApplicationId();

/** VERSION_CODE integer from the debug variant. */
int getVersionCode();

/** VERSION_NAME string from the debug variant. */
@NonNull String getVersionName();
```

In `AndroidModuleInfoModelBuilder`, extract via reflection:
```java
// From android extension
String appId = (String) invokeMethod(androidExt, "getDefaultConfig", /* then */ "getApplicationId");
// Alternatively from ApplicationId task or BuildConfig task metadata
```

Simpler approach: read from `build/intermediates/merged_manifests/debug/AndroidManifest.xml`
or fall back to the `namespace` property:
```java
Object namespace = invokeMethod(androidExt, "getNamespace");  // AGP 7.3+
// fallback: getDefaultConfig().getApplicationId()
```

---

## 2. GeneratedSourceDiscovery

Scans `build/generated/` for directories matching known AGP-generated-source
patterns. Only includes directories that exist (project must have been built at
least partially).

```kotlin
object GeneratedSourceDiscovery {

    private val PATTERNS = listOf(
        // (relative path template, source type)
        "generated/source/r/{variant}"                           to "java-source",
        "generated/source/buildConfig/{variant}"                 to "java-source",
        "generated/data_binding_base_class_source_out/{variant}/out" to "java-source",
        "generated/aidl_source_output_dir/{variant}/out"         to "java-source",
        "generated/source/navigation-args/{variant}"             to "java-source",
        "generated/ksp/{variant}/kotlin"                         to "kotlin-source",
        "generated/ksp/{variant}/java"                           to "java-source",
        "generated/source/kapt/{variant}"                        to "java-source",
        "generated/ap_generated_sources/{variant}/out"           to "java-source",
    )

    fun discover(moduleDir: Path, variant: String): List<SourceRootData> {
        val buildDir = moduleDir / "build"
        if (!buildDir.exists()) return emptyList()

        return PATTERNS.mapNotNull { (pattern, type) ->
            val resolved = buildDir / pattern.replace("{variant}", variant)
            if (resolved.exists() && resolved.isDirectory()) {
                SourceRootData(resolved.toString(), type)
            } else null
        }
    }
}
```

---

## 3. RClassStubGenerator

Generates an in-memory `R.kt` file without needing a prior build.
The generated class has the correct nested object structure and field names,
but uses placeholder values (actual resource IDs are only available post-build).

### Resource types to scan

| Resource dir | R class field | XML element |
|---|---|---|
| `res/values/strings.xml` | `R.string.xxx` | `<string name="xxx">` |
| `res/values/arrays.xml` | `R.array.xxx` | `<string-array name="xxx">` |
| `res/layout/*.xml` | `R.layout.xxx` | filename |
| `res/drawable/` | `R.drawable.xxx` | filename (strip extension) |
| `res/mipmap/` | `R.mipmap.xxx` | filename |
| `res/menu/*.xml` | `R.menu.xxx` | filename |
| `res/color/*.xml` | `R.color.xxx` | filename + `<color name="xxx">` in values/colors.xml |
| `res/anim/*.xml` | `R.anim.xxx` | filename |
| `res/raw/` | `R.raw.xxx` | filename |
| `res/xml/*.xml` | `R.xml.xxx` | filename |
| `res/font/` | `R.font.xxx` | filename |
| `res/values/dimens.xml` | `R.dimen.xxx` | `<dimen name="xxx">` |
| `res/values/styles.xml` | `R.style.xxx` | `<style name="xxx">` |
| `res/values/attrs.xml` | `R.attr.xxx` | `<attr name="xxx">` |
| `res/id/` or `res/values/ids.xml` | `R.id.xxx` | |

Also check `R.txt` at `build/intermediates/runtime_symbol_list/{variant}/R.txt` if
it exists — parse it for accurate complete field lists.

### R.txt format
```
int layout activity_main 0x7f0b001e
int string app_name 0x7f120015
```
Each line: `type  inner_class  field_name  hex_value`

### Output: write to `build/generated/kotlin-lsp/r-stubs/` directory

```kotlin
object RClassStubGenerator {

    fun generate(moduleDir: Path, applicationId: String, variant: String): Path? {
        val rTxtPath = moduleDir / "build/intermediates/runtime_symbol_list/$variant/R.txt"
        val fields: Map<String, Set<String>> = if (rTxtPath.exists()) {
            parseRTxt(rTxtPath)
        } else {
            scanResDirectories(moduleDir, variant)
        }

        if (fields.isEmpty()) return null

        val outputDir = moduleDir / "build/generated/kotlin-lsp/r-stubs"
        outputDir.createDirectories()

        val packagePath = outputDir / applicationId.replace('.', '/')
        packagePath.createDirectories()

        val rKt = packagePath / "R.kt"
        rKt.writeText(generateRKotlin(applicationId, fields))
        return outputDir
    }

    private fun generateRKotlin(pkg: String, fields: Map<String, Set<String>>): String {
        val sb = StringBuilder("package $pkg\n\nobject R {\n")
        for ((type, names) in fields.entries.sortedBy { it.key }) {
            sb.append("    object $type {\n")
            for (name in names.sorted()) {
                sb.append("        @JvmField val $name: Int = 0\n")
            }
            sb.append("    }\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    private fun parseRTxt(rTxt: Path): Map<String, Set<String>> {
        val result = mutableMapOf<String, MutableSet<String>>()
        rTxt.forEachLine { line ->
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size >= 3) {
                val innerClass = parts[1]  // e.g. "layout", "string"
                val name = parts[2]
                result.getOrPut(innerClass) { mutableSetOf() }.add(name)
            }
        }
        return result
    }

    private fun scanResDirectories(moduleDir: Path, variant: String): Map<String, Set<String>> {
        // Collect src/main/res + src/{variant}/res directories
        val resDirs = listOf(
            moduleDir / "src/main/res",
            moduleDir / "src/$variant/res",
        ).filter { it.exists() }

        val result = mutableMapOf<String, MutableSet<String>>()
        for (resDir in resDirs) {
            scanResDir(resDir, result)
        }
        return result
    }

    private fun scanResDir(resDir: Path, result: MutableMap<String, MutableSet<String>>) {
        resDir.listDirectoryEntries().forEach { typeDir ->
            val typeName = typeDir.name.split("-").first()  // strip qualifiers
            val rClass = dirToRClass(typeName) ?: return@forEach

            if (typeDir.isRegularFile()) return@forEach

            // File-based resources (layout, drawable, etc.)
            if (rClass != "string" && rClass != "dimen" && rClass != "style" && rClass != "attr" && rClass != "color") {
                typeDir.listDirectoryEntries().forEach { file ->
                    if (file.isRegularFile()) {
                        result.getOrPut(rClass) { mutableSetOf() }
                            .add(file.nameWithoutExtension.replace('-', '_'))
                    }
                }
            }

            // XML value-based resources
            if (typeDir.name.startsWith("values")) {
                typeDir.listDirectoryEntries().forEach { xmlFile ->
                    if (xmlFile.extension == "xml") {
                        parseValuesXml(xmlFile, result)
                    }
                }
            }
        }
    }

    private fun dirToRClass(dirName: String): String? = when (dirName) {
        "layout" -> "layout"
        "drawable", "drawable-v24" -> "drawable"
        "mipmap" -> "mipmap"
        "menu" -> "menu"
        "anim", "animator" -> "anim"
        "color" -> "color"
        "raw" -> "raw"
        "xml" -> "xml"
        "font" -> "font"
        "navigation" -> "navigation"
        "values" -> null  // handled separately
        else -> null
    }

    private fun parseValuesXml(xmlFile: Path, result: MutableMap<String, MutableSet<String>>) {
        // Simple regex-based parsing to avoid XML dependency
        val content = xmlFile.readText()
        val nameAttr = """name="([^"]+)"""".toRegex()
        val tagToClass = mapOf(
            "string" to "string",
            "string-array" to "array",
            "integer-array" to "array",
            "dimen" to "dimen",
            "style" to "style",
            "attr" to "attr",
            "color" to "color",
            "bool" to "bool",
            "integer" to "integer",
            "id" to "id",
        )

        val tagPattern = """<(\w[\w-]*)\s[^>]*name="([^"]+)"""".toRegex()
        tagPattern.findAll(content).forEach { match ->
            val tag = match.groupValues[1]
            val name = match.groupValues[2].replace('.', '_')
            val rClass = tagToClass[tag] ?: return@forEach
            result.getOrPut(rClass) { mutableSetOf() }.add(name)
        }
    }
}
```

---

## 4. BuildConfigStubGenerator

Generates `BuildConfig.kt` in a temp directory using metadata from `AndroidModuleInfo`.

```kotlin
object BuildConfigStubGenerator {

    fun generate(moduleDir: Path, applicationId: String, versionCode: Int,
                 versionName: String, buildType: String): Path? {
        val outputDir = moduleDir / "build/generated/kotlin-lsp/buildconfig-stubs"
        outputDir.createDirectories()

        val packagePath = outputDir / applicationId.replace('.', '/')
        packagePath.createDirectories()

        val buildConfigKt = packagePath / "BuildConfig.kt"
        buildConfigKt.writeText("""
package $applicationId

object BuildConfig {
    const val DEBUG: Boolean = ${buildType == "debug"}
    const val APPLICATION_ID: String = "$applicationId"
    const val BUILD_TYPE: String = "$buildType"
    const val VERSION_CODE: Int = $versionCode
    const val VERSION_NAME: String = "$versionName"
}
""".trimIndent())
        return outputDir
    }
}
```

---

## 5. AndroidProjectMapper Integration

After Phase 1/2 module processing, add:

```kotlin
// In AndroidProjectMapper.merge(), after building moduleDependencies and sourceRoots:

// 3a. Discover generated sources from build/
val selectedVariant = "debug" // Phase 2 provides this from active variant
val generatedRoots = GeneratedSourceDiscovery.discover(
    moduleDir = projectDirectory / moduleName,  // adjust to actual module path
    variant = selectedVariant
)

// 3b. Generate R class stub if no R sources found in build/
val hasRSource = generatedRoots.any { it.path.contains("source/r") || it.path.contains("ksp") }
if (!hasRSource) {
    val rStubDir = RClassStubGenerator.generate(
        moduleDir = projectDirectory / moduleName,
        applicationId = androidInfo.applicationId,
        variant = selectedVariant
    )
    if (rStubDir != null) {
        generatedRoots += SourceRootData(rStubDir.toString(), "kotlin-source")
    }
}

// 3c. BuildConfig stub
val buildConfigDir = BuildConfigStubGenerator.generate(
    moduleDir = projectDirectory / moduleName,
    applicationId = androidInfo.applicationId,
    versionCode = androidInfo.versionCode,
    versionName = androidInfo.versionName,
    buildType = selectedVariant.removeSuffix("Debug").removeSuffix("Release")
)
if (buildConfigDir != null) {
    generatedRoots += SourceRootData(buildConfigDir.toString(), "kotlin-source")
}
```

The module's `ContentRootData` gets additional `sourceRoots` from `generatedRoots`.

---

## Files to Create/Modify

| Action | File |
|--------|------|
| MODIFY | `gradle-plugin/src/.../android/model/AndroidModuleInfo.java` — add applicationId, versionCode, versionName |
| MODIFY | `gradle-plugin/src/.../android/model/impl/AndroidModuleInfoImpl.java` — add fields |
| MODIFY | `gradle-plugin/src/.../android/model/builder/AndroidModuleInfoModelBuilder.java` — extract new fields |
| CREATE | `workspace-import/src/.../android/GeneratedSourceDiscovery.kt` |
| CREATE | `workspace-import/src/.../android/RClassStubGenerator.kt` |
| CREATE | `workspace-import/src/.../android/BuildConfigStubGenerator.kt` |
| MODIFY | `workspace-import/src/.../android/AndroidProjectMapper.kt` — call generators |

---

## Edge Cases

- **No prior build**: `GeneratedSourceDiscovery` returns empty; fall through to stub generators
- **R.txt present**: prefer R.txt over XML scanning for accuracy
- **Multi-module R**: each module gets its own R stub; library modules' R resources appear in the app module's merged R
- **Resource name collisions**: R.txt handles this correctly; XML scan may produce duplicates — deduplicate
- **ViewBinding**: discovered from `build/generated/data_binding_base_class_source_out/` if built; no stub generator (too complex for Phase 3)
- **Stub output location**: write to `build/generated/kotlin-lsp/` so they're .gitignored automatically (build/ is always gitignored)

---

## Expected Outcome

- `R.string.app_name`, `R.layout.activity_main`, `R.drawable.icon` resolve (stub or real)
- `BuildConfig.DEBUG`, `BuildConfig.APPLICATION_ID` resolve
- ViewBinding classes resolve if project has been built
- KSP/KAPT outputs (Room, Hilt) resolve if project has been built
