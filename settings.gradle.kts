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
//   PR4 → :agent-shell / :agent-shell-tools / :examples:terminal-agent
include(":agent-core")
include(":agent-tools")
include(":agent-llm")
include(":agent-mcp")
include(":agent-plugin")
include(":agent-shell")
include(":agent-shell-tools")
include(":examples:simple-agent")
include(":examples:terminal-agent")
