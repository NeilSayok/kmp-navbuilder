package com.kmpnavbuilder.compiler.models

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName

/**
 * Internal model representing a parsed @NavDestination screen.
 */
data class ScreenModel(
    val componentClassName: ClassName,
    val configName: String,
    val path: String,
    val params: List<ParamModel>,
    val hasNavigateTo: Boolean,
    val composableFunctionName: String,
    val composablePackage: String,
    val hasFlowParam: Boolean = false,
    val isFlow: Boolean = false,
)

/**
 * A @NavParam constructor parameter.
 */
data class ParamModel(
    val name: String,
    val type: TypeName,
)
