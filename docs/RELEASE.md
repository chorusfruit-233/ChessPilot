# GitHub 构建与发布

`.github/workflows/android-build.yml` 在 `master` 更新时构建，也可在 Actions 页面手动运行。工作流下载并校验固定的官方 Android 命令行工具，自行安装 SDK，避免依赖 runner 预装环境；固定 Android/JDK 工具链和官方 Actions 提交，执行 Kotlin、映射、真实 Stockfish 回归及 lint，并直接核验 APK 中的 API 102 入口、CPU 路径和网络权重。

构建产物为 `ChessPilot-unsigned-<完整提交 SHA>`，包含未签名 APK、`BUILD_INFO.json`、校验和，以及对应的完整项目/Stockfish 源码归档（含同一份 NNUE）。测试和 lint 报告单独存放。源码归档从 Git 中提取，只加入已核验的网络文件，排除本地构建缓存和签名密钥。

发布步骤：

1. 确认目标提交的 Actions 全部成功，下载其构建产物，核对 `BUILD_INFO.json` 的提交与运行 URL，再检查 `SHA256SUMS-unsigned.txt`。
2. 在持有原签名密钥的环境中对齐、签名 APK。签名后重新运行 `tools/check_unified_apk.py`、`apksigner verify` 和 `zipalign -c`，确认包名、版本及证书。
3. 将签名 APK 的文件名、SHA-256 和证书摘要加入 `BUILD_INFO.json`，为最终发布文件重新生成 `SHA256SUMS`。
4. 在已构建的准确提交创建版本标签，将 APK、源码归档、构建信息及校验和放入草稿 Release；附件核对完毕再公开发布。原有的多邻国安装包草稿不参与发布。

CI 不生成临时签名身份，也不把密钥写入仓库。这样每次发布均能使用同一证书，便于用户覆盖安装并保留设置。当前 0.6.0 使用与此前测试 APK 相同的密钥；框架注入、hot reload 和 8 Elite 提速尚待真机验证。
