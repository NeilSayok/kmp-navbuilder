package com.kmpnavbuilder.annotations

/**
 * Marks a component class as a navigation flow container with its own nested ChildStack.
 *
 * Flow containers are treated as top-level screens in root navigation,
 * but contain their own sub-screens (marked with @NavDestination(flow = ThisClass::class)).
 *
 * The KSP processor generates per-flow:
 * - {Prefix}Config: sealed interface for sub-screen configs
 * - {Prefix}Child: sealed class wrapping sub-screen components
 * - {Prefix}Factory: object with createChild(config, context, flow)
 * - {Prefix}Content: @Composable function rendering the nested stack
 *
 * @param path URL path pattern for this flow as a root-level destination
 * @param name Override the generated prefix. Defaults to class name without "Component", with "Flow" appended if needed.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class NavFlow(
    val path: String,
    val name: String = "",
)
