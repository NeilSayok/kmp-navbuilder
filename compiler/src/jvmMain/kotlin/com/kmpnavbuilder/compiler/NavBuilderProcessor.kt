package com.kmpnavbuilder.compiler

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.validate
import com.kmpnavbuilder.compiler.generators.ChildComponentGenerator
import com.kmpnavbuilder.compiler.generators.DeepLinkGenerator
import com.kmpnavbuilder.compiler.generators.FlowCodeGenerator
import com.kmpnavbuilder.compiler.generators.NavigationContentGenerator
import com.kmpnavbuilder.compiler.generators.NavigationFactoryGenerator
import com.kmpnavbuilder.compiler.generators.ScreenConfigGenerator
import com.kmpnavbuilder.compiler.models.FlowModel
import com.kmpnavbuilder.compiler.models.ParamModel
import com.kmpnavbuilder.compiler.models.ScreenModel
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.toTypeName

class NavBuilderProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {

    private var processed = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (processed) return emptyList()

        val navDestinationName = "com.kmpnavbuilder.annotations.NavDestination"
        val navComposableName = "com.kmpnavbuilder.annotations.NavComposable"
        val navParamName = "com.kmpnavbuilder.annotations.NavParam"
        val navFlowName = "com.kmpnavbuilder.annotations.NavFlow"

        // Collect all annotated symbols
        val destinationSymbols = resolver.getSymbolsWithAnnotation(navDestinationName)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        val composableSymbols = resolver.getSymbolsWithAnnotation(navComposableName)
            .filterIsInstance<KSFunctionDeclaration>()
            .toList()

        val flowSymbols = resolver.getSymbolsWithAnnotation(navFlowName)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        // Check if destination and composable symbols are valid (resolvable).
        // NOTE: @NavFlow symbols are NOT validated here because their class bodies
        // reference generated types (e.g. AuthFlowConfig, AuthFlowFactory) which don't
        // exist until this processor runs. Validating them would cause infinite deferral.
        val invalidDestinations = destinationSymbols.filter { !it.validate() }
        val invalidComposables = composableSymbols.filter { !it.validate() }
        if (invalidDestinations.isNotEmpty() || invalidComposables.isNotEmpty()) {
            return invalidDestinations + invalidComposables
        }

        if (destinationSymbols.isEmpty() && flowSymbols.isEmpty()) return emptyList()

        // Build composable map and flow class map
        val composableMap = buildComposableMap(composableSymbols, navComposableName)
        val flowClassMap = buildFlowClassMap(flowSymbols) // qualifiedName → KSClassDeclaration

        // Parse @NavDestination screens, partitioning by flow membership
        val allScreens = mutableListOf<ScreenModel>()
        val screenToFlowKey = mutableMapOf<ScreenModel, String>() // screen → flow qualified name
        val paths = mutableSetOf<String>()
        val configNames = mutableSetOf<String>()

        for (classDecl in destinationSymbols) {
            val annotation = classDecl.annotations.first {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == navDestinationName
            }

            val path = annotation.getArgument("path") as? String ?: "/"
            val nameOverride = annotation.getArgument("name") as? String ?: ""
            val flowArg = annotation.getArgument("flow") as? KSType

            // Determine flow membership
            val flowQualifiedName = flowArg?.declaration?.qualifiedName?.asString()
            val belongsToFlow = flowQualifiedName != null && flowQualifiedName != "kotlin.Nothing"
            val flowDecl = if (belongsToFlow) flowClassMap[flowQualifiedName] else null

            if (belongsToFlow && flowDecl == null) {
                logger.error(
                    "Flow class '$flowQualifiedName' referenced by ${classDecl.qualifiedName?.asString()} " +
                        "is not annotated with @NavFlow.",
                    classDecl,
                )
                continue
            }

            // Validate path format
            if (!path.startsWith("/")) {
                logger.error(
                    "Navigation path must start with '/': \"$path\" on ${classDecl.qualifiedName?.asString()}",
                    classDecl,
                )
                continue
            }

            // Validate unique paths
            if (path in paths) {
                logger.error(
                    "Duplicate navigation path \"$path\" found on ${classDecl.qualifiedName?.asString()}",
                    classDecl,
                )
                continue
            }
            paths.add(path)

            val componentClassName = ClassName(
                classDecl.packageName.asString(),
                classDecl.simpleName.asString(),
            )

            val configName = if (nameOverride.isNotEmpty()) {
                nameOverride
            } else {
                classDecl.simpleName.asString().removeSuffix("Component")
            }

            // Validate unique config names
            if (configName in configNames) {
                logger.error(
                    "Duplicate config name \"$configName\" for ${classDecl.qualifiedName?.asString()}. " +
                        "Use @NavDestination(name = \"...\") to provide a unique name.",
                    classDecl,
                )
                continue
            }
            configNames.add(configName)

            // Parse constructor parameters
            val constructor = classDecl.primaryConstructor
            if (constructor == null) {
                logger.error("@NavDestination class must have a primary constructor: ${classDecl.qualifiedName?.asString()}", classDecl)
                continue
            }

            val params = mutableListOf<ParamModel>()
            var hasNavigateTo = false
            var hasFlowParam = false

            for (param in constructor.parameters) {
                when {
                    isComponentContextParam(param) -> {
                        // Recognized: injected by Decompose
                    }
                    isNavigateToParam(param) -> {
                        hasNavigateTo = true
                    }
                    belongsToFlow && isFlowComponentParam(param, flowDecl) -> {
                        hasFlowParam = true
                    }
                    hasNavParamAnnotation(param, navParamName) -> {
                        params.add(
                            ParamModel(
                                name = param.name?.asString() ?: continue,
                                type = param.type.toTypeName(),
                            )
                        )
                    }
                    else -> {
                        val expectedParams = if (belongsToFlow) {
                            "ComponentContext, ${flowDecl?.simpleName?.asString()}, or annotated with @NavParam"
                        } else {
                            "ComponentContext, (NavConfig, Boolean?) -> Unit, or annotated with @NavParam"
                        }
                        logger.error(
                            "Unrecognized constructor parameter '${param.name?.asString()}' in @NavDestination class ${classDecl.simpleName.asString()}. " +
                                "Parameters must be $expectedParams.",
                            param,
                        )
                    }
                }
            }

            // Warn if navigateTo is missing for top-level screens
            if (!belongsToFlow && !hasNavigateTo) {
                logger.warn(
                    "${classDecl.simpleName.asString()} has no navigateTo: (NavConfig, Boolean?) -> Unit parameter. " +
                        "This screen won't be able to trigger navigation.",
                    classDecl,
                )
            }

            // Validate @NavParam types are serializable
            for (param in params) {
                val typeName = param.type.toString()
                val allowedTypes = setOf(
                    "kotlin.String", "kotlin.Int", "kotlin.Long", "kotlin.Float",
                    "kotlin.Double", "kotlin.Boolean", "String", "Int", "Long",
                    "Float", "Double", "Boolean",
                )
                if (typeName !in allowedTypes && !typeName.endsWith("?")) {
                    logger.warn(
                        "@NavParam '${param.name}' has type '$typeName'. " +
                            "Ensure this type is @Serializable for config generation.",
                        classDecl,
                    )
                }
            }

            // Find matching composable
            val qualifiedName = classDecl.qualifiedName?.asString() ?: continue
            val composableFunc = composableMap[qualifiedName]
            if (composableFunc == null) {
                logger.error(
                    "No @NavComposable function found for @NavDestination ${classDecl.simpleName.asString()}. " +
                        "Add @NavComposable(${classDecl.simpleName.asString()}::class) to a composable function.",
                    classDecl,
                )
                continue
            }

            val screen = ScreenModel(
                componentClassName = componentClassName,
                configName = configName,
                path = path,
                params = params,
                hasNavigateTo = hasNavigateTo,
                composableFunctionName = composableFunc.simpleName.asString(),
                composablePackage = composableFunc.packageName.asString(),
                hasFlowParam = hasFlowParam,
            )

            allScreens.add(screen)

            if (belongsToFlow) {
                screenToFlowKey[screen] = flowQualifiedName!!
            }
        }

        // Parse @NavFlow symbols as top-level screens
        val flowScreenModels = mutableListOf<Pair<String, ScreenModel>>() // (qualifiedName, screenModel)

        for (flowDecl in flowSymbols) {
            val annotation = flowDecl.annotations.first {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == navFlowName
            }

            val path = annotation.getArgument("path") as? String ?: "/"

            if (!path.startsWith("/")) {
                logger.error(
                    "Navigation path must start with '/': \"$path\" on ${flowDecl.qualifiedName?.asString()}",
                    flowDecl,
                )
                continue
            }

            if (path in paths) {
                logger.error(
                    "Duplicate navigation path \"$path\" found on ${flowDecl.qualifiedName?.asString()}",
                    flowDecl,
                )
                continue
            }
            paths.add(path)

            val componentClassName = ClassName(
                flowDecl.packageName.asString(),
                flowDecl.simpleName.asString(),
            )

            // Derive configName for the flow as a top-level screen entry
            val nameOverride = annotation.getArgument("name") as? String ?: ""
            val configName = if (nameOverride.isNotEmpty()) {
                nameOverride
            } else {
                flowDecl.simpleName.asString().removeSuffix("Component")
            }

            if (configName in configNames) {
                logger.error(
                    "Duplicate config name \"$configName\" for ${flowDecl.qualifiedName?.asString()}. " +
                        "Use @NavFlow(name = \"...\") to provide a unique name.",
                    flowDecl,
                )
                continue
            }
            configNames.add(configName)

            // Parse constructor (same convention as top-level @NavDestination)
            val constructor = flowDecl.primaryConstructor
            if (constructor == null) {
                logger.error("@NavFlow class must have a primary constructor: ${flowDecl.qualifiedName?.asString()}", flowDecl)
                continue
            }

            val params = mutableListOf<ParamModel>()
            var hasNavigateTo = false

            for (param in constructor.parameters) {
                when {
                    isComponentContextParam(param) -> {}
                    isNavigateToParam(param) -> { hasNavigateTo = true }
                    hasNavParamAnnotation(param, navParamName) -> {
                        params.add(
                            ParamModel(
                                name = param.name?.asString() ?: continue,
                                type = param.type.toTypeName(),
                            )
                        )
                    }
                    else -> {
                        logger.error(
                            "Unrecognized constructor parameter '${param.name?.asString()}' in @NavFlow class ${flowDecl.simpleName.asString()}. " +
                                "Parameters must be ComponentContext, (NavConfig, Boolean?) -> Unit, or annotated with @NavParam.",
                            param,
                        )
                    }
                }
            }

            // Find matching composable for the flow container
            val qualifiedName = flowDecl.qualifiedName?.asString() ?: continue
            val composableFunc = composableMap[qualifiedName]
            if (composableFunc == null) {
                logger.error(
                    "No @NavComposable function found for @NavFlow ${flowDecl.simpleName.asString()}. " +
                        "Add @NavComposable(${flowDecl.simpleName.asString()}::class) to a composable function.",
                    flowDecl,
                )
                continue
            }

            val screen = ScreenModel(
                componentClassName = componentClassName,
                configName = configName,
                path = path,
                params = params,
                hasNavigateTo = hasNavigateTo,
                composableFunctionName = composableFunc.simpleName.asString(),
                composablePackage = composableFunc.packageName.asString(),
                isFlow = true,
            )

            allScreens.add(screen)
            flowScreenModels.add(qualifiedName to screen)
        }

        // Partition: top-level screens = all screens NOT belonging to a flow
        val topLevelScreens = allScreens.filter { it !in screenToFlowKey }

        if (topLevelScreens.isEmpty() && flowScreenModels.isEmpty()) return emptyList()

        // Determine output package
        val outputPackage = options["navbuilder.package"]
            ?: allScreens.first().componentClassName.packageName.substringBeforeLast(".") + ".navigation.generated"

        // Generate top-level files (5 existing generators) with top-level screens only
        if (topLevelScreens.isNotEmpty()) {
            ScreenConfigGenerator(codeGenerator, logger).generate(outputPackage, topLevelScreens)
            ChildComponentGenerator(codeGenerator, logger).generate(outputPackage, topLevelScreens)
            NavigationFactoryGenerator(codeGenerator, logger).generate(outputPackage, topLevelScreens)
            NavigationContentGenerator(codeGenerator, logger).generate(outputPackage, topLevelScreens)
            DeepLinkGenerator(codeGenerator, logger).generate(outputPackage, topLevelScreens)
        }

        // Build FlowModels and generate per-flow files
        val flowCodeGenerator = FlowCodeGenerator(codeGenerator, logger)
        for ((flowQualifiedName, flowDecl) in flowClassMap) {
            val flowScreen = flowScreenModels.find { it.first == flowQualifiedName }?.second ?: continue
            val subScreens = screenToFlowKey.filter { it.value == flowQualifiedName }.keys.toList()

            if (subScreens.isEmpty()) {
                logger.warn(
                    "@NavFlow ${flowDecl.simpleName.asString()} has no sub-screens. " +
                        "Add @NavDestination(flow = ${flowDecl.simpleName.asString()}::class) to sub-screen components.",
                    flowDecl,
                )
                continue
            }

            val configPrefix = deriveConfigPrefix(flowDecl)

            val flowModel = FlowModel(
                flowComponentClassName = flowScreen.componentClassName,
                configPrefix = configPrefix,
                screens = subScreens,
            )

            flowCodeGenerator.generate(outputPackage, flowModel)
        }

        processed = true
        return emptyList()
    }

    private fun buildComposableMap(
        composables: List<KSFunctionDeclaration>,
        annotationName: String,
    ): Map<String, KSFunctionDeclaration> {
        val map = mutableMapOf<String, KSFunctionDeclaration>()
        for (func in composables) {
            val annotation = func.annotations.first {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == annotationName
            }
            val componentArg = annotation.arguments.firstOrNull { it.name?.asString() == "component" }
            val componentType = componentArg?.value
            val qualifiedName = when (componentType) {
                is KSType -> componentType.declaration.qualifiedName?.asString()
                else -> null
            }
            if (qualifiedName != null) {
                if (qualifiedName in map) {
                    logger.error(
                        "Multiple @NavComposable functions found for component $qualifiedName: " +
                            "${map[qualifiedName]!!.simpleName.asString()} and ${func.simpleName.asString()}. " +
                            "Each component must have exactly one @NavComposable function.",
                        func,
                    )
                }
                map[qualifiedName] = func
            }
        }
        return map
    }

    private fun buildFlowClassMap(flowSymbols: List<KSClassDeclaration>): Map<String, KSClassDeclaration> {
        val map = mutableMapOf<String, KSClassDeclaration>()
        for (decl in flowSymbols) {
            val qn = decl.qualifiedName?.asString() ?: continue
            map[qn] = decl
        }
        return map
    }

    /**
     * Derives the config prefix from a @NavFlow class:
     * - Check @NavFlow(name = "...") override first
     * - AuthFlowComponent → remove "Component" → "AuthFlow"
     * - AuthComponent → remove "Component" → "Auth" → append "Flow" → "AuthFlow"
     */
    private fun deriveConfigPrefix(flowDecl: KSClassDeclaration): String {
        val navFlowName = "com.kmpnavbuilder.annotations.NavFlow"
        val annotation = flowDecl.annotations.first {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == navFlowName
        }
        val nameOverride = annotation.getArgument("name") as? String ?: ""
        if (nameOverride.isNotEmpty()) {
            return if (nameOverride.endsWith("Flow")) nameOverride else "${nameOverride}Flow"
        }

        val simpleName = flowDecl.simpleName.asString().removeSuffix("Component")
        return if (simpleName.endsWith("Flow")) simpleName else "${simpleName}Flow"
    }

    private fun isComponentContextParam(param: KSValueParameter): Boolean {
        val typeName = param.type.resolve().declaration.qualifiedName?.asString() ?: return false
        return typeName == "com.arkivanov.decompose.ComponentContext"
    }

    private fun isNavigateToParam(param: KSValueParameter): Boolean {
        val type = param.type.resolve()
        val decl = type.declaration.qualifiedName?.asString() ?: return false
        if (decl != "kotlin.Function2") return false
        val args = type.arguments
        if (args.size != 3) return false
        val firstArg = args[0].type?.resolve()?.declaration?.qualifiedName?.asString()
        return firstArg == "com.kmpnavbuilder.runtime.NavConfig"
    }

    /**
     * Checks if a constructor parameter's type matches the @NavFlow class.
     */
    private fun isFlowComponentParam(param: KSValueParameter, flowDecl: KSClassDeclaration?): Boolean {
        if (flowDecl == null) return false
        val paramTypeName = param.type.resolve().declaration.qualifiedName?.asString() ?: return false
        return paramTypeName == flowDecl.qualifiedName?.asString()
    }

    private fun hasNavParamAnnotation(param: KSValueParameter, annotationName: String): Boolean {
        return param.annotations.any {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == annotationName
        }
    }

    private fun KSAnnotation.getArgument(name: String): Any? {
        return arguments.firstOrNull { it.name?.asString() == name }?.value
    }
}
