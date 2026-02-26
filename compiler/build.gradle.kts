plugins {
    alias(libs.plugins.kotlinMultiplatform)
    `maven-publish`
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(libs.ksp.symbol.processing.api)
            implementation(libs.kotlinpoet)
            implementation(libs.kotlinpoet.ksp)
        }
    }
}
