package com.kmpnavbuilder.compiler.generators

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.kmpnavbuilder.compiler.models.ScreenModel
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

class ScreenConfigGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {
    private val serializableAnnotation = ClassName("kotlinx.serialization", "Serializable")
    private val navConfigInterface = ClassName("com.kmpnavbuilder.runtime", "NavConfig")

    fun generate(outputPackage: String, screens: List<ScreenModel>) {
        val sealedInterface = TypeSpec.interfaceBuilder("ScreenConfig")
            .addModifiers(KModifier.SEALED)
            .addAnnotation(AnnotationSpec.builder(serializableAnnotation).build())
            .addSuperinterface(navConfigInterface)

        for (screen in screens) {
            if (screen.params.isEmpty()) {
                // data object for parameterless screens
                val entry = TypeSpec.objectBuilder(screen.configName)
                    .addModifiers(KModifier.DATA)
                    .addAnnotation(AnnotationSpec.builder(serializableAnnotation).build())
                    .addSuperinterface(ClassName(outputPackage, "ScreenConfig"))
                    .build()
                sealedInterface.addType(entry)
            } else {
                // data class for screens with parameters
                val entry = TypeSpec.classBuilder(screen.configName)
                    .addModifiers(KModifier.DATA)
                    .addAnnotation(AnnotationSpec.builder(serializableAnnotation).build())
                    .addSuperinterface(ClassName(outputPackage, "ScreenConfig"))

                val constructorBuilder = com.squareup.kotlinpoet.FunSpec.constructorBuilder()
                for (param in screen.params) {
                    constructorBuilder.addParameter(param.name, param.type)
                    entry.addProperty(
                        PropertySpec.builder(param.name, param.type)
                            .initializer(param.name)
                            .build()
                    )
                }
                entry.primaryConstructor(constructorBuilder.build())

                sealedInterface.addType(entry.build())
            }
        }

        val fileSpec = FileSpec.builder(outputPackage, "ScreenConfig")
            .addType(sealedInterface.build())
            .build()

        fileSpec.writeTo(codeGenerator, Dependencies.ALL_FILES)
        logger.info("Generated ScreenConfig with ${screens.size} entries")
    }
}
