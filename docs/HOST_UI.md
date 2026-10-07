# 宿主 UI 接入指南（agent-chat + 终端 UI 设计经验）

框架内核零 Android 依赖；本文档面向要给框架做 UI 的宿主（Android Compose / 桌面 / Web），
沉淀两块内容：`agent-chat` 的接入方式，以及 yl-ai 在真机上踩过的全部 UI 坑。

## 1. agent-chat：事件 → 卡片的转译层

yl-ai 的核心交互主张是「聊天优先，终端细节可展开」：用户说一句话，AI 去执行，
动作默认折叠成一行卡片，审批是**内联卡片而不是弹窗**（用户需要看到上下文才能判断）。

框架把这套交互沉淀为纯 JVM 模块 `agent-chat`：

```
AgentEvent (agent-core) ──► ChatPresenter ──► StateFlow<List<ChatItem>> ──► 宿主 UI 渲染
ApprovalGate.requests (agent-shell) ─┘        │
ShellJob.ShellJobStore (agent-shell) ─┘       └─► ChatHistoryStore（chat-history.json 原子写）
```

```kotlin
val presenter = ChatPresenter(scope = lifecycleScope, store = ChatHistoryStore(File(filesDir, "chat")))
val bridge = ShellChatBridge(runtime = runtime, presenter = presenter)

bridge.start()                       // 订阅审批闸门 → 自动生成内联审批卡
engine.execute(UserInput(text)).collect { presenter.onAgentEvent(it) }
bridge.observeJobStore(runtime.jobs) // 作业卡与 JobStore 对账（live→RUNNING，消失→FAILED）

// 用户点「拒绝」：
bridge.respond(requestId, ApprovalGate.Verdict.DENY)
// 任务取消：未决审批全部 DENY 回填 + SystemNote，模型下一轮读到拒绝原因后换道
bridge.cancel()
```

### 卡片模型（5 类，@Serializable 可持久化）

| 卡片 | 语义 | 宿主渲染建议 |
|---|---|---|
| `UserMessage` | 用户输入（右侧气泡） | 绿色系强调色 |
| `AssistantMessage` | AI 正文（streaming 标记驱动打字态） | 左对齐不套气泡；Markdown 渲染 |
| `ActionItem` | 工具调用（RUNNING/OK/FAILED/BLOCKED/DENIED 五态） | 默认折叠一行，点开看 outputTail（持久化时截 4000 字符） |
| `ApprovalItem` | 审批请求/结论 | 内联卡片：[允许] [本次都允许] [拒绝] |
| `SystemNote` | 系统提示（取消/错误） | 弱化展示 |

### 工程要点

- **流式权威对齐**：`Complete` 事件携带权威全文，presenter 按「正文只和正文比」对齐
  （空权威保留 / 相等不动 / 前缀补尾 / 其余整体覆盖）—— 修复 yl-ai 流式尾部残缺的教训；
- **持久化纪律**：dirty → debounce 500ms 落盘（修复 yl-ai SharedFlow 订阅前丢信号隐患）；
  `MAX_ITEMS=200`、输出尾部截断、逐条容错解码；
- **作业对账**：加载历史时按 JobStore 重放作业状态 —— live 保持 RUNNING、id 消失改判
  FAILED（"作业已随上一次运行结束"），绝不留假状态。

## 2. yl-ai 真机 UI 坑清单（Android 宿主必读）

以下全部是 yl-ai（`dev.aiterm`）在真机上验证过的教训，做终端类宿主 UI 时逐条对照：

### WebView / xterm.js 终端

1. **前端独占尺寸决策**：终端 fit 逻辑只在 WebView 侧做，加 120ms 防抖 + 重入闸门。
   否则 `fit → onResize → TIOCSWINSZ → resize` 无限循环打爆 1MB 栈直接崩溃；
2. **跨分片多字节安全**：Java ↔ JS 用 **Base64 字节通道**传输出，别用字符串
   （JS桥会把切开的 UTF-8 搅成乱码）；
3. **`@JavascriptInterface` 方法必须吞全部异常 + 节流日志**：桥方法在 WebView 线程跑，
   抛异常会杀渲染进程；`handleXxx` 命名会被 ProGuard 混淆断链（keep 规则别忘）；
4. **会话切换必须 `key(activeId)` 强制重建 WebView + `onRendererDetached()`**：
   复用 WebView 会把上个会话的尺寸/缓冲带进新会话（yl-ai 里程碑 2 的真 bug）；
5. **安卓 IME 无法直接输入到 xterm.js**（所有 WebView 终端的通病）——
   中文输入必须走原生输入行（Compose TextField），再经会话写入终端。

### 会话保活与进程

6. **前台服务**（yl-ai `SessionService`）：START_STICKY + `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
   （API 34+）+ 常驻通知渠道；失败降级为普通 notify。保活是**宿主职责**——框架只管会话本身；
7. **W^X 限制**：应用私有目录不可 execve —— 可执行文件伪装成 `lib*.so` 放 jniLibs
   （`useLegacyPackaging=true` 保证解压），`ShellEnvironment.installBundledExecutables()`
   把它们链进 `$PREFIX/bin`；
8. **`adb input text` 转义不可靠**（空格/引号/中文都丢），端到端自检不要走 adb 输入，
   用框架的 `ControlApiSelfTest` / `SelfCheck`。

### 审批交互

9. **不弹窗做审批**（yl-ai 的核心取舍）：弹窗打断阅读，用户需要看到上下文才能判断
   该不该允许 —— 用 `agent-chat` 的内联 `ApprovalItem`；「本次都允许」的记忆键 =
   工具 + 完整命令首词（fs 操作 = 完整路径，框架已修，见 issue #17）；
10. **审批超时 = DENY（fail-closed）**：5 分钟无响应自动拒绝并回灌给模型，UI 只需订阅
    `ApprovalGate.requests` 渲染当前请求。

### 设置与密钥

11. **API Key 加密存储**：框架提供 `AgentSettingsStore` + `SecretVault`（AES-256-GCM，
    `iv:body` 双 base64）。**Android 宿主必须注入基于 AndroidKeyStore 的 SecretVault 实现**
    —— 默认的纯 JVM PKCS12 实现只靠文件权限保护，是明知取舍不是终点（issue #21 L-14）；
12. **连接测试**：保存前用 `ProviderPresets`（8 家端点预设）+ `normalizeEndpoint`
    （自动补 `/v1`）先做一次 1-token 冒烟请求。

### 设备能力

13. **DeviceTools 真实实现**（约 100 行胶水）：ClipboardManager / PackageManager /
    BatteryManager / StatFs / Intent。`DeviceTools.NONE` 时对应工具返回结构化
    「平台不支持」，模型会自动跳过 —— 但提供真实现后 `app_launch`（Intent + 包名感知）、
    `clipboard_read/write`、`device_info` 才真正可用。
