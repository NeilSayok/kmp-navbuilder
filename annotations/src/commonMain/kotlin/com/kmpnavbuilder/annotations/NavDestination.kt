package com.kmpnavbuilder.annotations

import kotlin.reflect.KClass

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
 * For flow sub-screens (flow != Nothing::class):
 * - `flow: FlowComponent` → auto-wired to the parent flow component
 * - `@NavParam` params → extracted from the generated flow config
 *
 * @param path URL path pattern (e.g. "/home", "/product/{productId}")
 * @param name Override the generated config name. Defaults to class name without "Component" suffix.
 * @param flow The @NavFlow class this screen belongs to. Nothing::class means top-level.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class NavDestination(
    val path: String,
    val name: String = "",
    val flow: KClass<*> = Nothing::class,
)
