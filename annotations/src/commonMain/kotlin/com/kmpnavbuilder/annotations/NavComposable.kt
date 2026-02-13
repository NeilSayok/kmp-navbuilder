package com.kmpnavbuilder.annotations

import kotlin.reflect.KClass

/**
 * Links a @Composable function to a @NavDestination component.
 *
 * The KSP processor uses this to generate the NavigationContent composable,
 * mapping each ChildComponent to its screen composable function.
 *
 * The annotated function must have the linked component as its first parameter.
 *
 * @param component The @NavDestination component class this composable renders.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class NavComposable(
    val component: KClass<*>,
)
