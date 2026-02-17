package com.kmpnavbuilder.compiler.generators

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.kmpnavbuilder.compiler.models.ScreenModel
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

class DeepLinkGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {
    fun generate(outputPackage: String, screens: List<ScreenModel>) {
        val screenConfigClass = ClassName(outputPackage, "ScreenConfig")

        val parseDeepLinkFun = FunSpec.builder("parseDeepLink")
            .addParameter("url", String::class)
            .returns(screenConfigClass)

        val code = CodeBlock.builder()
            .addStatement("val pathSegments = url.substringAfter(\"://\").substringAfter(\"/\")")
            .addStatement("val path = \"/\" + pathSegments.trimStart('/')")
            .beginControlFlow("return when")

        // Sort screens: parameterized paths last (more specific static paths first)
        val sortedScreens = screens.sortedBy { if (it.params.isEmpty()) 0 else 1 }

        for (screen in sortedScreens) {
            val configEntry = ClassName(outputPackage, "ScreenConfig", screen.configName)

            if (screen.params.isEmpty()) {
                // Simple path matching
                if (screen.path != "/") {
                    code.addStatement("path == %S -> %T", screen.path, configEntry)
                }
            } else {
                // Parameterized path matching using regex
                val paramNames = screen.params.map { it.name }
                var regexPattern = screen.path
                for (paramName in paramNames) {
                    regexPattern = regexPattern.replace("{$paramName}", "([^/]+)")
                }

                code.beginControlFlow("Regex(%S).matchEntire(path) != null ->", regexPattern)
                code.addStatement("val matchResult = Regex(%S).find(path)!!", regexPattern)
                for ((index, paramName) in paramNames.withIndex()) {
                    code.addStatement("val %L = matchResult.groupValues[%L]", paramName, index + 1)
                }
                code.addStatement(
                    "%T(${paramNames.joinToString(", ") { "$it = $it" }})",
                    configEntry,
                )
                code.endControlFlow()
            }
        }

        // Default: first screen without params (data object), fallback to first screen
        val defaultScreen = screens.firstOrNull { it.params.isEmpty() } ?: screens.first()
        val defaultConfig = ClassName(outputPackage, "ScreenConfig", defaultScreen.configName)
        code.addStatement("else -> %T", defaultConfig)
        code.endControlFlow()

        parseDeepLinkFun.addCode(code.build())

        // Generate configToPath: reverse mapping (ScreenConfig -> URL path)
        val configToPathFun = FunSpec.builder("configToPath")
            .addParameter("config", screenConfigClass)
            .returns(String::class)

        val pathCode = CodeBlock.builder()
            .beginControlFlow("return when (config)")

        for (screen in screens) {
            val configEntry = ClassName(outputPackage, "ScreenConfig", screen.configName)
            if (screen.params.isEmpty()) {
                pathCode.addStatement("is %T -> %S", configEntry, screen.path)
            } else {
                // Build path with string interpolation for params
                val paramNames = screen.params.map { it.name }
                var pathTemplate = screen.path
                for (paramName in paramNames) {
                    pathTemplate = pathTemplate.replace("{$paramName}", "\${config.$paramName}")
                }
                pathCode.addStatement("is %T -> %P", configEntry, pathTemplate)
            }
        }

        pathCode.endControlFlow()
        configToPathFun.addCode(pathCode.build())

        val objectSpec = TypeSpec.objectBuilder("DeepLinkParser")
            .addFunction(parseDeepLinkFun.build())
            .addFunction(configToPathFun.build())
            .build()

        val fileSpec = FileSpec.builder(outputPackage, "DeepLinkParser")
            .addType(objectSpec)
            .build()

        fileSpec.writeTo(codeGenerator, Dependencies.ALL_FILES)
        logger.info("Generated DeepLinkParser")
    }
}
