import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

sqldelight {
    databases {
        create("JuizDatabase") {
            packageName.set("app.juiz.core.db")
        }
    }
}

dependencies {
    api(libs.sqldelight.runtime)
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.okhttp)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.sqldelight.sqlite.driver)
    testImplementation(libs.okhttp.mockwebserver)
}
