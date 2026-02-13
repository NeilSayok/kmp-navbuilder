package com.kmpnavbuilder.compiler.generators

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.kmpnavbuilder.compiler.models.ScreenModel
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

class ChildComponentGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {
    fun generate(outputPackage: String, screens: List<ScreenModel>) {
        val sealedClass = TypeSpec.classBuilder("ChildComponent")
            .addModifiers(KModifier.SEALED)

        for (screen in screens) {
            val entry = TypeSpec.classBuilder(screen.configName)
                .addModifiers(KModifier.DATA)
                .superclass(ClassName(outputPackage, "ChildComponent"))
                .primaryConstructor(
                    com.squareup.kotlinpoet.FunSpec.constructorBuilder()
                        .addParameter("component", screen.componentClassName)
                        .build()
                )
                .addProperty(
                    PropertySpec.builder("component", screen.componentClassName)
                        .initializer("component")
                        .build()
                )
                .build()

            sealedClass.addType(entry)
        }

        val fileSpec = FileSpec.builder(outputPackage, "ChildComponent")
            .addType(sealedClass.build())
            .build()

        fileSpec.writeTo(codeGenerator, Dependencies.ALL_FILES)
        logger.info("Generated ChildComponent with ${screens.size} entries")
    }
}
