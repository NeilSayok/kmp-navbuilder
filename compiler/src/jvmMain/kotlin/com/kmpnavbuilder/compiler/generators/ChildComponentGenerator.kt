package com.kmpnavbuilder.compiler.generators

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.kmpnavbuilder.compiler.models.ScreenModel
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

class ChildComponentGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {
    private val navChildComponentClass = ClassName("com.kmpnavbuilder.runtime", "NavChildComponent")
    private val webNavigationOwnerClass = ClassName("com.arkivanov.decompose.router.webhistory", "WebNavigationOwner")

    fun generate(outputPackage: String, screens: List<ScreenModel>) {
        val sealedClass = TypeSpec.classBuilder("ChildComponent")
            .addModifiers(KModifier.SEALED)
            .addSuperinterface(navChildComponentClass)

        for (screen in screens) {
            val returnType = webNavigationOwnerClass.copy(nullable = true)
            val asWebNavOwnerFun = FunSpec.builder("asWebNavigationOwner")
                .addModifiers(KModifier.OVERRIDE)
                .returns(returnType)
                .apply {
                    if (screen.isFlow) {
                        addCode(CodeBlock.of("return component as? %T", webNavigationOwnerClass))
                    } else {
                        addCode(CodeBlock.of("return null"))
                    }
                }
                .build()

            val entry = TypeSpec.classBuilder(screen.configName)
                .addModifiers(KModifier.DATA)
                .superclass(ClassName(outputPackage, "ChildComponent"))
                .primaryConstructor(
                    FunSpec.constructorBuilder()
                        .addParameter("component", screen.componentClassName)
                        .build()
                )
                .addProperty(
                    PropertySpec.builder("component", screen.componentClassName)
                        .initializer("component")
                        .build()
                )
                .addFunction(asWebNavOwnerFun)
                .build()

            sealedClass.addType(entry)
        }

        val optInAnnotation = AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
            .addMember("%T::class", ClassName("com.arkivanov.decompose", "ExperimentalDecomposeApi"))
            .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
            .build()

        val fileSpec = FileSpec.builder(outputPackage, "ChildComponent")
            .addAnnotation(optInAnnotation)
            .addType(sealedClass.build())
            .build()

        fileSpec.writeTo(codeGenerator, Dependencies.ALL_FILES)
        logger.info("Generated ChildComponent with ${screens.size} entries")
    }
}
