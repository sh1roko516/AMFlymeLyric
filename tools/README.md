# tools

逆向阶段用的辅助脚本，**不参与构建**：

| 脚本 | 作用 |
| --- | --- |
| `dexstrings.mjs` | 枚举 APK 内的字符串（DexSQL 不可用时的替代） |
| `dexclasses.mjs` | 枚举 APK 内的类名 |
| `ttmlinfo.mjs` | 统计 TTML 的 `<p begin/end>` 行数 |
| `dbdump.cjs` | 读取 LSPosed `modules_config.db`（只读排查用） |
| `build_module.sh` | 早期构建脚本，已被仓库根目录的 `build.sh` 取代 |

`dexsql.exe` 是 LSPosed 官方的 DexSQL 工具，体积较大且非本仓库代码，**没有入库**；
需要时从 LSPosed 发布包/管理器里取，或参考 `docs/reverse-engineering.md` 的 DexSQL 一节。
