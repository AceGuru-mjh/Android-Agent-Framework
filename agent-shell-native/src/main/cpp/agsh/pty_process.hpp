// agsh_native — PTY 进程管理（C++17）
//
// 把 yl-ai 的 pty_jni.c（纯 C）升级为 C++ 实现，语义与框架的
// ProcessChannel SPI 完全对齐：
//   read     >0 数据 / 0 暂无数据(EAGAIN) / -1 已关闭
//   waitFor  >=0 退出码 / -1 进程未知 / -2 超时
//   execve 失败 → 子进程 _exit(127)；被信号杀死 → 128 + WTERMSIG
//
// 相对 yl-ai C 版的增强：
//   1. fork 前 pthread_sigmask 阻塞 SIGCHLD（消除「子进程在登记前死亡」
//      的竞态窗口），子进程内恢复默认掩码与信号处置；
//   2. fd→pid 映射收敛在原生层（yl-ai 需要 JNI 回调 Java 静态方法登记）；
//   3. 退出码缓存 256 条 + TTL（见 exit_cache.hpp）；
//   4. close 时尽力 reap，杜绝调用方忘调 waitFor 造成的僵尸进程。
#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace agsh {

struct SpawnRequest {
    std::string program;
    std::vector<std::string> argv; // 不含 argv[0]
    std::vector<std::string> env;  // "KEY=VALUE" 形式
    std::string cwd;               // 空 = 不 chdir
    int rows = 24;
    int cols = 80;
};

struct SpawnHandle {
    int master_fd = -1;
    pid_t pid = -1;
};

enum class SpawnError {
    kNone = 0,
    kOpenPty = 1,   // posix_openpt / grantpt / unlockpt / forkpty 失败
    kFork = 2,      // fork 失败
    kNoMemory = 3,  // 内存不足
    kBadArgs = 4,   // 参数非法（program 为空等）
};

// forkpty 拉起子进程；失败时 *out_error / *out_message 有诊断信息。
SpawnHandle pty_spawn(const SpawnRequest& req, SpawnError* out_error, std::string* out_message);

// 非阻塞读 master：>0 数据 / 0 EAGAIN / -1 EOF 或错误。
ssize_t pty_read(int master_fd, void* buf, size_t cap);

// 写（EINTR 自动重试），返回写入字节数；<0 失败（errno 取反）。
ssize_t pty_write(int master_fd, const void* buf, size_t len);

// TIOCSWINSZ 调整窗口尺寸。
bool pty_resize(int master_fd, int rows, int cols);

// 等待子进程：>=0 退出码（含 128+sig）；-1 未知/已被 reap 且无缓存；-2 超时。
int pty_wait(pid_t pid, int timeout_ms);

// 发送信号。
bool pty_signal(pid_t pid, int signum);

// 存活探测（缓存 + WNOHANG）。
bool pty_is_alive(pid_t pid);

// 关闭 master fd（并清理映射表）。
void pty_close(int master_fd);

// 关闭 master fd + 尽力 reap 子进程（close 语义：先由调用方自行终止）。
void pty_close_child(int master_fd, pid_t pid);

// fd → pid 反查（诊断用）。
std::optional<pid_t> pty_pid_of(int master_fd);

// 后端描述（版本 / 特性），供 SelfCheck 与日志。
std::string pty_backend_info();

} // namespace agsh
