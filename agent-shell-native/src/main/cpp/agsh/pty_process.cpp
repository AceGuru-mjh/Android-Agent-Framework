#include "pty_process.hpp"
#include "exit_cache.hpp"

#include <cerrno>
#include <chrono>
#include <cstring>
#include <mutex>
#include <thread>
#include <unordered_map>

#include <fcntl.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#if defined(__APPLE__)
#  include <util.h>
#else
#  include <pty.h>
#endif

#if defined(__ANDROID__)
#  include <android/log.h>
#  define AGSH_LOG(...) __android_log_print(ANDROID_LOG_INFO, "agsh_native", __VA_ARGS__)
#else
#  include <cstdio>
#  define AGSH_LOG(...) do { } while (0)
#endif

#define AGSH_VERSION "agsh_native/2.0 (C++17 forkpty+login_tty)"

namespace agsh {

namespace {

constexpr int kPollIntervalMs = 20;

ExitStatusCache g_exit_cache;
std::mutex g_children_mu;
std::unordered_map<int, pid_t> g_master_to_pid; // master fd -> child pid

void sleep_ms(int ms) {
    std::this_thread::sleep_for(std::chrono::milliseconds(ms));
}

// 退出码归一化：正常退出取 WEXITSTATUS；被信号杀死 = 128 + WTERMSIG
// （对齐 yl-ai 原生层与 ProcessChannel 语义）。
int normalize_status(int status) {
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

} // namespace

SpawnHandle pty_spawn(const SpawnRequest& req, SpawnError* out_error, std::string* out_message) {
    SpawnHandle h;
    if (req.program.empty()) {
        if (out_error) *out_error = SpawnError::kBadArgs;
        if (out_message) *out_message = "program is empty";
        return h;
    }

    struct winsize ws {};
    ws.ws_row = req.rows > 0 ? static_cast<unsigned short>(req.rows) : 24;
    ws.ws_col = req.cols > 0 ? static_cast<unsigned short>(req.cols) : 80;

    // fork 前阻塞 SIGCHLD：消灭「子进程在父进程登记完成前死亡」的竞态窗口。
    sigset_t chld, previous;
    sigemptyset(&chld);
    sigaddset(&chld, SIGCHLD);
    pthread_sigmask(SIG_BLOCK, &chld, &previous);

    int master = -1;
    pid_t pid = forkpty(&master, nullptr, nullptr, &ws);

    if (pid == 0) {
        // ---- 子进程 ----
        // 信号掩码跨 execve 保留，必须恢复；同时重置会干扰脚本的处理程序。
        pthread_sigmask(SIG_SETMASK, &previous, nullptr);
        signal(SIGPIPE, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGTERM, SIG_DFL);
        signal(SIGCHLD, SIG_DFL);

        if (!req.cwd.empty() && chdir(req.cwd.c_str()) != 0) {
            // cwd 失效时退回根目录（与 yl-ai 行为一致），不让进程裸死。
            chdir("/");
        }

        std::vector<char*> argv;
        argv.reserve(req.argv.size() + 2);
        argv.push_back(const_cast<char*>(req.program.c_str()));
        for (const auto& a : req.argv) argv.push_back(const_cast<char*>(a.c_str()));
        argv.push_back(nullptr);

        std::vector<char*> envp;
        envp.reserve(req.env.size() + 1);
        for (const auto& e : req.env) envp.push_back(const_cast<char*>(e.c_str()));
        envp.push_back(nullptr);

        execve(req.program.c_str(), argv.data(), envp.data());

        // execve 只在失败时返回。
        dprintf(STDERR_FILENO, "agsh: execve(%s) failed: %s\n",
                req.program.c_str(), strerror(errno));
        _exit(127);
    }

    pthread_sigmask(SIG_SETMASK, &previous, nullptr);

    if (pid < 0) {
        if (out_error) *out_error = SpawnError::kOpenPty;
        if (out_message) *out_message = std::string("forkpty failed: ") + strerror(errno);
        return h;
    }

    // ---- 父进程 ----
    // master 非阻塞：read 返回 0 表 EAGAIN，泵循环以短暂轮询兜底。
    int flags = fcntl(master, F_GETFL, 0);
    if (flags >= 0) fcntl(master, F_SETFL, flags | O_NONBLOCK);

    {
        std::lock_guard<std::mutex> lk(g_children_mu);
        g_master_to_pid[master] = pid;
    }

    AGSH_LOG("spawned pid=%d master=%d prog=%s", (int) pid, master, req.program.c_str());

    h.master_fd = master;
    h.pid = pid;
    return h;
}

ssize_t pty_read(int master_fd, void* buf, size_t cap) {
    for (;;) {
        ssize_t n = ::read(master_fd, buf, cap);
        if (n >= 0) return n;
        if (errno == EINTR) continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK) return 0; // 暂无数据
        return -1; // EOF / 错误
    }
}

ssize_t pty_write(int master_fd, const void* buf, size_t len) {
    size_t total = 0;
    const char* p = static_cast<const char*>(buf);
    while (total < len) {
        ssize_t n = ::write(master_fd, p + total, len - total);
        if (n > 0) { total += static_cast<size_t>(n); continue; }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            // 管道满：让出调度后重试一次，避免上层死循环。
            sleep_ms(1);
            continue;
        }
        return n < 0 ? -errno : 0;
    }
    return static_cast<ssize_t>(total);
}

bool pty_resize(int master_fd, int rows, int cols) {
    if (rows <= 0 || cols <= 0) return false;
    struct winsize ws {};
    ws.ws_row = static_cast<unsigned short>(rows);
    ws.ws_col = static_cast<unsigned short>(cols);
    return ioctl(master_fd, TIOCSWINSZ, &ws) == 0;
}

int pty_wait(pid_t pid, int timeout_ms) {
    // 已退出：直接走缓存（waitpid 只能 reap 一次）。
    if (auto cached = g_exit_cache.lookup(pid)) return *cached;

    if (timeout_ms < 0) timeout_ms = 0;
    const auto deadline = std::chrono::steady_clock::now()
        + std::chrono::milliseconds(timeout_ms);

    for (;;) {
        int status = 0;
        pid_t r = ::waitpid(pid, &status, WNOHANG);
        if (r == pid) {
            int code = normalize_status(status);
            g_exit_cache.record(pid, code);
            return code;
        }
        if (r < 0) {
            if (errno == EINTR) continue;
            // ECHILD：已被 reap（或根本不是我们的子进程）。
            if (auto cached = g_exit_cache.lookup(pid)) return *cached;
            return -1;
        }
        if (std::chrono::steady_clock::now() >= deadline) return -2; // 超时
        sleep_ms(kPollIntervalMs);
    }
}

bool pty_signal(pid_t pid, int signum) {
    return ::kill(pid, signum) == 0;
}

bool pty_is_alive(pid_t pid) {
    if (auto cached = g_exit_cache.lookup(pid)) return false;
    int status = 0;
    pid_t r = ::waitpid(pid, &status, WNOHANG);
    if (r == 0) return true;        // 仍存活
    if (r == pid) {                // 刚被 reap：落缓存
        g_exit_cache.record(pid, normalize_status(status));
        return false;
    }
    return false;
}

void pty_close(int master_fd) {
    {
        std::lock_guard<std::mutex> lk(g_children_mu);
        g_master_to_pid.erase(master_fd);
    }
    ::close(master_fd);
}

void pty_close_child(int master_fd, pid_t pid) {
    // 尽力 reap：调用方即使从未调 waitFor 也不会留下僵尸。
    int status = 0;
    pid_t r = ::waitpid(pid, &status, WNOHANG);
    if (r == pid) {
        g_exit_cache.record(pid, normalize_status(status));
    } else if (r == 0) {
        // 仍存活：先 SIGKILL 兜底（调用方在 close 前应已优雅终止过）。
        ::kill(pid, SIGKILL);
        for (int i = 0; i < 150; ++i) { // 最多 ~3s
            if (::waitpid(pid, &status, WNOHANG) == pid) {
                g_exit_cache.record(pid, normalize_status(status));
                break;
            }
            sleep_ms(20);
        }
    }
    pty_close(master_fd);
}

std::optional<pid_t> pty_pid_of(int master_fd) {
    std::lock_guard<std::mutex> lk(g_children_mu);
    auto it = g_master_to_pid.find(master_fd);
    if (it == g_master_to_pid.end()) return std::nullopt;
    return it->second;
}

std::string pty_backend_info() {
    return AGSH_VERSION " uid=" + std::to_string(::getuid());
}

} // namespace agsh
