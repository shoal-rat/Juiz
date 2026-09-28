import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("app.juiz.desk.DeskKt")
    applicationName = "juiz-desk"
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
