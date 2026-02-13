package com.kmpnavbuilder.compiler.models

import com.squareup.kotlinpoet.ClassName

/**
 * Internal model representing a parsed @NavFlow container.
 *
 * @param flowComponentClassName The flow component class (e.g. AuthFlowComponent)
 * @param configPrefix Naming prefix for generated files (e.g. "AuthFlow" → AuthFlowConfig, AuthFlowChild, etc.)
 * @param screens Sub-screens belonging to this flow
 */
data class FlowModel(
    val flowComponentClassName: ClassName,
    val configPrefix: String,
    val screens: List<ScreenModel>,
)
