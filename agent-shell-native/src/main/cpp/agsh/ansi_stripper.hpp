// agsh_native — ANSI 转义序列剥离（C++17 状态机）
//
// 语义与 agent-shell 的 AnsiStripper 逐字节对齐（CSI / OSC / \r / \b / BEL），
// 增强点：UTF-8 多字节序列安全透传 —— 字节级状态机只消费 ASCII 控制字符，
// 值 >= 0x80 的 UTF-8 续字节原样放行，不会切断中文 / emoji。
//
// 面向大块终端输出的批量清洗（LLM 输入预处理的热路径），
// 单次 JNI 往返处理整个缓冲区，消除 JVM 逐字符循环开销。
#pragma once

#include <cstddef>
#include <string>

namespace agsh {

// 状态机剥离。out_cap 为 0 / out 为 nullptr 时仅返回所需输出长度。
size_t ansi_strip(const char* in, size_t in_len, char* out, size_t out_cap);

// 便捷封装：剥离转义序列、\r、\b、BEL。
std::string ansi_strip_str(const std::string& input);

// 对应 AnsiStripper.clean：strip → 逐行 \r 覆盖折叠 → 3+ 换行折叠 → 去尾空白。
std::string ansi_clean(const std::string& input);

} // namespace agsh
