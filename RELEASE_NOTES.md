## v1.0.2

### Changed
- Updated Decompose to **3.5.0** (from 3.4.0). The library code needed no changes, because the one breaking change in Decompose 3.5.0 (`Child#key` is now `String`) isn't used here.
- Updated Kotlin to **2.4.21** (from 2.3.0). Consuming projects now need Kotlin 2.4.x.
- Updated KSP to **2.3.12** (from 2.3.4).
- Updated KotlinPoet to **2.4.0** (from 2.2.0).
- Updated kotlinx-serialization to **1.11.0** (from 1.8.1).
- Updated Compose Multiplatform plugin to **1.12.1** (from 1.10.0).

### Upgrade notes
- Use Kotlin 2.4.x and KSP 2.3.12 in your project.
- Bump your Decompose dependencies to 3.5.0:
  `com.arkivanov.decompose:decompose` and `:extensions-compose`.
- Bump `kotlinx-serialization-json` to 1.11.0 and your serialization plugin to match your Kotlin version.
- If you target JS/Wasm, regenerate your yarn lock (`./gradlew kotlinUpgradeYarnLock`).

### Artifacts
`com.kmpnavbuilder:annotations`, `:runtime`, `:compiler` at `1.0.2`

### Note
`1.0.1` was tagged but never published because its build still declared version 1.0.0. Use `1.0.2`.
