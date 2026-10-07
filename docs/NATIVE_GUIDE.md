# Native 层指南（agent-shell-native）

> C++17 原生增强层：forkpty PTY / ANSI 批量清洗 / ELF64 补丁 ——
> 把 yl-ai（AI Terminal）的 JNI 层并入框架，作为 `ProcessChannel` SPI 的官方参考实现。

## 1. 这个模块解决什么

agent-shell（PR4）把 yl-ai 的终端能力抽象为纯 JVM，进程 I/O 走
`ProcessChannel` SPI，默认实现是 **ProcessBuilder 管道**。管道有三个固有局限：

| 局限 | 管道表现 | 原生 PTY 表现 |
| --- | --- | --- |
| 交互式程序 | vim/top/ssh 无法渲染 | 全语义可用 |
| Ctrl-C | 无法送达前台进程组（空操作） | SIGINT 到达整组 |
| 窗口尺寸 | resize 为空操作 | TIOCSWINSZ 真实生效 |

agent-shell-native 提供了 `NativeProcessChannelFactory`：
**可用则一行升级为真 PTY，不可用则一行回退 JVM 管道**，框架其余部分零改动。

```
┌────────────────────────────────────────────────────────┐
│ examples / Android 宿主 App（组装根）                    │
│   NativeProcessChannelFactory.createIfAvailable()        │
│     ?: JvmProcessChannelFactory()      ← 优雅降级        │
└──────────────┬─────────────────────────────────────────┘
               │ 依赖（单向）
┌──────────────▼─────────────────────────────────────────┐
│ agent-shell（纯 JVM，无 native 依赖）                    │
│   ProcessChannel / ProcessChannelFactory（SPI）          │
│   AnsiStripper / ElfRunpathPatcher（JVM 参考实现）       │
└──────────────▲─────────────────────────────────────────┘
               │ 实现（本模块）
┌──────────────┴─────────────────────────────────────────┐
│ agent-shell-native（Kotlin 绑定 + C++17 源码）           │
│   NativePtyChannel / NativeAnsiStripper / NativeElfTools│
│   src/main/cpp/agsh/*.cpp ← libagsh_native.so           │
└─────────────────────────────────────────────────────────┘
```

## 2. C++ 组件一览（src/main/cpp/agsh/）

| 文件 | 能力 | 对应 JVM 实现 |
| --- | --- | --- |
| `pty_process.cpp` | forkpty 拉起、非阻塞读写、TIOCSWINSZ、waitpid 轮询、kill、退出码缓存 | `JvmProcessChannelFactory`（管道） |
| `ansi_stripper.cpp` | 字节级状态机：CSI/OSC/BEL、`\r` 覆盖折叠、UTF-8 安全透传 | `AnsiStripper` |
| `elf_patcher.cpp` | ELF64 动态段原位改写：RUNPATH 清空、SONAME 读改 | `ElfRunpathPatcher` |
| `jni_entry.cpp` | JNI 绑定（15 个 `NativeBridge_native*` 入口） | — |
| `exit_cache.hpp` | 退出码缓存（256 条 + TTL 淘汰） | — |

### 相对 yl-ai 原生层（pty_jni.c）的增强

1. **fork 前 `pthread_sigmask` 阻塞 SIGCHLD** —— 消灭「子进程在父进程登记前死亡」的竞态窗口；
2. **fd→pid 映射收敛在 C++ 侧** —— yl-ai 需要 JNI 回调 Java 静态方法登记；
3. **退出码缓存 128 → 256 条 + TTL**（1 小时未查询即淘汰，防长期句柄泄漏）；
4. **`pty_close_child` 尽力 reap** —— 调用方忘调 waitFor 也不会留僵尸进程；
5. **EINTR / EAGAIN 全路径处理**（read 的 0 语义 = EAGAIN，与 SPI 契约一致）；
6. C → **C++17**（RAII 锁、`std::optional`、`std::chrono`）。

## 3. 构建

### host（开发 / CI / 桌面宿主）

```bash
cmake -S agent-shell-native/src/main/cpp -B build-native -DCMAKE_BUILD_TYPE=Release
cmake --build build-native --parallel
ctest --test-dir build-native --output-on-failure        # C++ 单元测试（17 项）

# JVM 集成测试（加载 host 库；strict=true 防静默跳过）
./gradlew :agent-shell-native:test \
  -Pagsh.native.lib=$PWD/build-native/libagsh_native.so -Pagsh.native.strict=true
```

### Android（arm64-v8a）

```bash
cmake -S agent-shell-native/src/main/cpp -B build-android \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
cmake --build build-android --parallel
```

产物 `libagsh_native.so` 放进 `app/src/main/jniLibs/arm64-v8a/`（AGP 打包解压
到 nativeLibraryDir —— Android 上唯一允许 execve 的位置），Kotlin 侧
`System.loadLibrary("agsh_native")` 自动命中。

CI 产出的成品库：Actions → 对应 run → Artifacts（`libagsh_native-android-arm64-v8a`）。

## 4. Android 宿主组装

```kotlin
val factory = NativeProcessChannelFactory.createIfAvailable()
    ?: JvmProcessChannelFactory()
val runtime = ShellRuntime.create(baseDir, channelFactory = factory)
```

需要真 PTY 的场景（`TerminalSession.startInContainer`）自动获得交互语义；
桌面演示见 `examples/terminal-agent`（启动时打印实际启用的通道类型）。

## 5. 加载策略（NativeBridge）

按序尝试，全部失败则降级（不抛异常，`isLoaded()` = false）：

1. 系统属性 `agsh.native.lib` = **绝对路径**（测试 / 调试）；
2. `System.loadLibrary("agsh_native")`（Android jniLibs、Linux `java.library.path`）。

失败结果缓存 —— 不会每次调用重复支付 UnsatisfiedLinkError 的开销。

## 6. 语义契约（与 agent-shell SPI 逐项对齐）

| 操作 | 语义 |
| --- | --- |
| `read(buf)` | `>0` 数据；`0` 暂无数据（EAGAIN）；`-1` 已关闭 |
| `write(buf)` | EINTR/EAGAIN 自动重试；写满让出 1ms |
| `waitFor(timeout)` | `>=0` 退出码；`-1` 进程未知；`-2` 超时 |
| 信号杀死 | 退出码 = `128 + WTERMSIG` |
| `execve` 失败 | 子进程 `_exit(127)`（stderr 有原因） |
| `close()` | SIGTERM → 300ms 宽限 → SIGKILL → 关 fd + reap |

## 7. 测试矩阵

| 层 | 内容 | 位置 |
| --- | --- | --- |
| C++ 单元测试 | ANSI 状态机 / mini-ELF 构造验证 RUNPATH·SONAME / PTY 端到端（echo、exit 42、SIGKILL、超时、resize）/ 退出码缓存 | `tests/test_main.cpp`（host ctest） |
| JVM 集成测试 | 加载真实 .so：PTY 输出 CRLF 验证、退出码、信号、**ANSI 与 JVM 版逐字节对照**、mini-ELF 循环验证 | `NativeBridgeTest`（`-Pagsh.native.lib`） |
| 交叉编译验证 | arm64-v8a 产物 `readelf` 校验（ELF64/AArch64/DYN）+ JNI 符号 15 个全导出 | CI `native-android` job |

纯 JVM CI 矩阵（Windows/macOS 无 .so）里 JVM 集成测试自动跳过（`assumeTrue`）
—— 保证 `./gradlew build` 在任何环境都是绿的；CI 的 native job 用 strict 模式
防止「静默全跳过」的假绿。

## 8. GPL 边界说明

本模块为**自研 C++ 代码**（Apache-2.0，随仓库 LICENSE），不链接、不包含
PRoot 的任何代码 —— PRoot 二进制仍由宿主注入（`AGSH_PROOT*` 环境变量或
jniLibs），进程边界清晰（GPL「聚合分发」路线，与 yl-ai 的合规策略一致）。
