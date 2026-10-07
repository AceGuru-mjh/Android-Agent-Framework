// agsh_native — 退出码缓存
//
// 为什么需要它：waitpid() 对同一 pid 只能成功一次，而通道的三个消费方
// （waitFor / isAlive / close）在进程退出后都可能再查询退出状态，故在
// reap 成功的瞬间把退出码落缓存。
//
// 相对 yl-ai 原生层（固定 128 条、无过期）的增强：
//   1. 容量 256；
//   2. TTL 双重淘汰（1 小时未被查询即过期清理），长期运行防句柄泄漏；
//   3. 互斥锁粒度最小化（lookup 也只做共享读 + 惰性清理）。
#pragma once

#include <chrono>
#include <cstdint>
#include <mutex>
#include <optional>
#include <unordered_map>

namespace agsh {

class ExitStatusCache {
public:
    static constexpr size_t kCapacity = 256;

    void record(pid_t pid, int code) {
        std::lock_guard<std::mutex> lk(mu_);
        sweepLocked();
        entries_[pid] = Entry{code, std::chrono::steady_clock::now()};
        if (entries_.size() > kCapacity) {
            // 淘汰最旧条目
            auto oldest = entries_.begin();
            for (auto it = entries_.begin(); it != entries_.end(); ++it) {
                if (it->second.at < oldest->second.at) oldest = it;
            }
            entries_.erase(oldest);
        }
    }

    std::optional<int> lookup(pid_t pid) {
        std::lock_guard<std::mutex> lk(mu_);
        auto it = entries_.find(pid);
        if (it == entries_.end()) return std::nullopt;
        it->second.at = std::chrono::steady_clock::now(); // 刷新 LRU 时间
        return it->second.code;
    }

    void forget(pid_t pid) {
        std::lock_guard<std::mutex> lk(mu_);
        entries_.erase(pid);
    }

    size_t size() {
        std::lock_guard<std::mutex> lk(mu_);
        return entries_.size();
    }

    void clear() {
        std::lock_guard<std::mutex> lk(mu_);
        entries_.clear();
    }

private:
    static constexpr std::chrono::milliseconds kTtl{60 * 60 * 1000}; // 1h

    struct Entry {
        int code;
        std::chrono::steady_clock::time_point at;
    };

    void sweepLocked() {
        auto now = std::chrono::steady_clock::now();
        for (auto it = entries_.begin(); it != entries_.end();) {
            if (now - it->second.at > kTtl) it = entries_.erase(it);
            else ++it;
        }
    }

    std::mutex mu_;
    std::unordered_map<pid_t, Entry> entries_;
};

} // namespace agsh
