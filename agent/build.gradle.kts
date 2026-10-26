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
    mainClass.set("dev.jano.wake.agent.MainKt")
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.jna)
    implementation(libs.jna.platform)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
