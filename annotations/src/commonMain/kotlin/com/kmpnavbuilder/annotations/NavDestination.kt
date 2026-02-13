package com.kmpnavbuilder.annotations

/**
 * Marks a component class as a navigation destination.
 *
 * The KSP processor will generate:
 * - A ScreenConfig sealed interface entry for this destination
 * - A ChildComponent sealed class entry wrapping this component
 * - A factory branch in NavigationFactory to instantiate this component
 *
 * Constructor parameters are handled by convention:
 * - `ComponentContext` → injected by Decompose via childFactory
 * - `(NavConfig, Boolean?) -> Unit` → auto-wired navigation callback
 * - `@NavParam` annotated params → extracted from the generated ScreenConfig
 *
 * @param path URL path pattern (e.g. "/home", "/product/{productId}")
 * @param name Override the generated config name. Defaults to class name without "Component" suffix.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class NavDestination(
    val path: String,
    val name: String = "",
)
