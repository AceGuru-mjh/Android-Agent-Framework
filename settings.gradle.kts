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
//   PR5 → :agent-shell-native（C++17 原生增强层：forkpty PTY / ANSI 清洗 / ELF64 补丁）
//   PR8 → :agent-chat（聊天层：卡片模型 / 历史持久化 / 事件→卡片转译）
include(":agent-core")
include(":agent-tools")
include(":agent-llm")
include(":agent-mcp")
include(":agent-plugin")
include(":agent-chat")
include(":agent-shell")
include(":agent-shell-tools")
include(":agent-shell-native")
include(":examples:simple-agent")
include(":examples:terminal-agent")
