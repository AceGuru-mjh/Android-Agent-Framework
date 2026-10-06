// Android Agent Framework — root build
// 模块均为纯 JVM Kotlin（Android 工程可直接依赖 jar/aar），CI 无需 Android SDK。
// 仓库声明统一收敛在 settings.gradle.kts（FAIL_ON_PROJECT_REPOS 纪律）。
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// ---- 发布配置：所有库模块统一发布到 GitHub Packages / Maven Local ----
subprojects {
    group = "com.androidguru.agent"
    version = (System.getenv("RELEASE_VERSION") ?: "0.1.0")

    // examples 不发布
    if (path.startsWith(":examples:")) return@subprojects

    // 等 Kotlin JVM 插件就绪后再挂发布（components["java"] 依赖 java 插件）
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "maven-publish")
        configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/AceGuru-mjh/Android-Agent-Framework")
                    credentials {
                        username = System.getenv("GITHUB_ACTOR") ?: "unknown"
                        password = System.getenv("GITHUB_TOKEN") ?: "unknown"
                    }
                }
            }
            publications.create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
    }
}
