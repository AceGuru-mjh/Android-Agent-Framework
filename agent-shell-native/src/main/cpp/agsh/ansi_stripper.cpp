#include "ansi_stripper.hpp"

namespace agsh {

namespace {

enum class St {
    Ground, // 普通文本
    Esc,    // ESC 后：'[' 进 CSI，']' 进 OSC，其它吞掉
    Csi,    // 参数字节 0x20..0x3F，终结字节 0x40..0x7E
    Osc,    // 到 BEL 或 ST(ESC \) 结束
    OscEsc, // OSC 中遇到 ESC：下一个是 '\\' 才终结
};

} // namespace

size_t ansi_strip(const char* in, size_t in_len, char* out, size_t out_cap) {
    if (in == nullptr || in_len == 0) return 0;

    size_t w = 0;
    auto emit = [&](char c) {
        if (out != nullptr && w < out_cap) out[w] = c;
        ++w;
    };

    St st = St::Ground;
    for (size_t i = 0; i < in_len; ++i) {
        const unsigned char c = static_cast<unsigned char>(in[i]);
        switch (st) {
            case St::Ground:
                if (c == 0x1B) { st = St::Esc; }
                else if (c == '\r' || c == '\b' || c == 0x07) { /* 丢弃 */ }
                else { emit(static_cast<char>(c)); } // UTF-8 多字节续字节同样透传
                break;

            case St::Esc:
                if (c == '[') st = St::Csi;
                else if (c == ']') st = St::Osc;
                else st = St::Ground; // 两字符转义（ESC X），整体吞掉
                break;

            case St::Csi:
                if (c >= 0x20 && c <= 0x3F) { /* 参数字节 */ }
                else if (c >= 0x40 && c <= 0x7E) st = St::Ground; // 终结字节
                else st = St::Ground; // 非法序列：终止并丢弃
                break;

            case St::Osc:
                if (c == 0x07) st = St::Ground;        // BEL 终结
                else if (c == 0x1B) st = St::OscEsc;  // 可能是 ST
                break;

            case St::OscEsc:
                if (c == '\\') st = St::Ground; // ST 终结
                else st = St::Osc;             // 未构成 ST：继续等
                break;
        }
    }
    return w;
}

std::string ansi_strip_str(const std::string& input) {
    std::string out;
    out.reserve(input.size());
    const size_t need = ansi_strip(input.data(), input.size(), nullptr, 0);
    if (need == 0) return out;
    out.resize(need);
    const size_t written = ansi_strip(input.data(), input.size(), out.data(), out.size());
    out.resize(written);
    return out;
}

std::string ansi_clean(const std::string& input) {
    // ① 先折叠 \r 覆盖（进度条语义）—— strip 会删除全部 \r，
    //    顺序颠倒会让折叠永远空转（与 JVM 版 PR5 修正保持一致）。
    std::string collapsed;
    collapsed.reserve(input.size());
    size_t line_start = 0;
    for (size_t i = 0; i <= input.size(); ++i) {
        if (i == input.size() || input[i] == '\n') {
            // 在 [line_start, i) 内找最后一个 \r
            size_t last_cr = std::string::npos;
            for (size_t j = line_start; j < i; ++j) {
                if (input[j] == '\r') last_cr = j;
            }
            const size_t from = (last_cr == std::string::npos) ? line_start : last_cr + 1;
            collapsed.append(input, from, i - from);
            if (i != input.size()) collapsed.push_back('\n');
            line_start = i + 1;
        }
    }

    // ② 剥离 ANSI 转义序列与控制字符（UTF-8 安全）。
    std::string s = ansi_strip_str(collapsed);

    // ③ 3+ 连续换行折叠为空行。
    std::string folded;
    folded.reserve(s.size());
    int run = 0;
    for (char c : s) {
        if (c == '\n') {
            if (++run <= 2) folded.push_back(c);
        } else {
            run = 0;
            folded.push_back(c);
        }
    }

    // ④ 去尾部空白。
    while (!folded.empty() &&
           (folded.back() == ' ' || folded.back() == '\t' ||
            folded.back() == '\n' || folded.back() == '\r')) {
        folded.pop_back();
    }
    return folded;
}

} // namespace agsh
