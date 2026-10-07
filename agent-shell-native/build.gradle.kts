// agent-shell-native — C++17 原生增强层
//
// 定位：agent-shell 的 ProcessChannel SPI 之上的官方原生实现
//（forkpty PTY + ANSI 清洗 + ELF64 补丁），把 yl-ai 的 JNI 层并入框架。
// Kotlin 侧保持纯 JVM（无 Android 依赖）；C++ 源码在 src/main/cpp/，
// 由 CMake 构建（Android NDK 交叉编译或 host 直编），CI 见 native job。
//
// JVM 测试通过 -Pagsh.native.lib=<绝对路径>（透传为系统属性）加载 host 版
// libagsh_native.so；未指定且常规加载失败时测试自动跳过（assumeTrue）。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":agent-shell"))

    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
}

tasks.test {
    // CI native-jvm job：-Pagsh.native.lib=build-host/libagsh_native.so
    val nativeLib = providers.gradleProperty("agsh.native.lib").orElse("")
    if (nativeLib.isPresent && nativeLib.get().isNotBlank()) {
        systemProperty("agsh.native.lib", nativeLib.get())
    }
    systemProperty("agsh.native.strict", providers.gradleProperty("agsh.native.strict").orElse(""))
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// 便捷任务：host 构建（CMake）+ 跑 C++ 测试 + JVM 集成测试一条龙。
// 前置：系统装有 cmake 与 C++17 编译器。
val nativeHostTest by tasks.registering {
    group = "verification"
    description = "CMake host 构建 → C++ 单元测试 → 加载 .so 的 JVM 集成测试"
    doLast {
        val buildDir = layout.buildDirectory.get().asFile.resolve("native-host").absolutePath
        exec {
            commandLine("cmake", "-S", "src/main/cpp", "-B", buildDir, "-DCMAKE_BUILD_TYPE=Release")
        }
        exec {
            commandLine("cmake", "--build", buildDir, "--parallel")
        }
        exec {
            commandLine("ctest", "--test-dir", buildDir, "--output-on-failure")
        }
        exec {
            commandLine(
                "./gradlew", ":agent-shell-native:test",
                "-Pagsh.native.lib=$buildDir/libagsh_native.so",
                "-Pagsh.native.strict=true",
            )
        }
    }
}
