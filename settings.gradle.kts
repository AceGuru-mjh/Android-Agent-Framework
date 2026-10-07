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
//   PR #22 → :agent-tasks / :examples:long-task-agent（长程任务：计划 / 续跑 / 循环护栏 / 崩溃恢复）
//   PR #23 → :agent-chat（聊天层：卡片模型 / 历史持久化 / 事件→卡片转译）
//   PR #24 → :agent-memory / :examples:memory-agent（长期记忆：召回注入 / 记忆工具 / 会话抽取 / 压缩捕获）
//   PR #25 → :agent-workflow / :examples:workflow-agent（工作流：DAG 调度 / 持久化恢复 / 工作流库 / 蒸馏学习）
include(":agent-core")
include(":agent-tools")
include(":agent-llm")
include(":agent-mcp")
include(":agent-plugin")
include(":agent-chat")
include(":agent-shell")
include(":agent-shell-tools")
include(":agent-shell-native")
include(":agent-tasks")
include(":agent-memory")
include(":agent-workflow")
include(":examples:simple-agent")
include(":examples:terminal-agent")
include(":examples:long-task-agent")
include(":examples:memory-agent")
include(":examples:workflow-agent")
