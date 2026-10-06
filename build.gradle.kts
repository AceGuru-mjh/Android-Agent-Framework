// Android Agent Framework — root build
// 模块均为纯 JVM Kotlin（Android 工程可直接依赖 jar/aar），CI 无需 Android SDK。
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

subprojects {
    repositories {
        mavenCentral()
    }
}
