ChessPilot 0.6.0 将 Hook 模块、Stockfish 引擎和伴侣控制页合并为一个 APK，包名保留为 `com.chessassist`。

- 适配多邻国 6.105.2（versionCode 2497），仅 ARM64。
- 使用官方 libxposed API 102，实现自动/手动 hot reload；重载前停止旧回调、线程和延迟点按，新代码接回当前棋盘。
- Stockfish 19 按 CPU 能力选择 NEON DotProd 或通用 ARMv8 NEON，启用 ThinLTO、预取和循环展开，两条路径共用一份内嵌 NNUE。
- 包含极限思考修复：展示预备应招与深度，预测命中直接复用已有搜索；电脑端隔离主分析和预计算引擎。

### 安装与迁移

需要支持 **libxposed API 102** 的框架。首次迁移时停用旧独立模块，启用 `com.chessassist` 的 ChessPilot，作用域只选多邻国，然后结束多邻国运行再重开一次。打开 ChessPilot 启动引擎服务；APK 更新后服务可能需要重新启动。

APK 使用与此前测试构建相同的签名密钥；其他签名的旧包不能直接覆盖安装。Android 最低版本为 8.1（API 27）。

### 构建与验证

APK 来自 [GitHub Actions 构建](https://github.com/chorusfruit-233/ChessPilot/actions/runs/44124086371)，下载云端产物后沿用原密钥签名。构建提交为 [`0808f79`](https://github.com/chorusfruit-233/ChessPilot/commit/0808f792dca145bff8b2c63770ac6c6025738617)。云端 19 项 Kotlin、9 项映射、5 项真实 Stockfish 测试全部通过；Lint 为 0 错误、72 警告。最终签名 APK 通过 apksigner v2/v3、zipalign 和 API 102/CPU/NNUE 打包核验。完整构建来源和文件校验和见附件 `BUILD_INFO.json` / `SHA256SUMS`。

尚未进行 Android 真机对局、跨版本 hot reload 或骁龙 8 Elite 性能测量，不承诺具体提速倍数。

附件中的源码归档包含本版本项目源码、Stockfish 19 源码及对应 NNUE，构建与迁移说明见仓库文档。
