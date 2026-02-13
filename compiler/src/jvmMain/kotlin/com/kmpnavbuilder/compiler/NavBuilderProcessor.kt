package com.kmpnavbuilder.compiler

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.validate
import com.kmpnavbuilder.compiler.generators.ChildComponentGenerator
import com.kmpnavbuilder.compiler.generators.DeepLinkGenerator
import com.kmpnavbuilder.compiler.generators.NavigationContentGenerator
import com.kmpnavbuilder.compiler.generators.NavigationFactoryGenerator
import com.kmpnavbuilder.compiler.generators.ScreenConfigGenerator
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

        // Collect @NavDestination classes
        val destinationSymbols = resolver.getSymbolsWithAnnotation(navDestinationName)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        // Collect @NavComposable functions
        val composableSymbols = resolver.getSymbolsWithAnnotation(navComposableName)
            .filterIsInstance<KSFunctionDeclaration>()
            .toList()

        // Check if all symbols are valid (resolvable)
        val invalidDestinations = destinationSymbols.filter { !it.validate() }
        val invalidComposables = composableSymbols.filter { !it.validate() }
        if (invalidDestinations.isNotEmpty() || invalidComposables.isNotEmpty()) {
            // Defer to next round — types not yet resolved
            return invalidDestinations + invalidComposables
        }

        if (destinationSymbols.isEmpty()) return emptyList()

        // Build a map of component qualified name → composable function
        val composableMap = buildComposableMap(composableSymbols, navComposableName)

        // Parse each @NavDestination into a ScreenModel
        val screens = mutableListOf<ScreenModel>()
        val paths = mutableSetOf<String>()
        val configNames = mutableSetOf<String>()

        for (classDecl in destinationSymbols) {
            val annotation = classDecl.annotations.first {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == navDestinationName
            }

            val path = annotation.getArgument("path") as? String ?: "/"
            val nameOverride = annotation.getArgument("name") as? String ?: ""

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

            for (param in constructor.parameters) {
                when {
                    isComponentContextParam(param) -> {
                        // Recognized: injected by Decompose
                    }
                    isNavigateToParam(param) -> {
                        hasNavigateTo = true
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
                        logger.error(
                            "Unrecognized constructor parameter '${param.name?.asString()}' in @NavDestination class ${classDecl.simpleName.asString()}. " +
                                "Parameters must be ComponentContext, (NavConfig, Boolean?) -> Unit, or annotated with @NavParam.",
                            param,
                        )
                    }
                }
            }

            // Warn if navigateTo is missing (screen won't be able to navigate)
            if (!hasNavigateTo) {
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
                            "Ensure this type is @Serializable for ScreenConfig generation.",
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

            screens.add(
                ScreenModel(
                    componentClassName = componentClassName,
                    configName = configName,
                    path = path,
                    params = params,
                    hasNavigateTo = hasNavigateTo,
                    composableFunctionName = composableFunc.simpleName.asString(),
                    composablePackage = composableFunc.packageName.asString(),
                )
            )
        }

        if (screens.isEmpty()) return emptyList()

        // Determine output package from options or first screen's package
        val outputPackage = options["navbuilder.package"]
            ?: screens.first().componentClassName.packageName.substringBeforeLast(".") + ".navigation.generated"

        // Generate all files
        ScreenConfigGenerator(codeGenerator, logger).generate(outputPackage, screens)
        ChildComponentGenerator(codeGenerator, logger).generate(outputPackage, screens)
        NavigationFactoryGenerator(codeGenerator, logger).generate(outputPackage, screens)
        NavigationContentGenerator(codeGenerator, logger).generate(outputPackage, screens)
        DeepLinkGenerator(codeGenerator, logger).generate(outputPackage, screens)

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
            // KSP represents KClass arguments as KSType
            val qualifiedName = when (componentType) {
                is com.google.devtools.ksp.symbol.KSType -> componentType.declaration.qualifiedName?.asString()
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

    private fun isComponentContextParam(param: KSValueParameter): Boolean {
        val typeName = param.type.resolve().declaration.qualifiedName?.asString() ?: return false
        return typeName == "com.arkivanov.decompose.ComponentContext"
    }

    private fun isNavigateToParam(param: KSValueParameter): Boolean {
        val type = param.type.resolve()
        val decl = type.declaration.qualifiedName?.asString() ?: return false
        // Match Function2<NavConfig, Boolean?, Unit>
        if (decl != "kotlin.Function2") return false
        val args = type.arguments
        if (args.size != 3) return false
        val firstArg = args[0].type?.resolve()?.declaration?.qualifiedName?.asString()
        return firstArg == "com.kmpnavbuilder.runtime.NavConfig"
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
