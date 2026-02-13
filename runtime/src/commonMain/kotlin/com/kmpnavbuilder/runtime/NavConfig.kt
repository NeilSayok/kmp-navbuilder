package com.kmpnavbuilder.runtime

/**
 * Marker interface for generated navigation configurations.
 *
 * The KSP-generated `ScreenConfig` sealed interface extends this.
 * Components should reference `NavConfig` for navigation callbacks
 * to avoid circular dependencies with generated code.
 *
 * Usage in components:
 * ```
 * class HomeComponent(
 *     componentContext: ComponentContext,
 *     val navigateTo: (NavConfig, Boolean?) -> Unit,
 * )
 * ```
 */
interface NavConfig
