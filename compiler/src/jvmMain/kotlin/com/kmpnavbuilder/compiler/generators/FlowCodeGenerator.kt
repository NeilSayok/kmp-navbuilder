package com.kmpnavbuilder.compiler.generators

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.kmpnavbuilder.compiler.models.FlowModel
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Generates 4 files per @NavFlow:
 * - {Prefix}Config.kt — sealed interface for sub-screen configs (no NavConfig superinterface)
 * - {Prefix}Child.kt — sealed class wrapping sub-screen components
 * - {Prefix}Factory.kt — object with createChild(config, context, flow)
 * - {Prefix}Content.kt — @Composable function rendering the nested stack
 */
class FlowCodeGenerator(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) {
    private val serializableAnnotation = ClassName("kotlinx.serialization", "Serializable")
    private val componentContextClass = ClassName("com.arkivanov.decompose", "ComponentContext")
    private val composableAnnotation = ClassName("androidx.compose.runtime", "Composable")

    fun generate(outputPackage: String, flow: FlowModel) {
        val prefix = flow.configPrefix
        generateConfig(outputPackage, prefix, flow)
        generateChild(outputPackage, prefix, flow)
        generateFactory(outputPackage, prefix, flow)
        generateContent(outputPackage, prefix, flow)
        logger.info("Generated ${prefix} flow files (Config, Child, Factory, Content) with ${flow.screens.size} sub-screens")
    }

    private fun generateConfig(outputPackage: String, prefix: String, flow: FlowModel) {
        val configName = "${prefix}Config"
        val sealedInterface = TypeSpec.interfaceBuilder(configName)
            .addModifiers(KModifier.SEALED)
            .addAnnotation(AnnotationSpec.builder(serializableAnnotation).build())

        for (screen in flow.screens) {
            if (screen.params.isEmpty()) {
                val entry = TypeSpec.objectBuilder(screen.configName)
                    .addModifiers(KModifier.DATA)
                    .addAnnotation(AnnotationSpec.builder(serializableAnnotation).build())
                    .addSuperinterface(ClassName(outputPackage, configName))
                    .build()
                sealedInterface.addType(entry)
            } else {
                val entry = TypeSpec.classBuilder(screen.configName)
                    .addModifiers(KModifier.DATA)
                    .addAnnotation(AnnotationSpec.builder(serializableAnnotation).build())
                    .addSuperinterface(ClassName(outputPackage, configName))

                val constructorBuilder = FunSpec.constructorBuilder()
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

        val fileSpec = FileSpec.builder(outputPackage, configName)
            .addType(sealedInterface.build())
            .build()

        fileSpec.writeTo(codeGenerator, Dependencies.ALL_FILES)
    }

    private fun generateChild(outputPackage: String, prefix: String, flow: FlowModel) {
        val childName = "${prefix}Child"
        val sealedClass = TypeSpec.classBuilder(childName)
            .addModifiers(KModifier.SEALED)

        for (screen in flow.screens) {
            val entry = TypeSpec.classBuilder(screen.configName)
                .addModifiers(KModifier.DATA)
                .superclass(ClassName(outputPackage, childName))
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
                .build()

            sealedClass.addType(entry)
        }

        val fileSpec = FileSpec.builder(outputPackage, childName)
            .addType(sealedClass.build())
            .build()

        fileSpec.writeTo(codeGenerator, Dependencies.ALL_FILES)
    }

    private fun generateFactory(outputPackage: String, prefix: String, flow: FlowModel) {
        val factoryName = "${prefix}Factory"
        val configClass = ClassName(outputPackage, "${prefix}Config")
        val childClass = ClassName(outputPackage, "${prefix}Child")

        val createChildFun = FunSpec.builder("createChild")
            .addParameter("config", configClass)
            .addParameter("context", componentContextClass)
            .addParameter("flow", flow.flowComponentClassName)
            .returns(childClass)

        val whenBlock = CodeBlock.builder()
            .beginControlFlow("return when (config)")

        for (screen in flow.screens) {
            val configEntry = ClassName(outputPackage, "${prefix}Config", screen.configName)
            val childEntry = ClassName(outputPackage, "${prefix}Child", screen.configName)

            whenBlock.beginControlFlow("is %T ->", configEntry)

            val constructorArgs = mutableListOf<String>()
            constructorArgs.add("componentContext = context")

            if (screen.hasFlowParam) {
                constructorArgs.add("flow = flow")
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

        val objectSpec = TypeSpec.objectBuilder(factoryName)
            .addFunction(createChildFun.build())
            .build()

        val fileSpec = FileSpec.builder(outputPackage, factoryName)
            .addType(objectSpec)
            .build()

        fileSpec.writeTo(codeGenerator, Dependencies.ALL_FILES)
    }

    private fun generateContent(outputPackage: String, prefix: String, flow: FlowModel) {
        val contentName = "${prefix}Content"
        val childClass = ClassName(outputPackage, "${prefix}Child")
        val childStackClass = ClassName("com.arkivanov.decompose.router.stack", "ChildStack")
        val valueClass = ClassName("com.arkivanov.decompose.value", "Value")
        val modifierClass = ClassName("androidx.compose.ui", "Modifier")
        val childrenFun = MemberName("com.arkivanov.decompose.extensions.compose.stack", "Children")
        val stackAnimationFun = MemberName("com.arkivanov.decompose.extensions.compose.stack.animation", "stackAnimation")
        val slideFun = MemberName("com.arkivanov.decompose.extensions.compose.stack.animation", "slide")

        val stackType = valueClass.parameterizedBy(
            childStackClass.parameterizedBy(STAR, childClass)
        )

        val funSpec = FunSpec.builder(contentName)
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

        for (screen in flow.screens) {
            val childEntry = ClassName(outputPackage, "${prefix}Child", screen.configName)
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

        val fileSpec = FileSpec.builder(outputPackage, contentName)
            .addFunction(funSpec.build())
            .build()

        fileSpec.writeTo(codeGenerator, Dependencies.ALL_FILES)
    }
}
