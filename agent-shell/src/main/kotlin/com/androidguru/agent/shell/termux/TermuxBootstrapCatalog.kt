package com.androidguru.agent.shell.termux

/**
 * Termux bootstrap 下载源目录 —— arm64-v8a bootstrap 的多镜像 URL 与官方 SHA256 锚。
 *
 * 为什么需要"目录"而不是单个常量（与 yl-ai 原实现的差异）：
 * - yl-ai 把 `BOOTSTRAP_URL` / `BOOTSTRAP_SHA256` 写成两个散装常量，只有一个官方源，
 *   GitHub 在部分网络不可达时安装直接失败；
 * - 框架版把"ABI → 镜像列表 + 期望哈希"收敛为一份 [CATALOG]，镜像按序回退，
 *   并把 guest 侧路径常量（[GUEST_FILES] 等）集中到同一处 —— Termux 领域知识单点维护。
 *
 * 【安全锚定声明 —— 宿主发布前必读】
 * CATALOG 里的 SHA256 取自 yl-ai 源码锚定的那个 bootstrap-aarch64.zip（其真机验证版本）。
 * 注意官方 `releases/latest/download` 是**可变 URL**：Termux 发布新版本后内容会变，
 * 旧哈希会合法地校验失败。因此宿主发布前必须用 yl-ai 官方源 / termux-packages 官方
 * release **重新锚定校验**：固定某个 release tag 的下载 URL，并用官方渠道公布的哈希
 * 更新 [BootstrapEntry.sha256]。哈希是这条安装链路里唯一的信任根 —— 镜像可以随便加，
 * 但每个镜像的产物都要过同一把哈希锁。
 */
object TermuxBootstrapCatalog {

    /** 一份 bootstrap 发行物：目标 ABI、文件名、多镜像 URL、期望 SHA256。 */
    data class BootstrapEntry(
        /** Android ABI 名（如 arm64-v8a）。 */
        val abi: String,
        /** 归档文件名（也用作本地缓存文件名）。 */
        val fileName: String,
        /** 多镜像下载 URL，按优先级排序：官方在前，加速代理在后。 */
        val mirrors: List<String>,
        /** 期望 SHA256（64 位十六进制；与大小写无关）。 */
        val sha256: String,
    )

    /** guest 侧根路径：Termux 二进制把 $PREFIX 硬编码进了 ELF（见 TermuxLauncher 的 KDoc）。 */
    const val GUEST_FILES = "/data/data/com.termux/files"

    /** guest 侧 $PREFIX。 */
    const val GUEST_PREFIX = "$GUEST_FILES/usr"

    /** guest 侧 HOME。 */
    const val GUEST_HOME = "$GUEST_FILES/home"

    /** SYMLINKS.txt 里可能出现的绝对目标前缀（剥离后变 $PREFIX 相对路径）。 */
    const val GUEST_FILES_PREFIX = "$GUEST_PREFIX/"

    /**
     * bootstrap 目录。目前只收录 arm64-v8a：
     * yl-ai 只验证过 arm64 真机，其余 ABI 的哈希没有锚定来源，宁缺毋滥。
     */
    val CATALOG: List<BootstrapEntry> = listOf(
        BootstrapEntry(
            abi = "arm64-v8a",
            fileName = "bootstrap-aarch64.zip",
            mirrors = listOf(
                // 官方源（可变 latest URL；发布前请按类注释重新锚定到固定 release tag）
                "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip",
                // 社区 GitHub 加速代理（可用性随时间变化，仅作为第二/第三源；
                // 即使代理被劫持，SHA256 校验也会把坏产物挡在安装之前）
                "https://ghfast.top/https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip",
                "https://gh-proxy.com/https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip",
            ),
            // 来自 yl-ai 源码锚定值；宿主发布前需用官方源重新锚定校验（见类注释）
            sha256 = "c671bde0d9923ede9d9e972634a04042c93b1589b9a2ca9bc5c38848234e8c5b",
        ),
    )

    /** 按 ABI 取发行物；没有锚定记录的 ABI 返回 null（调用方应拒绝安装而不是裸下）。 */
    fun entryFor(abi: String): BootstrapEntry? = CATALOG.firstOrNull { it.abi == abi }

    /**
     * 是否为占位哈希（全 0 或空白）。占位哈希意味着开发者还没锚定校验值，
     * 出于安全考虑必须拒绝安装 —— 这是从 yl-ai 原样保留的防线。
     */
    fun isPlaceholderSha256(sha256: String): Boolean =
        sha256.isBlank() || sha256.all { it == '0' }
}
