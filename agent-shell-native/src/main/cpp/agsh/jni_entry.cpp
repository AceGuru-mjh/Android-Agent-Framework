// agsh_native — JNI 绑定（Kotlin: com.androidguru.agent.shell.nativeruntime.NativeBridge）
//
// JNI 暴露面一览（相对 yl-ai 的 pty_jni.c）：
//   - nativeSpawn 返回 (master_fd << 32) | pid 的复合句柄，fd→pid 映射
//     留在 C++ 侧（不再回调 Java 静态方法登记）；
//   - nativeCloseChild 合并「关 fd + 尽力 reap」；
//   - 追加 nativeStripAnsi / ELF 四件套（对应 JVM 版 AnsiStripper / ElfRunpathPatcher）。
#include "ansi_stripper.hpp"
#include "elf_patcher.hpp"
#include "pty_process.hpp"

#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#if defined(__ANDROID__)
#  include <android/log.h>
#endif

namespace {

using agsh::SpawnError;
using agsh::SpawnRequest;

inline std::string to_std_string(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* p = env->GetStringUTFChars(s, nullptr);
    std::string r = (p != nullptr) ? p : "";
    env->ReleaseStringUTFChars(s, p);
    return r;
}

inline std::vector<std::string> to_string_vector(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> out;
    if (arr == nullptr) return out;
    const jsize n = env->GetArrayLength(arr);
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto* s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        out.push_back(to_std_string(env, s));
        if (s != nullptr) env->DeleteLocalRef(s);
    }
    return out;
}

inline jstring to_jstring(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "agsh_native", "loaded %s",
                        agsh::pty_backend_info().c_str());
#endif
    return JNI_VERSION_1_6;
}

// ---------------------------------------------------------------- PTY ----

JNIEXPORT jlong JNICALL
Java_com_androidguru_agent_shell_nativeruntime_NativeBridge_nativeSpawn(
        JNIEnv* env, jclass, jstring program, jobjectArray argv, jobjectArray envp,
        jstring cwd, jint rows, jint cols) {
    SpawnRequest req;
    req.program = to_std_string(env, program);
    req.argv = to_string_vector(env, argv);
    req.env = to_string_vector(env, envp);
    req.cwd = to_std_string(env, cwd);
    req.rows = rows;
    req.cols = cols;

    SpawnError err = SpawnError::kNone;
    std::string message;
    agsh::SpawnHandle h = agsh::pty_spawn(req, &err, &message);
    if (h.master_fd < 0) {
        // 失败编码为负数（-1..-4），Kotlin 侧映射为 IOException。
        return -static_cast<jlong>(static_cast<int>(err));
    }
    // 复合句柄：高 32 位 master fd，低 32 位 pid。
    const uint64_t pid_u = static_cast<uint64_t>(static_cast<uint32_t>(h.pid));
    return static_cast<jlong>((static_cast<uint64_t>(h.master_fd) << 32) | pid_u);
}

JNIEXPORT jint JNICALL
Java_com_androidguru_agent_shell_nativeruntime_NativeBridge_nativeRead(
        JNIEnv* env, jclass, jint fd, jbyteArray buffer) {
    if (buffer == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "buffer is null");
        return -1;
    }
    jbyte* p = env->GetByteArrayElements(buffer, nullptr);
    if (p == nullptr) return -1;
    const jsize cap = env->GetArrayLength(buffer);
    const ssize_t n = agsh::pty_read(fd, p, static_cast<size_t>(cap));
    env->ReleaseByteArrayElements(buffer, p, (n >= 0) ? 0 : JNI_ABORT);
    return static_cast<jint>(n);
}

JNIEXPORT jint JNICALL
Java_com_androidguru_agent_shell_nativeruntime_NativeBridge_nativeWrite(
        JNIEnv* env, jclass, jint fd, jbyteArray buffer, jint offset, jint length) {
    if (buffer == nullptr || offset < 0 || length < 0) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "bad write args");
        return -1;
    }
    jbyte* p = env->GetByteArrayElements(buffer, nullptr);
    if (p == nullptr) return -1;
    const ssize_t n = agsh::pty_write(fd, p + offset, static_cast<size_t>(length));
    env->ReleaseByteArrayElements(buffer, p, JNI_ABORT);
    return static_cast<jint>(n);
}

JNIEXPORT void JNICALL
Java_com_androidguru_agent_shell_nativeruntime_NativeBridge_nativeResize(
        JNIEnv*, jclass, jint fd, jint rows, jint cols) {
    agsh::pty_resize(fd, rows, cols);
}

JNIEXPORT jint JNICALL
Java_com_androidguru_agent_shell_nativeruntime_NativeBridge_nativeWaitFor(
        JNIEnv*, jclass, jint pid, jint timeout_ms) {
    return agsh::pty_wait(static_cast<pid_t>(pid), timeout_ms);
}

JNIEXPORT void JNICALL
Java_com_androidguru_agent_shell_nativeruntime_NativeBridge_nativeSignal(
        JNIEnv*, jclass, jint pid, jint signum) {
    agsh::pty_signal(static_cast<pid_t>(pid), signum);
}

JNIEXPORT jboolean JNICALL
Java_com_androidguru_agent_shell_nativeruntime_NativeBridge_nativeIsAlive(
        JNIEnv*, jclass, jint pid) {
    return agsh::pty_is_alive(static_cast<pid_t>(pid)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_androidguru_agent_shell_nativeruntime_NativeBridge_nativeClose(
        JNIEnv*, jclass, jint fd) {
    agsh::pty_close(fd);
}

JNIEXPORT void JNICALL
Java_com_androidguru_agent_shell_nativeruntime_NativeBridge_nativeCloseChild(
        JNIEnv*, jclass, jint fd, jint pid) {
    agsh::pty_close_child(fd, static_cast<pid_t>(pid));
}

// -------------------------------------------------------------- ANSI ----

JNIEXPORT jbyteArray JNICALL
Java_com_androidguru_agent_shell_nativeruntime_NativeBridge_nativeStripAnsi(
        JNIEnv* env, jclass, jbyteArray input) {
    if (input == nullptr) return nullptr;
    const jsize in_len = env->GetArrayLength(input);
    if (in_len == 0) return env->NewByteArray(0);

    std::vector<char> in(static_cast<size_t>(in_len));
    env->GetByteArrayRegion(input, 0, in_len, reinterpret_cast<jbyte*>(in.data()));

    const size_t need = agsh::ansi_strip(in.data(), in.size(), nullptr, 0);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(need));
    if (out == nullptr) return nullptr;
    if (need > 0) {
        std::vector<char> buf(need);
        agsh::ansi_strip(in.data(), in.size(), buf.data(), buf.size());
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(need),
                                reinterpret_cast<const jbyte*>(buf.data()));
    }
    return out;
}

// --------------------------------------------------------------- ELF ----

JNIEXPORT jstring JNICALL
Java_com_androidguru_agent_shell_nativeruntime_NativeBridge_nativeElfReadRunpath(
        JNIEnv* env, jclass, jstring path) {
    std::string value;
    bool has = false;
    if (!agsh::elf_read_runpath(to_std_string(env, path), &value, &has)) {
        return nullptr; // 解析失败 = null（与 JVM 版语义一致）
    }
    return to_jstring(env, value); // "" = 无 RUNPATH
}

JNIEXPORT jboolean JNICALL
Java_com_androidguru_agent_shell_nativeruntime_NativeBridge_nativeElfClearRunpath(
        JNIEnv* env, jclass, jstring path) {
    std::string err;
    return agsh::elf_clear_runpath(to_std_string(env, path), &err) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_androidguru_agent_shell_nativeruntime_NativeBridge_nativeElfReadSoname(
        JNIEnv* env, jclass, jstring path) {
    std::string value;
    if (!agsh::elf_read_soname(to_std_string(env, path), &value)) return nullptr;
    return to_jstring(env, value);
}

JNIEXPORT jboolean JNICALL
Java_com_androidguru_agent_shell_nativeruntime_NativeBridge_nativeElfRenameSoname(
        JNIEnv* env, jclass, jstring path, jstring new_name) {
    std::string err;
    return agsh::elf_rename_soname(to_std_string(env, path),
                                   to_std_string(env, new_name), &err) ? JNI_TRUE : JNI_FALSE;
}

// --------------------------------------------------------------- 信息 ----

JNIEXPORT jstring JNICALL
Java_com_androidguru_agent_shell_nativeruntime_NativeBridge_nativeInfo(
        JNIEnv* env, jclass) {
    return to_jstring(env, agsh::pty_backend_info());
}

} // extern "C"
