// agsh_native — C++ 原生层单元测试（host 构建，无框架、零依赖）
//
// 运行：cmake -B build && cmake --build build && ctest --test-dir build
// （或直接：g++ -std=c++17 tests/test_main.cpp agsh/*.cpp -o t && ./t，
//  jni_entry.cpp 不参与——JNI 绑定由 Kotlin 侧集成测试覆盖。）
#include "../agsh/ansi_stripper.hpp"
#include "../agsh/elf_patcher.hpp"
#include "../agsh/exit_cache.hpp"
#include "../agsh/pty_process.hpp"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

static int g_pass = 0;
static int g_fail = 0;

#define REQUIRE(cond)                                                        \
    do {                                                                     \
        if (!(cond)) {                                                       \
            std::fprintf(stderr, "  FAIL %s:%d: %s\n", __FILE__, __LINE__,   \
                         #cond);                                             \
            ++g_fail;                                                        \
            return;                                                          \
        }                                                                    \
    } while (0)

#define RUN(fn)                                                             \
    do {                                                                     \
        const int before_fail = g_fail;                                       \
        std::printf("[ RUN  ] %s\n", #fn);                                  \
        fn();                                                               \
        if (g_fail == before_fail) ++g_pass;                                \
        std::printf("[ %s ] %s\n",                                           \
                    (g_fail == before_fail) ? " OK " : "FAIL", #fn);         \
    } while (0)

// ------------------------------------------------------------------ ANSI ----

static void test_ansi_csi() {
    // 颜色/光标序列整体剥离
    REQUIRE(agsh::ansi_strip_str("\x1b[31mRED\x1b[0m") == "RED");
    REQUIRE(agsh::ansi_strip_str("\x1b[2J\x1b[H\x1b[1;3fprompt> ") == "prompt> ");
    REQUIRE(agsh::ansi_strip_str("a\x1b[Kb") == "ab");
    // CSI 中断在结尾：不输出悬挂字节
    REQUIRE(agsh::ansi_strip_str("ok\x1b[31") == "ok");
}

static void test_ansi_osc() {
    // OSC 以 BEL 终结
    REQUIRE(agsh::ansi_strip_str("\x1b]0;title\x07visible") == "visible");
    // OSC 以 ST（ESC \）终结
    REQUIRE(agsh::ansi_strip_str("\x1b]0;title\x1b\\visible") == "visible");
    // OSC 内部出现孤 ESC（非 ST）：继续等终结
    REQUIRE(agsh::ansi_strip_str("\x1b]0;a\x1b" "b\x07v") == "v");
}

static void test_ansi_control_chars() {
    REQUIRE(agsh::ansi_strip_str("a\rb\bc\ad") == "abcd");
}

static void test_ansi_utf8_passthrough() {
    // UTF-8 多字节（中文/emoji）安全透传
    REQUIRE(agsh::ansi_strip_str("\x1b[32m你好\x1b[0m")
            == "\xE4\xBD\xA0\xE5\xA5\xBD");
    REQUIRE(agsh::ansi_strip_str("🎉plain")
            == "\xF0\x9F\x8E\x89plain");
}

static void test_ansi_clean() {
    // \r 覆盖折叠：同一行只留最后一段（进度条语义）
    REQUIRE(agsh::ansi_clean("10%\r50%\r100%\ndone") == "100%\ndone");
    // 3+ 连续换行 → 空行
    REQUIRE(agsh::ansi_clean("a\n\n\n\n\nb") == "a\n\nb");
    // 尾部空白清理
    REQUIRE(agsh::ansi_clean("x  \n\n  ") == "x");
}

// ------------------------------------------------------------------ ELF ----

// 构造最小 ELF64 LE：ehdr + 2 个 phdr（PT_LOAD/PT_DYNAMIC）+ 动态条目 + strtab。
// 布局（vaddr == file offset）：
//   0x00 Ehdr(64B)
//   0x40 Phdr[0]=PT_LOAD(offset=0, vaddr=0, filesz=整文件)
//   0x78 Phdr[1]=PT_DYNAMIC(offset=DYN_OFF, filesz=3*16)
//   DYN_OFF: [DT_STRTAB=STR_OFF][DT_RUNPATH=1][DT_NULL]
//   STR_OFF: strtab，"/data/agsh-runpath" 放在偏移 1
static std::string build_mini_elf(const std::string& runpath,
                                 const std::string& soname = "") {
    const size_t kEhdr = 64, kPhdr = 56, kPhnum = 2;
    const size_t dyn_off = kEhdr + kPhdr * kPhnum;             // 176
    const size_t kDynEntries = 4;                                // STRTAB + RUNPATH + SONAME + NULL
    const size_t str_off = dyn_off + kDynEntries * 16;           // 240
    const size_t strtab_len = 1 + runpath.size() + 1 + soname.size() + 1;
    const size_t total = str_off + strtab_len;

    std::string b(total, '\0');
    auto put64 = [&](size_t off, uint64_t v) {
        for (int i = 0; i < 8; ++i) b[off + i] = static_cast<char>((v >> (8 * i)) & 0xFF);
    };
    auto put16 = [&](size_t off, uint16_t v) {
        b[off] = static_cast<char>(v & 0xFF);
        b[off + 1] = static_cast<char>((v >> 8) & 0xFF);
    };

    // Ehdr
    b[0] = '\x7f'; b[1] = 'E'; b[2] = 'L'; b[3] = 'F';
    b[4] = '\x02'; // ELFCLASS64
    b[5] = '\x01'; // ELFDATA2LSB
    b[6] = '\x01'; // EV_CURRENT
    put16(0x10, 3); // ET_DYN
    put16(0x34, 64); // e_ehsize
    put16(0x36, 56); // e_phentsize
    put16(0x38, kPhnum); // e_phnum
    put64(0x20, kEhdr); // e_phoff

    // Phdr[0] PT_LOAD
    put64(0x40 + 0, 1); // p_type=PT_LOAD
    put64(0x40 + 8, 0); // p_offset
    put64(0x40 + 16, 0); // p_vaddr
    put64(0x40 + 32, total); // p_filesz

    // Phdr[1] PT_DYNAMIC
    const size_t p1 = kEhdr + kPhdr;
    put64(p1 + 0, 2); // p_type=PT_DYNAMIC
    put64(p1 + 8, dyn_off); // p_offset
    put64(p1 + 16, dyn_off); // p_vaddr
    put64(p1 + 32, kDynEntries * 16); // p_filesz

    // 动态条目
    const size_t soname_str_off = 1 + runpath.size() + 1;
    put64(dyn_off + 0, 5); // DT_STRTAB
    put64(dyn_off + 8, str_off); // 值 = strtab vaddr
    put64(dyn_off + 16, 29); // DT_RUNPATH
    put64(dyn_off + 24, 1); // strtab 偏移 1
    put64(dyn_off + 32, 14); // DT_SONAME
    put64(dyn_off + 40, soname_str_off);
    put64(dyn_off + 48, 0); // DT_NULL
    put64(dyn_off + 56, 0);

    // strtab: [0]='\0' + runpath + '\0' + soname + '\0'
    b[str_off] = '\0';
    std::memcpy(&b[str_off + 1], runpath.data(), runpath.size());
    b[str_off + 1 + runpath.size()] = '\0';
    if (!soname.empty()) {
        std::memcpy(&b[str_off + soname_str_off], soname.data(), soname.size());
    }
    return b;
}

static std::string write_temp(const std::string& name, const std::string& data) {
    std::string path = "/tmp/agsh_test_" + name + std::to_string(::getpid());
    std::ofstream f(path, std::ios::binary | std::ios::trunc);
    f.write(data.data(), static_cast<std::streamsize>(data.size()));
    f.close();
    ::chmod(path.c_str(), 0644);
    return path;
}

static void test_elf_runpath_read_and_clear() {
    const std::string path = write_temp("runpath.elf", build_mini_elf("/data/agsh-runpath"));

    std::string value;
    bool has = false;
    REQUIRE(agsh::elf_read_runpath(path, &value, &has));
    REQUIRE(has);
    REQUIRE(value == "/data/agsh-runpath");

    std::string err;
    REQUIRE(agsh::elf_clear_runpath(path, &err));

    // 清空后：RUNPATH 条目已被改写为 DT_DEBUG → has=false、value=""
    REQUIRE(agsh::elf_read_runpath(path, &value, &has));
    REQUIRE(!has);
    REQUIRE(value.empty());

    // 再清一次（无 RUNPATH 条目 = DT_DEBUG 占位）应成功且幂等
    REQUIRE(agsh::elf_clear_runpath(path, &err));
    ::unlink(path.c_str());
}

static void test_elf_soname() {
    const std::string path = write_temp("soname.elf", build_mini_elf("/x", "liboldname.so"));
    std::string value;
    REQUIRE(agsh::elf_read_soname(path, &value));
    REQUIRE(value == "liboldname.so");

    std::string err;
    REQUIRE(agsh::elf_rename_soname(path, "libnew.so", &err)); // 更短，允许
    REQUIRE(agsh::elf_read_soname(path, &value));
    REQUIRE(value == "libnew.so");

    REQUIRE(!agsh::elf_rename_soname(path, "libway_too_long_name.so", &err)); // 更长，拒绝
    ::unlink(path.c_str());
}

static void test_elf_rejects_non_elf() {
    const std::string path = write_temp("plain.txt", "hello, not an elf");
    REQUIRE(!agsh::elf_is_elf64_le(path));
    std::string value;
    bool has = false;
    REQUIRE(!agsh::elf_read_runpath(path, &value, &has)); // 解析失败
    REQUIRE(!agsh::elf_clear_runpath(path, nullptr));
    ::unlink(path.c_str());
}

static void test_elf_real_system_binary() {
    // host 上总有 /bin/sh（Linux/macOS CI 均满足）
    const char* candidates[] = {"/bin/sh", "/usr/bin/env", "/bin/ls"};
    std::string sh;
    for (const char* c : candidates) {
        if (::access(c, X_OK) == 0) { sh = c; break; }
    }
    if (sh.empty()) {
        std::printf("  [skip] no system ELF found\n");
        return;
    }
    REQUIRE(agsh::elf_is_elf64_le(sh));
    std::string value;
    bool has = false;
    REQUIRE(agsh::elf_read_runpath(sh, &value, &has)); // 能解析（值任意）
}

// ------------------------------------------------------------------- PTY ----

static void test_pty_spawn_and_read() {
    agsh::SpawnRequest req;
    req.program = "/bin/sh";
    req.argv = {"-c", "echo AGSH_PTY_OK"};
    req.rows = 24;
    req.cols = 80;

    agsh::SpawnError err = agsh::SpawnError::kNone;
    std::string msg;
    agsh::SpawnHandle h = agsh::pty_spawn(req, &err, &msg);
    REQUIRE(h.master_fd >= 0);
    REQUIRE(h.pid > 0);

    std::string out;
    char buf[4096];
    for (int spin = 0; spin < 300; ++spin) {
        ssize_t n = agsh::pty_read(h.master_fd, buf, sizeof(buf));
        if (n > 0) out.append(buf, static_cast<size_t>(n));
        if (out.find("AGSH_PTY_OK") != std::string::npos) break;
        agsh::pty_wait(h.pid, 10);
    }
    // PTY 输出带回车（行规程 ONLCR）：验证这确实是 PTY 而非管道
    REQUIRE(out.find("AGSH_PTY_OK") != std::string::npos);
    REQUIRE(out.find("\r\n") != std::string::npos);

    const int code = agsh::pty_wait(h.pid, 5000);
    REQUIRE(code == 0);
    // 退出码缓存：再次 waitFor 同一 pid 仍能拿到 0（waitpid 只能一次）
    REQUIRE(agsh::pty_wait(h.pid, 1000) == 0);
    REQUIRE(!agsh::pty_is_alive(h.pid));
    agsh::pty_close(h.master_fd);
}

static void test_pty_exit_code() {
    agsh::SpawnRequest req;
    req.program = "/bin/sh";
    req.argv = {"-c", "exit 42"};
    agsh::SpawnError err = agsh::SpawnError::kNone;
    agsh::SpawnHandle h = agsh::pty_spawn(req, &err, nullptr);
    REQUIRE(h.master_fd >= 0);
    REQUIRE(agsh::pty_wait(h.pid, 5000) == 42);
    agsh::pty_close(h.master_fd);
}

static void test_pty_wait_timeout() {
    agsh::SpawnRequest req;
    req.program = "/bin/sh";
    req.argv = {"-c", "sleep 2"};
    agsh::SpawnError err = agsh::SpawnError::kNone;
    agsh::SpawnHandle h = agsh::pty_spawn(req, &err, nullptr);
    REQUIRE(h.master_fd >= 0);
    REQUIRE(agsh::pty_wait(h.pid, 100) == -2); // 超时约定
    REQUIRE(agsh::pty_is_alive(h.pid));
    // SIGKILL → 128 + 9
    REQUIRE(agsh::pty_signal(h.pid, 9));
    REQUIRE(agsh::pty_wait(h.pid, 5000) == 128 + 9);
    agsh::pty_close(h.master_fd);
}

static void test_pty_resize() {
    agsh::SpawnRequest req;
    req.program = "/bin/sh";
    req.argv = {"-c", "exit 0"};
    req.rows = 10;
    req.cols = 40;
    agsh::SpawnError err = agsh::SpawnError::kNone;
    agsh::SpawnHandle h = agsh::pty_spawn(req, &err, nullptr);
    REQUIRE(h.master_fd >= 0);
    REQUIRE(agsh::pty_resize(h.master_fd, 66, 200)); // TIOCSWINSZ 生效
    REQUIRE(agsh::pty_wait(h.pid, 5000) == 0);
    agsh::pty_close(h.master_fd);
}

static void test_pty_bad_args() {
    agsh::SpawnRequest req; // program 为空
    agsh::SpawnError err = agsh::SpawnError::kNone;
    agsh::SpawnHandle h = agsh::pty_spawn(req, &err, nullptr);
    REQUIRE(h.master_fd < 0);
    REQUIRE(err == agsh::SpawnError::kBadArgs);
}

static void test_pty_close_child_reaps() {
    agsh::SpawnRequest req;
    req.program = "/bin/sh";
    req.argv = {"-c", "exit 7"};
    agsh::SpawnError err = agsh::SpawnError::kNone;
    agsh::SpawnHandle h = agsh::pty_spawn(req, &err, nullptr);
    REQUIRE(h.master_fd >= 0);
    // 先等子进程自然退出（退出码 7 已被 waitFor 落缓存）……
    REQUIRE(agsh::pty_wait(h.pid, 5000) == 7);
    // ……再 close_child（关 fd + 尽力 reap）：不应留僵尸，缓存仍可查。
    agsh::pty_close_child(h.master_fd, h.pid);
    REQUIRE(agsh::pty_wait(h.pid, 0) == 7);
    REQUIRE(!agsh::pty_is_alive(h.pid));
}

static void test_pty_backend_info() {
    const std::string info = agsh::pty_backend_info();
    REQUIRE(info.find("agsh_native") == 0);
    REQUIRE(info.find("uid=") != std::string::npos);
}

// ----------------------------------------------------------- ExitCache ----

static void test_exit_cache() {
    agsh::ExitStatusCache cache;
    cache.record(100, 3);
    auto v = cache.lookup(100);
    REQUIRE(v.has_value());
    REQUIRE(*v == 3);
    REQUIRE(!cache.lookup(101).has_value());
    cache.forget(100);
    REQUIRE(!cache.lookup(100).has_value());

    // 容量淘汰
    for (size_t i = 0; i < agsh::ExitStatusCache::kCapacity + 50; ++i) {
        cache.record(1000 + i, static_cast<int>(i));
    }
    REQUIRE(cache.size() <= agsh::ExitStatusCache::kCapacity);
    REQUIRE(cache.lookup(1000).has_value() == false); // 最老条目被淘汰
}

// ------------------------------------------------------------------ main ----

int main() {
    std::printf("== agsh_native tests ==\n");

    RUN(test_ansi_csi);
    RUN(test_ansi_osc);
    RUN(test_ansi_control_chars);
    RUN(test_ansi_utf8_passthrough);
    RUN(test_ansi_clean);

    RUN(test_elf_runpath_read_and_clear);
    RUN(test_elf_soname);
    RUN(test_elf_rejects_non_elf);
    RUN(test_elf_real_system_binary);

    RUN(test_pty_spawn_and_read);
    RUN(test_pty_exit_code);
    RUN(test_pty_wait_timeout);
    RUN(test_pty_resize);
    RUN(test_pty_bad_args);
    RUN(test_pty_close_child_reaps);
    RUN(test_pty_backend_info);

    RUN(test_exit_cache);

    std::printf("\n%d passed, %d failed\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
