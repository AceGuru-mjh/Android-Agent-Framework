pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "android-agent-framework"

// 模块随开发进度逐步合入：
//   PR1 → :agent-core / :agent-tools / :agent-llm
//   PR2 → :agent-mcp
//   PR3 → :agent-plugin / :examples:simple-agent
