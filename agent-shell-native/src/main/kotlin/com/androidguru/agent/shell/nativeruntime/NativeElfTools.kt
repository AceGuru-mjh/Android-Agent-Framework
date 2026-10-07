package com.androidguru.agent.shell.nativeruntime

import java.io.File

/**
 * 原生 ELF64 补丁 —— 对应 agent-shell 的 ElfRunpathPatcher（JVM 版）。
 *
 * PRoot 无 root 适配的核心工具（yl-ai 真机踩坑沉淀）：
 * - [clearRunpath]：RUNPATH 会静默覆盖 LD_LIBRARY_PATH，注入前必须清空；
 * - [renameSoname]：「伪装文件名 ↔ 真实 soname」对齐
 *   （jniLibs 只解压 `lib*.so` 命名的文件，如 libtallocimpl.so → libtalloc.so.2）。
 *
 * C++ 实现走 pread/pwrite 直读直写；批处理整个 jniLibs 目录时优先用它。
 */
object NativeElfTools {

    /** 原生库是否可用。 */
    fun available(): Boolean = NativeBridge.isLoaded()

    /**
     * 清空 RUNPATH / RPATH（tag → DT_DEBUG，strtab 字符串清零）。
     * 不存在 RUNPATH 时幂等成功；不可用时返回 false。
     */
    fun clearRunpath(file: File): Boolean {
        if (!available()) return false
        return NativeBridge.nativeElfClearRunpath(file.absolutePath)
    }

    /**
     * 读取 RUNPATH。返回值语义与 JVM 版一致：
     * `null` = 解析失败；`""` = 无 RUNPATH / 已清空。
     */
    fun readRunpath(file: File): String? {
        if (!available()) return null
        return NativeBridge.nativeElfReadRunpath(file.absolutePath)
    }

    /** 读取 DT_SONAME；无 SONAME 或解析失败返回 null。 */
    fun readSoname(file: File): String? {
        if (!available()) return null
        return NativeBridge.nativeElfReadSoname(file.absolutePath)
    }

    /** 原位改写 SONAME（新名必须 ≤ 旧名长度；供 talloc 伪装对齐）。 */
    fun renameSoname(file: File, newName: String): Boolean {
        if (!available()) return false
        return NativeBridge.nativeElfRenameSoname(file.absolutePath, newName)
    }

    /** 后端描述。 */
    fun info(): String = if (available()) NativeBridge.nativeInfo() else "native-unavailable"
}
