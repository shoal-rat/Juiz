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
    mainClass.set("app.juiz.sim.MainKt")
    applicationName = "juiz-sim"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(project(":core"))
    implementation(project(":desk"))
    implementation(libs.sqldelight.sqlite.driver)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
