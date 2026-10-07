#include "elf_patcher.hpp"

#include <array>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <unistd.h>
#include <vector>

namespace agsh {

namespace {

constexpr uint16_t PT_LOAD = 1;
constexpr uint16_t PT_DYNAMIC = 2;

constexpr uint64_t DT_NULL = 0;
constexpr uint64_t DT_STRTAB = 5;
constexpr uint64_t DT_SONAME = 14;
constexpr uint64_t DT_RPATH = 15;
constexpr uint64_t DT_DEBUG = 21;
constexpr uint64_t DT_RUNPATH = 29;

constexpr int kCStringMax = 512;

struct WrappedFd {
    int fd = -1;
    ~WrappedFd() { if (fd >= 0) ::close(fd); }
};

bool pread_exact(int fd, void* buf, size_t n, off_t off) {
    size_t done = 0;
    auto* p = static_cast<char*>(buf);
    while (done < n) {
        ssize_t r = ::pread(fd, p + done, n - done, off + static_cast<off_t>(done));
        if (r < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (r == 0) return false; // EOF
        done += static_cast<size_t>(r);
    }
    return true;
}

bool pwrite_exact(int fd, const void* buf, size_t n, off_t off) {
    size_t done = 0;
    const auto* p = static_cast<const char*>(buf);
    while (done < n) {
        ssize_t r = ::pwrite(fd, p + done, n - done, off + static_cast<off_t>(done));
        if (r < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        done += static_cast<size_t>(r);
    }
    return true;
}

uint64_t le64(const void* p) {
    const auto* b = static_cast<const unsigned char*>(p);
    uint64_t v = 0;
    for (int i = 7; i >= 0; --i) v = (v << 8) | b[i];
    return v;
}

uint16_t le16(const void* p) {
    const auto* b = static_cast<const unsigned char*>(p);
    return static_cast<uint16_t>(b[0] | (b[1] << 8));
}

struct DynEntry {
    uint64_t tag;
    uint64_t value;
    uint64_t file_off; // 该条目在文件中的偏移
};

struct DynInfo {
    std::vector<DynEntry> entries;
    uint64_t strtab_file_off = 0;
    bool has_strtab = false;
    uint64_t file_size = 0;
};

// 解析 ELF64 LE 的动态段与 strtab 文件偏移（语义对齐 JVM 版 parse()）。
bool parse_dynamic(int fd, DynInfo* out, std::string* err) {
    char ehdr[64];
    if (!pread_exact(fd, ehdr, sizeof(ehdr), 0)) {
        if (err) *err = "read ehdr failed";
        return false;
    }
    if (std::memcmp(ehdr, "\x7f" "ELF", 4) != 0) {
        if (err) *err = "not an ELF";
        return false;
    }
    if (static_cast<unsigned char>(ehdr[4]) != 2 || static_cast<unsigned char>(ehdr[5]) != 1) {
        if (err) *err = "not 64-bit little-endian";
        return false;
    }

    const uint64_t phoff = le64(ehdr + 0x20);
    const uint16_t phentsize = le16(ehdr + 0x36);
    const uint16_t phnum = le16(ehdr + 0x38);
    if (phoff == 0 || phentsize < 56 || phnum == 0 || phnum > 128) {
        if (err) *err = "bad program header table";
        return false;
    }

    uint64_t dyn_offset = 0;
    uint64_t dyn_size = 0;
    bool has_dyn = false;
    std::vector<std::array<uint64_t, 3>> loads; // {p_offset, p_vaddr, p_filesz}

    std::vector<char> phbuf(static_cast<size_t>(phentsize));
    for (int i = 0; i < phnum; ++i) {
        if (!pread_exact(fd, phbuf.data(), phentsize,
                         static_cast<off_t>(phoff + static_cast<uint64_t>(i) * phentsize))) {
            if (err) *err = "read phdr failed";
            return false;
        }
        const uint32_t p_type = static_cast<uint32_t>(le64(phbuf.data() + 0) & 0xFFFFFFFFu);
        const uint64_t p_offset = le64(phbuf.data() + 8);
        const uint64_t p_vaddr = le64(phbuf.data() + 16);
        const uint64_t p_filesz = le64(phbuf.data() + 32);
        if (p_type == PT_DYNAMIC) {
            dyn_offset = p_offset;
            dyn_size = p_filesz;
            has_dyn = true;
        } else if (p_type == PT_LOAD) {
            loads.push_back({p_offset, p_vaddr, p_filesz});
        }
    }
    if (!has_dyn || dyn_size == 0) {
        if (err) *err = "no PT_DYNAMIC";
        return false;
    }

    const uint64_t end = dyn_offset + dyn_size;
    for (uint64_t off = dyn_offset; off + 16 <= end; off += 16) {
        char e[16];
        if (!pread_exact(fd, e, sizeof(e), static_cast<off_t>(off))) break;
        const uint64_t tag = le64(e);
        const uint64_t value = le64(e + 8);
        if (tag == DT_NULL) break;
        if (tag == DT_STRTAB) {
            // vaddr → 文件偏移（经 PT_LOAD 换算）
            for (const auto& l : loads) {
                if (value >= l[1] && value < l[1] + l[2]) {
                    out->strtab_file_off = l[0] + (value - l[1]);
                    out->has_strtab = true;
                    break;
                }
            }
        }
        out->entries.push_back(DynEntry{tag, value, off});
    }
    return true;
}

bool read_cstring_at(int fd, uint64_t offset, std::string* out) {
    if (offset == 0) return false;
    char buf[kCStringMax];
    size_t n = 0;
    while (n < kCStringMax) {
        char one = 0;
        if (!pread_exact(fd, &one, 1, static_cast<off_t>(offset + n))) return false;
        if (one == '\0') break;
        buf[n++] = one;
    }
    out->assign(buf, n);
    return true;
}

const DynEntry* find_entry(std::vector<DynEntry>& entries, uint64_t tag) {
    for (auto& e : entries) {
        if (e.tag == tag) return &e;
    }
    return nullptr;
}

} // namespace

bool elf_is_elf64_le(const std::string& path) {
    WrappedFd w;
    w.fd = ::open(path.c_str(), O_RDONLY);
    if (w.fd < 0) return false;
    char ehdr[6];
    if (!pread_exact(w.fd, ehdr, sizeof(ehdr), 0)) return false;
    return std::memcmp(ehdr, "\x7f" "ELF", 4) == 0
        && static_cast<unsigned char>(ehdr[4]) == 2
        && static_cast<unsigned char>(ehdr[5]) == 1;
}

bool elf_read_runpath(const std::string& path, std::string* out, bool* has) {
    WrappedFd w;
    w.fd = ::open(path.c_str(), O_RDONLY);
    if (w.fd < 0) return false;
    DynInfo info;
    std::string err;
    if (!parse_dynamic(w.fd, &info, &err)) return false;

    const DynEntry* run = find_entry(info.entries, DT_RUNPATH);
    if (run == nullptr) run = find_entry(info.entries, DT_RPATH);
    if (run == nullptr) {
        *has = false;
        out->clear();
        return true;
    }
    if (!info.has_strtab) return false;
    if (!read_cstring_at(w.fd, info.strtab_file_off + run->value, out)) {
        out->clear();
    }
    *has = true;
    return true;
}

bool elf_clear_runpath(const std::string& path, std::string* out_error) {
    WrappedFd w;
    w.fd = ::open(path.c_str(), O_RDWR);
    if (w.fd < 0) {
        if (out_error) *out_error = "open(O_RDWR) failed: " + std::string(strerror(errno));
        return false;
    }
    DynInfo info;
    std::string err;
    if (!parse_dynamic(w.fd, &info, &err)) {
        if (out_error) *out_error = err;
        return false;
    }

    const DynEntry* run = find_entry(info.entries, DT_RUNPATH);
    if (run == nullptr) run = find_entry(info.entries, DT_RPATH);
    if (run == nullptr) return true; // 没有 RUNPATH，视为已清空

    if (!info.has_strtab) {
        if (out_error) *out_error = "DT_STRTAB not found";
        return false;
    }

    // ① tag → DT_DEBUG，value → 0（16 字节一次写）
    char zero_entry[16];
    std::memset(zero_entry, 0, sizeof(zero_entry));
    const uint64_t debug_tag = DT_DEBUG;
    std::memcpy(zero_entry, &debug_tag, 8); // 小端主机直接 memcpy
    if (!pwrite_exact(w.fd, zero_entry, sizeof(zero_entry),
                      static_cast<off_t>(run->file_off))) {
        if (out_error) *out_error = "write dyn entry failed";
        return false;
    }

    // ② strtab 字符串清零（保持长度，读到 NUL/文件尾为止）
    const uint64_t str_off = info.strtab_file_off + run->value;
    for (uint64_t i = 0;; ++i) {
        char one = 0;
        if (!pread_exact(w.fd, &one, 1, static_cast<off_t>(str_off + i))) break;
        if (one == '\0') break;
        char z = '\0';
        if (!pwrite_exact(w.fd, &z, 1, static_cast<off_t>(str_off + i))) {
            if (out_error) *out_error = "zero strtab byte failed";
            return false;
        }
    }
    return true;
}

bool elf_read_soname(const std::string& path, std::string* out) {
    WrappedFd w;
    w.fd = ::open(path.c_str(), O_RDONLY);
    if (w.fd < 0) return false;
    DynInfo info;
    std::string err;
    if (!parse_dynamic(w.fd, &info, &err)) return false;

    const DynEntry* soname = find_entry(info.entries, DT_SONAME);
    if (soname == nullptr) return false;
    if (!info.has_strtab) return false;
    return read_cstring_at(w.fd, info.strtab_file_off + soname->value, out);
}

bool elf_rename_soname(const std::string& path, const std::string& new_name,
                       std::string* out_error) {
    if (new_name.empty()) {
        if (out_error) *out_error = "new soname is empty";
        return false;
    }
    WrappedFd w;
    w.fd = ::open(path.c_str(), O_RDWR);
    if (w.fd < 0) {
        if (out_error) *out_error = "open(O_RDWR) failed";
        return false;
    }
    DynInfo info;
    std::string err;
    if (!parse_dynamic(w.fd, &info, &err)) {
        if (out_error) *out_error = err;
        return false;
    }

    const DynEntry* soname = find_entry(info.entries, DT_SONAME);
    if (soname == nullptr) {
        if (out_error) *out_error = "no DT_SONAME";
        return false;
    }
    if (!info.has_strtab) {
        if (out_error) *out_error = "DT_STRTAB not found";
        return false;
    }

    std::string old_name;
    if (!read_cstring_at(w.fd, info.strtab_file_off + soname->value, &old_name)) {
        if (out_error) *out_error = "read old soname failed";
        return false;
    }
    if (new_name.size() > old_name.size()) {
        if (out_error) *out_error = "new soname longer than old one";
        return false;
    }

    // 新名 + NUL 填充到旧名长度（原地等长改写）。
    std::vector<char> buf(old_name.size() + 1, '\0');
    std::memcpy(buf.data(), new_name.data(), new_name.size());
    if (!pwrite_exact(w.fd, buf.data(), buf.size(),
                      static_cast<off_t>(info.strtab_file_off + soname->value))) {
        if (out_error) *out_error = "write soname failed";
        return false;
    }
    return true;
}

} // namespace agsh
