plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":agent-core"))
    implementation(project(":agent-tools"))
    implementation(project(":agent-llm"))
    implementation(project(":agent-mcp"))
    implementation(project(":agent-plugin"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
}

application {
    mainClass.set("com.androidguru.example.SimpleAgentKt")
}
