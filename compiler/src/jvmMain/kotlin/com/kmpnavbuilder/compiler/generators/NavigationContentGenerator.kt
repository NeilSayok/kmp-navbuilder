package com.kmpnavbuilder.compiler.generators

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.kmpnavbuilder.compiler.models.ScreenModel
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.ksp.writeTo

class NavigationContentGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {
    fun generate(outputPackage: String, screens: List<ScreenModel>) {
        val screenConfigClass = ClassName(outputPackage, "ScreenConfig")
        val childComponentClass = ClassName(outputPackage, "ChildComponent")

        val childStackClass = ClassName("com.arkivanov.decompose.router.stack", "ChildStack")
        val valueClass = ClassName("com.arkivanov.decompose.value", "Value")
        val modifierClass = ClassName("androidx.compose.ui", "Modifier")
        val composableAnnotation = ClassName("androidx.compose.runtime", "Composable")
        val childrenFun = MemberName("com.arkivanov.decompose.extensions.compose.stack", "Children")
        val stackAnimationFun = MemberName("com.arkivanov.decompose.extensions.compose.stack.animation", "stackAnimation")
        val slideFun = MemberName("com.arkivanov.decompose.extensions.compose.stack.animation", "slide")

        val stackType = valueClass.parameterizedBy(
            childStackClass.parameterizedBy(STAR, childComponentClass)
        )

        val funSpec = FunSpec.builder("NavigationContent")
            .addAnnotation(composableAnnotation)
            .addParameter("stack", stackType)
            .addParameter(
                ParameterSpec.builder("modifier", modifierClass)
                    .defaultValue("%T", modifierClass)
                    .build()
            )

        val codeBlock = CodeBlock.builder()
            .beginControlFlow(
                "%M(\nstack = stack,\nmodifier = modifier,\nanimation = %M(%M()),\n)",
                childrenFun, stackAnimationFun, slideFun,
            )
            .beginControlFlow("child -> when (val instance = child.instance)")

        for (screen in screens) {
            val childEntry = ClassName(outputPackage, "ChildComponent", screen.configName)
            val composableMember = MemberName(screen.composablePackage, screen.composableFunctionName)

            codeBlock.addStatement(
                "is %T -> %M(instance.component)",
                childEntry,
                composableMember,
            )
        }

        codeBlock.endControlFlow() // when
        codeBlock.endControlFlow() // Children

        funSpec.addCode(codeBlock.build())

        val fileSpec = FileSpec.builder(outputPackage, "NavigationContent")
            .addFunction(funSpec.build())
            .build()

        fileSpec.writeTo(codeGenerator, Dependencies.ALL_FILES)
        logger.info("Generated NavigationContent")
    }
}
