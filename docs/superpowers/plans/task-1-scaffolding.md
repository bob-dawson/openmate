# Task 1: Scaffolding & Build Config

## Goal

Create the multi-module Android project with all modules, build configuration, and Hilt setup. The project should compile and run (showing an empty Activity) after this task.

## Project Structure

```
android/
├── build.gradle.kts              # Root build file
├── settings.gradle.kts           # Include all modules
├── gradle.properties
├── gradle/
│   └── libs.versions.toml        # Version catalog
├── app/
│   └── build.gradle.kts
├── core/
│   ├── common/
│   ├── domain/
│   ├── data/
│   ├── database/
│   ├── network/
│   └── ui/
└── feature/
    ├── instance/
    ├── session/
    └── settings/
```

Each subdirectory is a standalone Gradle module with `build.gradle.kts`, `src/main/`, `src/test/`, `src/androidTest/`.

## Key Decisions

- **Min SDK**: 26 (Android 8.0) — covers >95% of devices, gives us java.time
- **Target/Compile SDK**: 35
- **Kotlin**: 2.1.x
- **AGP**: Use latest stable (check current)
- **Compose BOM**: Latest stable
- **Hilt**: Use latest stable, apply plugin to all modules
- **Version catalog** in `gradle/libs.versions.toml` — all dependency versions centralized

## Version Catalog Entries (libs.versions.toml)

Define versions, libraries, and plugins for:
- Kotlin, AGP, Compose BOM, Hilt, Room, OkHttp, Retrofit (optional), Moshi/Kotlinx.serialization, Coroutines, Lifecycle, Navigation Compose, Truth (test), Espresso (androidTest), Turbine (test)

## Module build.gradle.kts Patterns

**Library modules** (`core/*`, `feature/*`):
- `plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android); alias(libs.plugins.hilt); kotlin("kapt") }`
- Compose enabled where needed (core/ui, feature/*)
- Dependencies on other modules via `implementation(project(":core.xxx"))`

**App module**:
- `plugins { alias(libs.plugins.android.application); ... }`
- `implementation(project(":feature.instance"))` etc.
- Hilt `Application` subclass

## Files to Create

| File | Purpose |
|------|---------|
| `settings.gradle.kts` | Include all 10 modules |
| `build.gradle.kts` (root) | Common config, submodule config block |
| `gradle/libs.versions.toml` | All versions/dependencies |
| `app/build.gradle.kts` | Application module |
| `app/src/main/AndroidManifest.xml` | Application + MainActivity |
| `app/src/main/java/com/openmate/app/OpenMateApp.kt` | Hilt Application subclass |
| `app/src/main/java/com/openmate/app/MainActivity.kt` | Empty Compose Activity |
| `core/common/build.gradle.kts` | Common utilities module |
| `core/domain/build.gradle.kts` | Domain models module |
| `core/data/build.gradle.kts` | Data layer module |
| `core/database/build.gradle.kts` | Room module |
| `core/network/build.gradle.kts` | Network module |
| `core/ui/build.gradle.kts` | UI components module |
| `feature/instance/build.gradle.kts` | Instance feature module |
| `feature/session/build.gradle.kts` | Session feature module |
| `feature/settings/build.gradle.kts` | Settings feature module |

Each module also needs: `src/main/AndroidManifest.xml` (minimal), `src/main/java/com/openmate/<module>/.gitkeep`, `src/test/`, `src/androidTest/`.

## Hilt Setup

- Root: `classpath(libs.hilt.android.gradle.plugin)` in buildscript
- Each module: `id("dagger.hilt.android.plugin")` + `kotlin("kapt")` + `kapt(libs.hilt.compiler)`
- `OpenMateApp` annotated with `@HiltAndroidApp`
- `MainActivity` annotated with `@AndroidEntryPoint`

## Verification

1. `./gradlew assembleDebug` succeeds
2. `./gradlew :app:installDebug` installs on device/emulator
3. App launches with empty Activity (white screen)
4. All module `./gradlew :<module>:test` pass (no tests yet, but task runs)
