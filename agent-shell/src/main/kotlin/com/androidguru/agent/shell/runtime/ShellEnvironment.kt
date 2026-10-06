package com.androidguru.agent.shell.runtime

import java.io.File
import java.nio.file.Files

/**
 * Shell 运行环境 —— 目录锚点、PATH 组装、默认 shell 与环境变量构建。
 *
 * 这是 yl-ai `RuntimeLocator` 的去 Android 化改写：全部路径由构造注入，
 * 不再依赖 `Context` / `nativeLibraryDir`。Android 宿主接入时把 `filesDir`、
 * `nativeLibraryDir` 等映射进来即可；纯 JVM 宿主用工作目录即可。
 */
class ShellEnvironment(
    /** 可写前缀目录（bin/lib/etc 建在其下）。 */
    val prefix: File,
    /** 用户主目录（命令的默认 cwd / `~` 展开）。 */
    val home: File,
    /** 临时目录（注入 TMPDIR）。 */
    val tmp: File,
    /** 额外的 PATH 目录（Android 宿主传 nativeLibraryDir —— 唯一可 execve 的位置）。 */
    val extraPathDirs: List<File> = emptyList(),
    /** 宿主系统 PATH 目录（纯 JVM 一般不需要覆盖）。 */
    val systemPathDirs: List<File> = defaultSystemPathDirs(),
) {

    init {
        listOf(prefix, home, tmp).forEach { it.mkdirs() }
        File(prefix, "bin").mkdirs()
        File(prefix, "lib").mkdirs()
        File(prefix, "etc").mkdirs()
    }

    /** 供会话/作业使用的 PATH 检索目录（探测与符号链接安装共用同一份）。 */
    fun pathDirs(): List<File> = extraPathDirs + systemPathDirs

    /** 组装 PATH 字符串。 */
    fun buildPath(): String = pathDirs().joinToString(":") { it.absolutePath }

    /** 默认 shell：prefix/bin 下的内置 bash → 内置 sh → 系统 sh。 */
    fun defaultShell(): String {
        val candidates = listOf(
            File(prefix, "bin/bash"),
            File(prefix, "bin/sh"),
            File("/bin/sh"),
            File("/system/bin/sh"),
        )
        return candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath ?: "sh"
    }

    /** 构建子进程环境（以系统环境为基底，按 POSIX「首次出现优先」追加本环境条目）。 */
    fun buildEnv(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val base = System.getenv().orEmpty().toMutableMap()
        // 注意：这里的 Map 交给 ProcessChannelFactory 处理；语义上我们希望
        // 本环境的值优先于宿主环境，因此后写入（工厂实现需保证覆盖语义）。
        val overrides = linkedMapOf(
            "PATH" to buildPath(),
            "HOME" to home.absolutePath,
            "PREFIX" to prefix.absolutePath,
            "TMPDIR" to tmp.absolutePath,
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "SHELL" to defaultShell(),
            "AGSH" to "1",
        )
        base.putAll(overrides)
        base.putAll(extra)
        return base
    }

    /**
     * 扫描并链接「伪装成库文件的可执行文件」（Android 场景：只有 `lib*.so` 会被解压到
     * 可 execve 的目录）。用 [ElfInspector] 的 PT_INTERP 判定筛出真正可执行者，
     * 以短名符号链接进 `prefix/bin`。
     */
    fun installBundledExecutables(sourceDirs: List<File>): Int {
        val binDir = File(prefix, "bin")
        var linked = 0
        for (dir in sourceDirs) {
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile) continue
                // Android 安装器只解压 lib*.so；宿主目录无此约束时全量扫描
                if (f.name.startsWith("lib") && f.name.endsWith(".so")) {
                    if (!ElfInspector.inspect(f).executable) continue
                }
                val shortName = bundledShortName(f.name) ?: continue
                val link = File(binDir, shortName)
                if (link.exists()) continue
                runCatching {
                    Files.createSymbolicLink(link.toPath(), f.toPath())
                    linked++
                }
            }
        }
        return linked
    }

    private fun bundledShortName(fileName: String): String? = when {
        fileName.startsWith("lib") && fileName.endsWith(".so") ->
            fileName.removePrefix("lib").removeSuffix(".so").substringBefore('-')
        else -> fileName
    }

    /** 自检描述。 */
    fun describe(): String = buildString {
        appendLine("PREFIX  = ${prefix.absolutePath}")
        appendLine("HOME    = ${home.absolutePath}")
        appendLine("TMPDIR  = ${tmp.absolutePath}")
        appendLine("SHELL   = ${defaultShell()}")
        appendLine("PATH    = ${buildPath()}")
    }

    companion object {

        private fun defaultSystemPathDirs(): List<File> = listOf(
            "/usr/local/sbin", "/usr/local/bin", "/usr/sbin", "/usr/bin", "/sbin", "/bin",
            "/system/bin", "/system/xbin", "/product/bin",
            "/apex/com.android.runtime/bin", "/apex/com.android.art/bin",
            "/system_ext/bin", "/odm/bin", "/vendor/bin",
        ).map(::File).filter { it.isDirectory }
    }
}
