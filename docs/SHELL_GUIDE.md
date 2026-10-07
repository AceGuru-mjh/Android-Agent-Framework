# 终端能力指南（agent-shell / agent-shell-tools）

> 终端执行能力全景：共享终端会话、哨兵式命令执行、命令风险分级、挂起式审批闸门、
> 后台作业、Alpine 容器（PRoot）、本地控制 API、审计与崩溃恢复。
> 能力来源：从作者的 [yl-ai（AI Terminal）](https://github.com/iill392/yl-ai) 项目整体移植并按框架架构重写。

```
┌─────────────────────────────────────────────────────────────────┐
│                        宿主（Android / 桌面 / 服务端）              │
│   UI（聊天/终端） · 审批卡 · 前台服务保活                            │
├─────────────────────────────────────────────────────────────────┤
│  agent-shell-tools（工具集成层）                                   │
│    19 个 AgentTool ── ShellToolSet.installInto(registry)          │
│    ShellApprovalHook（PreToolUse 门控）· ShellAuditHook（记账）     │
│    ShellPromptBuilder（系统提示词）· DeviceTools SPI（平台能力）     │
├─────────────────────────────────────────────────────────────────┤
│  agent-shell（终端引擎）                                           │
│    ShellRuntime ── 组装根                                         │
│    ├─ ShellSession / ShellSessionManager（共享终端 · 双流输出）      │
│    ├─ CommandRunner + SentinelProtocol（哨兵执行 · 退出码）         │
│    ├─ CommandPolicy（分级）· ApprovalGate（挂起审批）               │
│    ├─ ShellJob（后台作业）· SessionCheckpoint（崩溃恢复）           │
│    ├─ AlpineContainer + PRootLauncher（容器）                      │
│    ├─ LocalControlServer + WsConnection（本地控制 API）            │
│    ├─ AuditLog（审计）· EnvironmentProbe（环境探测）· SelfCheck     │
│    └─ ProcessChannel SPI（管道默认实现 / 宿主注入真 PTY）            │
├─────────────────────────────────────────────────────────────────┤
│  agent-tools（五段执行管线）· agent-core（ReAct 引擎）               │
└─────────────────────────────────────────────────────────────────┘
```

## 1. 引入

```kotlin
dependencies {
    implementation("com.androidguru.agent:agent-shell:0.1.0")
    implementation("com.androidguru.agent:agent-shell-tools:0.1.0")
}
```

两个模块均为**纯 JVM Kotlin**（零 Android 依赖），Android 宿主与任意 JVM 应用可直接依赖。

## 2. 三行接入一个终端 Agent

```kotlin
// ① 组装运行时（状态落盘在 baseDir：检查点 / 审计 / 作业日志 / 容器 rootfs）
val runtime = ShellRuntime.create(File("data/shell"))
runtime.sessions.create()                       // 打开一个共享终端会话

// ② 19 个工具一次注册
val registry = DefaultToolRegistry()
val shell = ShellToolSet(runtime)
shell.installInto(registry)

// ③ 审批 + 审计钩子挂进执行管线
val hooks = HookRegistry()
hooks.register(shell.approvalHook())
hooks.register(shell.auditHook())

val engine = DefaultAgentEngine(
    llmClient = OpenAiCompatibleClient(llmConfig),
    toolRegistry = registry,
    toolExecutor = DefaultToolExecutor(registry, hooks, policies = shell.recommendedPolicies()),
    config = AgentConfig(systemPrompt = ShellPromptBuilder.build(runtime)),
)
```

完整可运行示例见 [examples/terminal-agent](../examples/terminal-agent/src/main/kotlin/com/androidguru/example/TerminalAgent.kt)。

## 3. 共享终端与哨兵协议

**核心设计**：用户与 AI 共用**同一条**终端会话。AI 执行的命令用户看得见，用户敲的命令
AI 也能读到 —— 随时可接管，没有「AI 偷偷执行」。

`terminal_exec` 的实现是**哨兵协议**：往共享 shell 写入 4 行 payload，从原始输出流里
精确提取「退出码 + 命令输出」：

```sh
printf '\n__AGSH_<id>_BEGIN__\n'    # ① 前置 BEGIN 标记
<command>                            # ② 真正的命令
__agsh_rc=$?                         # ③ 立即暂存退出码
printf '__AGSH_<id>_END__:%s\n' "$__agsh_rc"   # ④ END 标记携带退出码
```

### 与 yl-ai 原实现的对照（本次移植的关键修复）

| # | yl-ai 原实现 | 本框架 | 效果 |
|---|---|---|---|
| 1 | BEGIN 在命令**之后**，`[BEGIN,END)` 区间取不到命令输出 | BEGIN 前置 | 输出完整 |
| 2 | `$?` 展开在第一条 printf 之后，捕获到的是 printf 的状态（恒 0） | `__agsh_rc=$?` 在命令结束后立即暂存 | 退出码精确 |
| 3 | 退出码解析只在当前 chunk 找标记，被 PTY 读边界切开时失败 | 基于**累计缓冲**解析 | 跨 chunk 安全 |
| 4 | echo=false 时命令根本不会被写入 | payload 恒定 4 行，无开关 | 无边界问题 |
| 5 | （未处理）`exit`/`exec` 会杀死 shell 本身 | 命令杀死 shell 时从**通道**取真实退出码 | `exit 42` → 42 |

双流输出模型：**解析者订阅原始流**（哨兵完整保留），**渲染者订阅清洗流**
（[SentinelStripper] 删除哨兵标记与控制序列）。

## 4. 命令风险分级与审批闸门

`CommandPolicy` 把命令分为三级；`ApprovalGate` 提供挂起式人机确认：

| 级别 | 判定示例 | 行为 |
|---|---|---|
| **SAFE** | `ls -la`、`cat a.txt`、`grep x \| wc -l` | 直接放行 |
| **CONFIRM** | `rm f.txt`、`mkdir d`、`echo x > f`、无法识别的命令 | 挂起等人裁决（超时=拒绝） |
| **BLOCKED** | `rm -rf /`、`mkfs`、`curl \| sh`、`sudo`、fork 炸弹 | 硬拦，**永不弹「本次都允许」** |

要点：

- BLOCKED 正则对**整条命令**匹配 —— `ls -la; rm -rf /`、`true && rm -rf /`、
  `echo hi | rm -rf /` 都拦得住（测试背书）；
- 审批记忆的 key 是「工具名 + 命令首词」；BLOCKED 级别永不产生记忆；
- **拒绝回灌**：DENY 不是异常，而是 `HookDecision.Block` 的 reason 进入对话 ——
  模型读到「用户拒绝…请改用其它方式」后会换道，而不是原样重试；
- 本模块填补了框架的一个缺口：`ToolRisk.HIGH` 的 KDoc 声明「HIGH 风险工具默认需要
  会话级确认」，此前只有声明没有实现。

## 5. 工具清单（20 个，与 yl-ai 同名）

| 工具 | 能力 | 安全 |
|---|---|---|
| `terminal_exec` | 共享终端执行命令，取退出码与输出 | CommandPolicy + 审批 |
| `terminal_write` | 向终端写原始输入（应答交互式程序）；行级 WriteGate 扫描（issue #9） | BLOCKED 拦截 / 命令形态引导走 exec |
| `container_exec` | Alpine 容器内执行（root + apk） | CommandPolicy + 审批 |
| `termux_exec` | Termux 环境内一次性执行（apt / bash 工具链，启动开销比容器低） | CommandPolicy + 审批 |
| `fs_read` / `fs_list` | 读文件 / 列目录 | 只读 |
| `fs_write` | 写文件（覆盖/追加）；「本次都允许」记忆键 = 完整路径（issue #17） | 恒需审批（带内容摘要） |
| `file_delete` | 删除 —— 实际**移入回收目录**，可恢复 | 恒需审批 + 路径边界 |
| `http_get` | 抓取网页 / API（HTML 转纯文本） | 只读 |
| `app_list` / `app_launch` / `open_url` | 设备应用能力（DeviceTools SPI） | 宿主决定 |
| `device_info` | 设备信息 | 只读 |
| `clipboard_read` / `clipboard_write` | 剪贴板 | 宿主决定 |
| `job_start` / `job_list` / `job_get` / `job_stop` | 后台作业生命周期 | job_start 过 CommandPolicy |
| `task_finish` | 记录任务结论并收尾 | — |

## 6. 后台作业

`terminal_exec` 的模型是「执行-等待-返回」—— 长任务（起服务器 / 大文件下载 / 长编译）
塞不进去：硬等只会超时，超时后进程状态就丢。作业的模型是「**启动-记账-随时查看**」：

- 跑在**独立进程通道**里，不占用用户终端；输出直接重定向进 `jobs/<id>.log` 边跑边落盘
  （无 `tee` 管道 → 退出码就是命令的真实退出码，无 `PIPESTATUS` 的 bash 依赖）；
- `job_start` 立即返回 id；`job_get` 可等待（≤60s）、读日志、拿到自动探测的服务地址
  （输出里出现 `0.0.0.0:8080` → 归一化为 `http://127.0.0.1:8080`）；
- 状态机 `RUNNING → STOPPING → STOPPED/EXITED`；**App 重启后残留的 running 如实改判
  为 interrupted** —— 留假状态只会误导用户。

## 7. Alpine 容器（PRoot）

Android 的 W^X 限制让应用私有目录不可 execve；Alpine 的 musl 解释器又只在 rootfs 内。
**PRoot 是唯一通路**：拦截 `execve` 并把解释器解析重定向进 rootfs，同时 `-0` 伪装 uid=0。

```kotlin
// 一键安装（多镜像回退 + 官方 SHA256 校验 + tar 解压 + 符号链接自愈）
runtime.container.install { progress -> ... }
// 校验 / 修复 / 诊断
runtime.container.verify()       // busybox/sh/apk/包数据库 关键文件
runtime.container.repairSymlinks() // 66 项 busybox applet 链接自愈
runtime.container.diagnose()
```

安全语义：rootfs 下载强制官方 SHA256 校验（失败删档不重试）；镜像链
清华 → 阿里云 → 南大 → 官方 CDN 逐个回退。PRoot 二进制由**宿主注入**
（环境变量 `AGSH_PROOT` / `AGSH_PROOT_LOADER` / `AGSH_PROOT_LIB`，或 Android 宿主
放进 jniLibs 后映射路径），未注入时容器工具返回结构化安装指引而不是裸崩。

容器 `/root` 映射宿主可写目录 —— 容器内外文件互通。

## 7.5 Termux 环境（补齐 yl-ai 里程碑 2）

yl-ai 的里程碑 2 修通了 Termux 执行链（bash 5.3 + apt / dpkg），本框架把整条链路移植为纯 JVM：

```kotlin
// 安装：多镜像回退 + 官方 SHA256 锚定 + ZipEntry 内容判别符号链接 + SYMLINKS.txt 恢复
runtime.termux.install { progress -> ... }
// 完整性校验 / 诊断 / 卸载
runtime.termux.verify() ; runtime.termux.diagnose() ; runtime.termux.uninstall()
// 会话（proot 自映射方案：-r / + termux 目录自映射，路径与官方 Termux 完全一致）
runtime.createTermuxSession()          // 登录 shell（引导脚本自动 source）
runtime.termuxLauncher.runShellCommand("uname -a")   // 一次性命令
termux_exec 工具                       // AI 侧入口（未安装时返回安装指引）
```

工程要点（全部来自 yl-ai 真机踩坑，KDoc 里有完整叙述）：

- **ZipEntry 内容判别符号链接**：`ZipInputStream` 拿不到 unix 模式位 ——
  用「ELF 魔数 / `#!` / 含 NUL」三判据证明是真身，剩下的短单行路径文本判为链接；
- **SYMLINKS.txt 恢复规则**（DEVLOG 8.7 连错两次的教训）：链接名相对 `$PREFIX`、
  目标相对链接所在目录（兄弟节点）；清单文件本身必须落为普通文件；
- **proot 自映射**：`-r / -b <filesDir>/termux:/data/data/com.termux/files` ——
  环境内 `$PREFIX` 硬编码路径无需改写，与官方 Termux 完全一致；
- **六级逐级诊断**（`diagnoseLadder()`）：文件级检查静态判定，只在真正需要时启动通道；
- `lastLaunchCommand` 记录完整可复现启动命令行，proot 类失败可粘进 adb shell 逐项二分；
- ⚠️ `TermuxBootstrapCatalog` 的官方 SHA256 需要**在宿主发布前重新锚定**：
  占位哈希会被拒绝安装（fail-closed，防供应链投毒）。

## 8. 本地控制 API

供 PC 端或外部编排器复用同一会话。**自研 RFC 6455 WebSocket**（零第三方依赖）：

| 安全项 | 措施 |
|---|---|
| 网络边界 | 仅监听 `127.0.0.1` + **随机端口**；默认关闭 |
| 鉴权 | 32 字节 SecureRandom 令牌；`?token=` 或 `Authorization: Bearer`；**定长 + 恒时比较**；令牌文件 0600，审计脱敏 |
| 命令安全 | 照走 `CommandPolicy`：BLOCKED 直接拒、CONFIRM 弹审批 —— **外部客户端不能绕过用户与审计**；`session.write` 同样过 WriteGate（issue #9） |
| 帧安全 | 4MB 帧上限；分片续帧完整组装（RFC 6455）；未掩码客户端帧 fail-close；64 位长度符号安全 |
| 握手安全 | 握手阶段 10s 超时（slowloris 缓解）；协议自检器 `ControlApiSelfTest` 内置 |

协议：请求 `{"id","method","params"}` → 响应 `{"id","ok","result"|"error"}`。
方法：`server.info` / `session.list` / `session.open` / `session.exec` / `session.write` /
`session.interrupt` / `session.resize` / `session.tail` / `fs.read` / `fs.write` / `fs.list` /
`approval.pending` / `approval.respond`。

## 9. 审计 / 检查点 / 自检

- **审计**（`AuditLog`）：记录命令、退出码、来源（USER/AI/API/SYSTEM）、审批结论；
  接口可插拔，默认 JSONL 文件实现（按天滚动 + 保留期清理）。不记截图、不记终端原始输入
  —— 审计是安全记账，不是监控；
- **崩溃恢复**（`SessionCheckpoint`）：ON_PAUSE 语义处保存 `sessions.json`（tmp→rename
  原子写）；**进程不恢复，恢复的是视觉与工作目录** —— 回放尾部输出 + 说明横幅，
  不留假状态；
- **自检**（`SelfCheck`）：环境描述 + 执行能力探针 + 工具可用性 + 端到端哨兵冒烟
  （期望退出码 7），一段文本全拿到。

## 10. Android 宿主接入建议

框架侧全部纯 JVM；Android 特性通过两个注入点接入：

| 注入点 | 说明 |
|---|---|
| `ProcessChannelFactory` | 注入基于 `forkpty`/`login_tty` 的真 PTY 通道工厂，获得完整交互语义（ssh/vim/top、Ctrl-C 送达前台进程组、窗口尺寸）。默认的 `JvmProcessChannelFactory`（管道）在无 PTY 环境也能覆盖全部非交互场景 |
| `DeviceTools` | 注入 PackageManager / ClipboardManager / Intent / Build / BatteryManager 的真实现（约 100 行胶水）；`DeviceTools.NONE` 时对应工具返回结构化「平台不支持」，模型自动跳过 |
| `ShellEnvironment.extraPathDirs` | 传 `nativeLibraryDir` —— Android 上唯一可 execve 的位置；可执行文件伪装成 `lib*.so` 随 APK 分发，`ElfInspector` 用 PT_INTERP 把它们从共享库里筛出来 |
| `AlpineContainer` / `PRootLauncher` | PRoot 二进制放进 jniLibs（`libproot.so`），`ElfRunpathPatcher.clear()` 清空 RUNPATH 让 `LD_LIBRARY_PATH` 生效 |

其它工程细节（真机踩坑沉淀，全部保留）：

- 共享库与可执行文件的判别依据是 **PT_INTERP**（执行共享库会 SIGSEGV，magic 不算数）；
- `ElfRunpathPatcher`：RUNPATH 优先于 `LD_LIBRARY_PATH`（原地改写 tag → DT_DEBUG）；
- `waitpid` 成功即消费状态 —— 退出码需要缓存（JNI 实现的环形表；JVM 实现由
  `Process.waitFor` 天然覆盖）；
- 哨兵执行前先等 rawOutput 订阅生效（SharedFlow 订阅延迟会吃掉首块输出）。

## 11. 测试与质量

- 命令风险分级：**22 个用例**移植自 yl-ai 设备端测试（含「藏在无害首词之后的删除」）；
- 哨兵协议：跨 chunk 切开解析、负退出码、回显抑制、`exit 42` 杀 shell 场景；
- 端到端：真实 `/bin/sh` 的 7 组集成测试（输出/退出码/管道/127/超时/串行/`cd` 跟踪）；
- 本地控制 API：dispatch 级自检（执行记账闭环、BLOCKED 硬拦、未知方法、恒时令牌）；
- 审批闸门：ALLOW_ALWAYS 记忆、resetSession、cancelAll、BLOCKED 不进记忆。

```bash
./gradlew :agent-shell:test :agent-shell-tools:test
```
