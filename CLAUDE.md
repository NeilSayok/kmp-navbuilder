# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Gradle wrapper, Kotlin 2.4.21, JDK 17 (CI uses Temurin 17). Publishing and iOS targets need macOS.

```bash
./gradlew build                      # compile and test all modules
./gradlew :runtime:jvmTest           # test one module on one target (no test sources exist yet)
./gradlew :compiler:jvmTest --tests "com.kmpnavbuilder.compiler.SomeTest"   # single test class
./gradlew publishToMavenLocal        # install all three modules locally for consumer testing
./gradlew publish --no-parallel      # what CI runs (needs gpr.user/gpr.key or GITHUB_ACTOR/GITHUB_TOKEN)
```

The `compiler` module is JVM-only; `annotations` and `runtime` target JVM, iosArm64, iosSimulatorArm64, JS and WasmJS.

## Architecture

Three modules form one pipeline. Annotations are the input, the KSP processor generates code, and the runtime provides base classes that the generated code extends.

- **`annotations`** (commonMain): `@NavDestination`, `@NavFlow`, `@NavComposable`, `@NavParam`. Pure Kotlin, no dependencies. Consumers depend on it from shared code.
- **`compiler`** (jvmMain only): `NavBuilderProvider` registers `NavBuilderProcessor` as a KSP `SymbolProcessorProvider` (`META-INF/services`). Consumers wire it with `kspJvm` / `kspAndroid`, not as an implementation dependency.
- **`runtime`** (commonMain): `NavRootComponent<C, T>` (wraps a Decompose `childStack`, handles back and web navigation) and the `NavConfig` / `NavChildComponent` marker interfaces that generated code implements.

### Processor flow (`NavBuilderProcessor.process`)

1. Collect symbols annotated with `@NavDestination`, `@NavComposable`, `@NavFlow`. Defer the round if destination or composable symbols do not resolve yet.
2. Validate and build a `ScreenModel` per destination: unique path (must start with `/`), unique config name (the class name minus `Component`, or `name=` override), constructor params classified as ComponentContext, `navigateTo`, flow-component reference, or `@NavParam`. Each destination must have exactly one `@NavComposable` function that names it as `component`.
3. Split screens into top-level (no `flow=`) and per-flow sub-screens. `@NavFlow` classes are also top-level screens with their own path.
4. Output package: `navbuilder.package` KSP option, otherwise `<base package>.navigation.generated`.
5. Run generators under `compiler/.../generators/`. Top-level: `ScreenConfigGenerator`, `ChildComponentGenerator`, `NavigationFactoryGenerator`, `NavigationContentGenerator`, `DeepLinkGenerator`. One `FlowCodeGenerator` run per flow, prefixed with the flow's config prefix (e.g. `CheckoutFlow`).

Generated code is written by KotlinPoet. Consumers add `build/generated/ksp/metadata/commonMain/kotlin` as a common source dir (see README "Configure KSP source sets").

### Runtime wiring

- A consumer's root component extends `NavRootComponent<ScreenConfig, ChildComponent>` and passes the generated `ScreenConfig.serializer()`, `NavigationFactory::createChild` and `DeepLinkParser.configToPath`.
- Screen components receive `navigateTo: (NavConfig, Boolean?) -> Unit`. `true` means replace the whole stack, otherwise push. `NavRootComponent.handleNavigation` does this.
- Flows are nested `childStack`s built in the flow component. Their `handleNavigation` handles their own configs and forwards unknown configs to the parent `navigateTo`.
- `ChildComponent` implements `NavChildComponent.asWebNavigationOwner()` so web history reaches into flow sub-stacks.

### Gotchas

- `@NavFlow` symbols are deliberately not validated before deferral, because their bodies reference generated types. Do not add `validate()` to them, or the processor defers forever (see the NOTE in `NavBuilderProcessor.process`).
- Processor state is a single `processed` flag per instance; it runs once per compilation.
- Only `String`, `Int`, `Long`, `Boolean`, `Float`, `Double` are accepted as `@NavParam` types without warnings. Other types only produce a warning.

## graphify

This project has a graphify knowledge graph at graphify-out/.

Rules:
- Before answering architecture or codebase questions, read graphify-out/GRAPH_REPORT.md for god nodes and community structure
- If graphify-out/wiki/index.md exists, navigate it instead of reading raw files
- After modifying code files in this session, run `python3 -c "from graphify.watch import _rebuild_code; from pathlib import Path; _rebuild_code(Path('.'))"` to keep the graph current
