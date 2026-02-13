package com.kmpnavbuilder.annotations

/**
 * Marks a constructor parameter of a @NavDestination component
 * as a navigation parameter.
 *
 * Parameters annotated with @NavParam will be:
 * - Added as properties to the generated ScreenConfig data class
 * - Extracted from the config and passed to the component constructor
 *
 * The parameter name should match a path segment placeholder if using
 * path-based parameters (e.g. "/product/{productId}" → @NavParam val productId: String).
 *
 * Supported types: String, Int, Long, Boolean, Float, Double.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class NavParam
