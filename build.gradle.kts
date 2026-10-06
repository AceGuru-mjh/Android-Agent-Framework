// Android Agent Framework — root build
// 模块均为纯 JVM Kotlin（Android 工程可直接依赖 jar/aar），CI 无需 Android SDK。
// 仓库声明统一收敛在 settings.gradle.kts（FAIL_ON_PROJECT_REPOS 纪律）。
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
