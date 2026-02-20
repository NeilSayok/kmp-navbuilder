package com.kmpnavbuilder.compiler.generators

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.kmpnavbuilder.compiler.models.ScreenModel
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.ksp.writeTo

class NavigationFactoryGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {
    private val componentContextClass = ClassName("com.arkivanov.decompose", "ComponentContext")
    private val navConfigClass = ClassName("com.kmpnavbuilder.runtime", "NavConfig")

    fun generate(outputPackage: String, screens: List<ScreenModel>) {
        val screenConfigClass = ClassName(outputPackage, "ScreenConfig")
        val childComponentClass = ClassName(outputPackage, "ChildComponent")

        val navigateToType = LambdaTypeName.get(
            parameters = listOf(
                ParameterSpec.unnamed(navConfigClass),
                ParameterSpec.unnamed(Boolean::class.asTypeName().copy(nullable = true)),
            ),
            returnType = UNIT,
        )

        // createChild function
        val createChildFun = FunSpec.builder("createChild")
            .addParameter("config", screenConfigClass)
            .addParameter("context", componentContextClass)
            .addParameter("navigateTo", navigateToType)
            .addParameter(
                ParameterSpec.builder("deepLinkUrl", String::class.asTypeName().copy(nullable = true))
                    .defaultValue("null")
                    .build()
            )
            .returns(childComponentClass)

        val whenBlock = CodeBlock.builder()
            .beginControlFlow("return when (config)")

        for (screen in screens) {
            val configEntry = ClassName(outputPackage, "ScreenConfig", screen.configName)
            val childEntry = ClassName(outputPackage, "ChildComponent", screen.configName)

            whenBlock.beginControlFlow("is %T ->", configEntry)

            // Build constructor call
            val constructorArgs = mutableListOf<String>()
            val constructorFormatArgs = mutableListOf<Any>()

            constructorArgs.add("componentContext = context")

            if (screen.hasNavigateTo) {
                constructorArgs.add("navigateTo = navigateTo")
            }

            if (screen.isFlow && screen.hasDeepLinkUrl) {
                constructorArgs.add("deepLinkUrl = deepLinkUrl")
            }

            for (param in screen.params) {
                constructorArgs.add("${param.name} = config.${param.name}")
            }

            whenBlock.addStatement(
                "%T(%T(${constructorArgs.joinToString(", ")}))",
                childEntry,
                screen.componentClassName,
            )
            whenBlock.endControlFlow()
        }

        whenBlock.endControlFlow()
        createChildFun.addCode(whenBlock.build())

        val objectSpec = TypeSpec.objectBuilder("NavigationFactory")
            .addFunction(createChildFun.build())
            .build()

        val fileSpec = FileSpec.builder(outputPackage, "NavigationFactory")
            .addType(objectSpec)
            .build()

        fileSpec.writeTo(codeGenerator, Dependencies.ALL_FILES)
        logger.info("Generated NavigationFactory")
    }
}
