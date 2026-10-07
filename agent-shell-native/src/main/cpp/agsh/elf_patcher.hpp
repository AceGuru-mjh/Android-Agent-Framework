// agsh_native — ELF64 动态段原位改写（C++17）
//
// 语义与 agent-shell 的 ElfRunpathPatcher（纯 JVM）逐位对齐，增强点：
//   pread/pwrite 系统调用直读直写（JVM 版为 RandomAccessFile + 逐字节
//   seek/write，本实现合并系统调用次数），适合批量处理 jniLibs 目录。
//
// 功能（PRoot 无 root 适配的核心）：
//   - clear_runpath  : DT_RPATH(15)/DT_RUNPATH(29) tag → DT_DEBUG(21)，
//                      strtab 字符串清零 —— 让 LD_LIBRARY_PATH 重新生效；
//   - read_runpath   : 只读检测；
//   - read_soname    : 读 DT_SONAME(14)；
//   - rename_soname  : 原位改写（新名 ≤ 旧名长，补 NUL）。
//
// 仅支持 64 位小端 ELF（与 JVM 版一致；Android arm64-v8a 即此格式）。
#pragma once

#include <cstdint>
#include <string>

namespace agsh {

// ELF64 小端快速判定（magic + ELFCLASS64 + ELFDATA2LSB）。
bool elf_is_elf64_le(const std::string& path);

// 读 RUNPATH：解析失败返回 false；成功且存在 RUNPATH 时 *out 为其值
// （已清空 = 空串），不存在时 *has 为 false。
bool elf_read_runpath(const std::string& path, std::string* out, bool* has);

// 清空 RUNPATH/RPATH（不存在视为成功）。失败时 *out_error 有诊断。
bool elf_clear_runpath(const std::string& path, std::string* out_error);

// 读 DT_SONAME；无 SONAME 返回 false。
bool elf_read_soname(const std::string& path, std::string* out);

// 原位改写 SONAME（新名必须 ≤ 旧名长度）。
bool elf_rename_soname(const std::string& path, const std::string& new_name, std::string* out_error);

} // namespace agsh
