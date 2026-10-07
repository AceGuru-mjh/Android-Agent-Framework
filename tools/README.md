# tools

实用脚本（自 yl-ai 融入）：

- **unpack_deb.py** — 解包 `.deb`（ar 归档）提取 payload。宿主获取 PRoot 二进制的
  流程里会用到：从 Termux/Debian 的 proot 包里取出 `proot` / `proot-loader` /
  `libtalloc` 后，按 `NATIVE_GUIDE.md` 的方式注入宿主（jniLibs 或环境变量）。

```bash
python3 tools/unpack_deb.py proot_x.y.z_aarch64.deb ./out
```

相关的 PRoot 注入要点（RUNPATH 清空、ELF 判别）见 `docs/NATIVE_GUIDE.md` 与
`agent-shell` 的 `ElfRunpathPatcher` / `ElfInspector`。
