# KMP-NavBuilder

A Kotlin Multiplatform annotation processing library that generates type-safe navigation infrastructure at compile time. Built on top of [Decompose](https://github.com/arkivanov/Decompose) and [KSP](https://github.com/google/ksp), KMP-NavBuilder eliminates navigation boilerplate by generating configs, factories, and composable renderers from simple annotations.

## Features

- **Compile-time code generation** — zero runtime overhead from navigation wiring
- **Type-safe navigation** — sealed interfaces prevent invalid navigation states
- **Deep linking** — automatic URL ↔ config conversion with path parameter support
- **Nested navigation flows** — hierarchical screen grouping with their own back stacks
- **Web navigation** — built-in browser history API support
- **Multiplatform** — Android, iOS, JS, Wasm, and JVM

## Platforms

| Module      | Android | iOS | JS | Wasm | JVM |
|-------------|---------|-----|----|------|-----|
| annotations | ✅      | ✅  | ✅ | ✅   | ✅  |
| runtime     | ✅      | ✅  | ✅ | ✅   | ✅  |
| compiler    | —       | —   | —  | —    | ✅ (KSP) |

---

## Setup

### 1. Add dependencies

In your shared KMP module's `build.gradle.kts`:

```kotlin
plugins {
    id("com.google.devtools.ksp") version "2.3.12"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.kmpnavbuilder:annotations:1.0.0")
            implementation("com.kmpnavbuilder:runtime:1.0.0")

            // Decompose (required by runtime)
            implementation("com.arkivanov.decompose:decompose:3.5.0")
            implementation("com.arkivanov.decompose:extensions-compose:3.5.0")

            // Serialization (required by runtime)
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
    }
}

// Register the KSP compiler on the JVM target (which runs the processor)
dependencies {
    add("kspJvm", "com.kmpnavbuilder:compiler:1.0.0")
    // For Android targets use:
    // add("kspAndroid", "com.kmpnavbuilder:compiler:1.0.0")
}

// Optional: set the output package for generated files
ksp {
    arg("navbuilder.package", "com.example.myapp.navigation.generated")
}
```

### 2. Configure KSP source sets

Make the generated sources available to the common source set:

```kotlin
kotlin {
    sourceSets {
        commonMain {
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
        }
    }
}
```

---

## Core concepts

KMP-NavBuilder uses four annotations to describe your navigation graph. KSP reads them and generates all the wiring code.

| Annotation | Target | Purpose |
|---|---|---|
| `@NavDestination` | Class | Marks a Decompose component as a navigation screen |
| `@NavFlow` | Class | Groups screens into a nested navigation flow |
| `@NavComposable` | Function | Links a `@Composable` function to a destination component |
| `@NavParam` | Constructor parameter | Declares a typed navigation argument |

---

## Basic usage

### Step 1 — Annotate your components

```kotlin
// HomeComponent.kt
@NavDestination(path = "/home")
class HomeComponent(
    componentContext: ComponentContext,
    private val navigateTo: (NavConfig, Boolean?) -> Unit,
) : ComponentContext by componentContext {

    fun goToProfile() {
        navigateTo(ScreenConfig.Profile, false)
    }
}

// ProfileComponent.kt
@NavDestination(path = "/profile")
class ProfileComponent(
    componentContext: ComponentContext,
) : ComponentContext by componentContext
```

### Step 2 — Link composables

```kotlin
// HomeScreen.kt
@NavComposable(component = HomeComponent::class)
@Composable
fun HomeScreen(component: HomeComponent) {
    Column {
        Text("Home")
        Button(onClick = component::goToProfile) { Text("Go to Profile") }
    }
}

// ProfileScreen.kt
@NavComposable(component = ProfileComponent::class)
@Composable
fun ProfileScreen(component: ProfileComponent) {
    Text("Profile")
}
```

### Step 3 — Build the root component

After KSP runs, it generates `ScreenConfig`, `ChildComponent`, `NavigationFactory`, and `NavigationContent` in your configured package. Extend `NavRootComponent` with them:

```kotlin
class RootComponent(
    componentContext: ComponentContext,
) : NavRootComponent<ScreenConfig, ChildComponent>(
    componentContext = componentContext,
    serializer = ScreenConfig.serializer(),
    initialStack = { listOf(ScreenConfig.Home) },
    childFactory = NavigationFactory::createChild,
    pathMapper = { config -> DeepLinkParser.configToPath(config) },
)
```

### Step 4 — Render the navigation stack

```kotlin
@Composable
fun App(root: RootComponent) {
    NavigationContent(stack = root.stack)
}
```

---

## Navigation parameters

Use `@NavParam` on constructor parameters to pass typed data between screens. The parameter name should match the path placeholder when using URL-based deep links.

```kotlin
@NavDestination(path = "/product/{productId}")
class ProductDetailComponent(
    componentContext: ComponentContext,
    private val navigateTo: (NavConfig, Boolean?) -> Unit,
    @NavParam val productId: String,
    @NavParam val quantity: Int,
) : ComponentContext by componentContext

@NavComposable(component = ProductDetailComponent::class)
@Composable
fun ProductDetailScreen(component: ProductDetailComponent) {
    Text("Product: ${component.productId}, Qty: ${component.quantity}")
}
```

KSP generates a config data class with those properties:

```kotlin
// Generated
@Serializable
data class ProductDetail(val productId: String, val quantity: Int) : ScreenConfig
```

Navigate to it like this:

```kotlin
navigateTo(ScreenConfig.ProductDetail(productId = "abc-123", quantity = 2), false)
```

**Supported parameter types:** `String`, `Int`, `Long`, `Boolean`, `Float`, `Double`

---

## Navigation behaviour

The `navigateTo` callback accepts two arguments:

| Argument | Type | Description |
|---|---|---|
| `config` | `NavConfig` | The destination config to navigate to |
| `popBackStack` | `Boolean?` | `true` = replace entire back stack, `false`/`null` = push onto stack |

```kotlin
// Push a new screen (standard forward navigation)
navigateTo(ScreenConfig.Profile, false)

// Replace the entire back stack (e.g. after login)
navigateTo(ScreenConfig.Home, true)
```

To navigate back, call `navigateBack()` on the root component:

```kotlin
rootComponent.navigateBack()
```

---

## Nested navigation flows

Use `@NavFlow` to group a set of screens into a self-contained navigation flow with its own back stack. This is useful for multi-step wizards, onboarding, checkout flows, etc.

### Step 1 — Declare the flow component

```kotlin
@NavFlow(path = "/checkout")
class CheckoutFlowComponent(
    componentContext: ComponentContext,
    private val navigateTo: (NavConfig, Boolean?) -> Unit,
) : ComponentContext by componentContext
```

### Step 2 — Declare sub-screens

Link sub-screens to the flow using `flow = CheckoutFlowComponent::class`. Sub-screens receive the flow component as a constructor parameter so they can access shared state.

```kotlin
@NavDestination(path = "/checkout/cart", flow = CheckoutFlowComponent::class)
class CartComponent(
    componentContext: ComponentContext,
    private val navigateTo: (NavConfig, Boolean?) -> Unit,
    private val flow: CheckoutFlowComponent,
) : ComponentContext by componentContext {

    fun goToPayment() {
        navigateTo(CheckoutFlowConfig.Payment, false)
    }
}

@NavDestination(path = "/checkout/payment", flow = CheckoutFlowComponent::class)
class PaymentComponent(
    componentContext: ComponentContext,
    private val flow: CheckoutFlowComponent,
) : ComponentContext by componentContext

@NavComposable(component = CartComponent::class)
@Composable
fun CartScreen(component: CartComponent) { /* ... */ }

@NavComposable(component = PaymentComponent::class)
@Composable
fun PaymentScreen(component: PaymentComponent) { /* ... */ }
```

### Generated files per flow

KSP generates four files prefixed with the flow name (e.g. `CheckoutFlow`):

| File | Description |
|---|---|
| `CheckoutFlowConfig.kt` | Sealed interface for sub-screen configs |
| `CheckoutFlowChild.kt` | Sealed class wrapping sub-screen components |
| `CheckoutFlowFactory.kt` | Factory: `createChild(config, context, flow)` |
| `CheckoutFlowContent.kt` | `@Composable` rendering the nested stack |

### Step 3 — Implement the flow's child stack

Inside `CheckoutFlowComponent`, create a child stack using the generated factory:

```kotlin
@NavFlow(path = "/checkout")
class CheckoutFlowComponent(
    componentContext: ComponentContext,
    private val navigateTo: (NavConfig, Boolean?) -> Unit,
) : ComponentContext by componentContext {

    private val navigation = StackNavigation<CheckoutFlowConfig>()

    val stack: Value<ChildStack<CheckoutFlowConfig, CheckoutFlowChild>> = childStack(
        source = navigation,
        serializer = CheckoutFlowConfig.serializer(),
        initialStack = { listOf(CheckoutFlowConfig.Cart) },
        handleBackButton = true,
        childFactory = { config, context ->
            CheckoutFlowFactory.createChild(config, context, this)
        },
    )

    fun handleNavigation(config: NavConfig, popBackStack: Boolean?) {
        when (config) {
            is CheckoutFlowConfig -> {
                if (popBackStack == true) navigation.replaceAll(config)
                else navigation.pushNew(config)
            }
            else -> navigateTo(config, popBackStack)
        }
    }
}
```

### Step 4 — Render the flow

```kotlin
@NavComposable(component = CheckoutFlowComponent::class)
@Composable
fun CheckoutFlowScreen(component: CheckoutFlowComponent) {
    CheckoutFlowContent(stack = component.stack)
}
```

---

## Deep linking

KSP generates a `DeepLinkParser` object with two functions:

```kotlin
object DeepLinkParser {
    // Parse a URL into a ScreenConfig
    fun parseDeepLink(url: String): ScreenConfig

    // Convert a ScreenConfig back to its URL path
    fun configToPath(config: ScreenConfig): String
}
```

### Handling incoming deep links

```kotlin
class RootComponent(
    componentContext: ComponentContext,
    deepLinkUrl: String? = null,
) : NavRootComponent<ScreenConfig, ChildComponent>(
    componentContext = componentContext,
    serializer = ScreenConfig.serializer(),
    initialStack = {
        if (deepLinkUrl != null) {
            listOf(DeepLinkParser.parseDeepLink(deepLinkUrl))
        } else {
            listOf(ScreenConfig.Home)
        }
    },
    childFactory = NavigationFactory::createChild,
    pathMapper = { config -> DeepLinkParser.configToPath(config) },
)
```

### Path parameter extraction

Parameters in the path pattern (e.g. `{productId}`) are automatically extracted and populated on the config:

```
URL: /product/abc-123
 → ScreenConfig.ProductDetail(productId = "abc-123", quantity = 0)

URL: /product/abc-123?quantity=5  (query params not yet supported; pass via config)
```

---

## Web navigation

`NavRootComponent` implements Decompose's `WebNavigationOwner`, enabling browser history integration out of the box. Provide a `pathMapper` to activate it:

```kotlin
NavRootComponent(
    ...
    pathMapper = { config -> DeepLinkParser.configToPath(config) },
)
```

On JS/Wasm targets Decompose will synchronise the browser address bar and the back/forward buttons with your navigation stack automatically.

---

## Generated file reference

For a project with two top-level screens and one flow, KSP produces:

```
<output-package>/
├── ScreenConfig.kt          // Sealed interface: Home, Profile, Checkout, ProductDetail(productId, quantity)
├── ChildComponent.kt        // Sealed class: Home(HomeComponent), Profile(ProfileComponent), ...
├── NavigationFactory.kt     // fun createChild(config, context, navigateTo): ChildComponent
├── NavigationContent.kt     // @Composable fun NavigationContent(stack: Value<ChildStack<...>>)
├── DeepLinkParser.kt        // fun parseDeepLink(url), fun configToPath(config)
├── CheckoutFlowConfig.kt    // Sealed interface: Cart, Payment
├── CheckoutFlowChild.kt     // Sealed class: Cart(CartComponent), Payment(PaymentComponent)
├── CheckoutFlowFactory.kt   // fun createChild(config, context, flow): CheckoutFlowChild
└── CheckoutFlowContent.kt   // @Composable fun CheckoutFlowContent(stack: Value<ChildStack<...>>)
```

---

## KSP processor options

| Option | Description | Default |
|---|---|---|
| `navbuilder.package` | Output package for all generated files | Derived from the base package of your annotated classes |

Set in `build.gradle.kts`:

```kotlin
ksp {
    arg("navbuilder.package", "com.example.app.navigation.generated")
}
```

---

## Validation and error reporting

The KSP processor validates your annotations at compile time and reports errors for:

- Paths that do not start with `/`
- Duplicate paths or config names across destinations
- `@NavFlow` references that do not point to a class annotated with `@NavFlow`
- `@NavParam` types outside the supported set (`String`, `Int`, `Long`, `Boolean`, `Float`, `Double`)
- Multiple `@NavComposable` functions registered for the same component

Warnings are emitted for:

- Top-level `@NavDestination` components that have no `navigateTo` parameter
- `@NavFlow` components that have no associated sub-screens

---

## Dependencies

| Library | Version | Role |
|---|---|---|
| [Decompose](https://github.com/arkivanov/Decompose) | 3.5.0 | Component lifecycle & navigation stack |
| [KotlinX Serialization](https://github.com/Kotlin/kotlinx.serialization) | 1.11.0 | Serializable navigation configs |
| [KSP](https://github.com/google/ksp) | 2.3.12 | Symbol processing at compile time |
| [KotlinPoet](https://github.com/square/kotlinpoet) | 2.4.0 | Kotlin source file generation |

---

## License

```
MIT License

Copyright (c) 2025 Neil

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
