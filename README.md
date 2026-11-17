<div align="center">

# ♟️ ChessPilot · 多邻国国际象棋助手

**在多邻国国际象棋里实时看胜率、画推荐箭头、自动走棋 —— 手机自带引擎，或连电脑算力**

*One APK: libxposed API 102 module + Stockfish companion for Duolingo chess: live analysis,
best-move arrows on the board, optional autoplay — on-device or PC-powered.*

[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/tag/a1175815821/ChessPilot?label=release)](https://github.com/a1175815821/ChessPilot/releases)
[![Platform](https://img.shields.io/badge/Android%2014%20%7C%20LSPosed%20%7C%20arm64-blueviolet)](#-快速上手3-分钟)
[![Stars](https://img.shields.io/github/stars/a1175815821/ChessPilot?style=social)](https://github.com/a1175815821/ChessPilot/stargazers)

</div>

> ⚠️ **免责声明**：本项目会修改多邻国的运行行为，违反其服务条款，有封号风险。仅供学习研究，请用小号折腾。

---

## ✨ 它能做什么

| | 功能 | 一句话说明 |
|---|---|---|
| 🎯 | **实时分析** | Hook 棋盘拿到局面（FEN），Stockfish 秒级给出最佳招法和胜率 |
| ➡️ | **推荐箭头** | 直接画在多邻国棋盘上：金色＝推荐招法，红色＝预测对手的应招 |
| 🤖 | **自动走棋** | 可选自动落子（带延迟可调），或悬浮窗按钮半自动确认 |
| 🖥️ | **电脑算力** | 手机连电脑 Stockfish 19（NNUE），断线自动秒切回手机本地引擎 |
| 🧠 | **极限思考** | 提前算出预测后的应招，显示准备招法和深度，命中直接复用 |
| 🔥 | **狂暴模式** | 线程拉满 ＋ 大内存，深算到底（悬浮窗红框角标） |
| 📊 | **控制台** | 对局雷达卡、FEN 小棋盘、事件终端，每个状态都看得见 |

<p align="center">
  <img src="docs/img/console-running.jpg" alt="控制页：对局雷达卡 ＋ 悬浮窗走棋胶囊" width="260" />
  <img src="docs/img/settings.jpg" alt="功能设置与高级设置（远程算力）" width="260" />
  <img src="docs/img/console-stopped.jpg" alt="控制页：一键启停" width="260" />
</p>


## 🚀 快速上手（3 分钟）

前提：手机已 root，并装好**支持 libxposed API 102 的 Xposed 框架**。旧版只支持 API 82/100/101 的 LSPosed 不能加载本版。当前适配多邻国 `6.105.2`（arm64），已核对 APK 接口并通过构建，尚待真机验证；`6.85.7` 是旧版模块的实测基准。

1. 安装 **ChessPilot 0.6.0 合包 APK**（包名仍为 `com.chessassist`），模块、引擎和控制页都在这一包里。
2. 从旧版迁移时，在框架管理器中停用原来的独立模块，再启用新的 **ChessPilot**，作用域只选**多邻国**，将多邻国结束运行后重开一次。
3. 打开 ChessPilot，点「启动引擎服务」（允许通知）→ 进多邻国开一局。状态页可查看模块连接和实际 CPU 路径。

本版保留 `0.5.1` 的极限思考修复。覆盖安装须沿用原签名密钥，设置沿用原包；以后更新合包无需分别更新模块和伴侣。API 102 → API 102 的更新启用自动 hot reload，也可在控制页点「热重载多邻国模块」重试过期/失败的目标。首次从旧 API 迁移仍需重开多邻国；框架不支持或清理失败时也需重开。安装更新可能停止 ChessPilot 自身的引擎服务，重新启动服务即可。详见 [迁移、热重载与 CPU 验证](docs/API102_CPU.md)。

状态页怎么读、装完没反应怎么办 → [docs/USER_GUIDE.md](docs/USER_GUIDE.md)（含故障排查对照表）。

## 🧪 适配环境

Hook 依赖多邻国内部的混淆名，`remap.json` 针对下面的具体构建生成，包含类名及箭头清理方法名。当前映射已与安装包的类定义、方法签名和局面字段布局核对，模块通过构建；原生箭头显示和自动走棋仍需在 LSPosed 真机上验证。

| 项 | 值 |
|---|---|
| 应用 | 多邻国 `com.duolingo` |
| 版本 | **6.105.2**（`versionCode 2497`） |
| 架构 | arm64-v8a（`.apks` 含 3 个 split：base / arm64_v8a / xxxhdpi） |
| 适配用安装包 | 草稿 release 的 `_6.105.2.apks`，SHA-256 `cfd396e2b50c91ac050bfe3e9e37bd88632171936f84f96be545f2dca39e7c51` |
| 映射表基准 | `module/src/main/assets/remap.json` → `"appVersion": "6.105.2"` |
| 验证范围 | APK 静态接口核验、重映射回归测试、模块构建；未进行真机验证 |

旧版 `6.85.7`（`versionCode 2396`）的真机记录保留在逆向笔记中。当前映射只包含 `6.105.2` 的已确认名称；旧的短混淆名在新版可能对应其他类，不能直接作为跨版本候选。

> **为什么源码仓库里没有这个安装包**：它是多邻国的版权作品，公开分发属于侵权；且该 `.apks` 约 132 MiB，超过 GitHub 单文件 100 MB 的硬限制，推上去会被直接拒绝。
> 想复现当前适配环境，装 **6.105.2**；提 issue 时带上版本号和 LSPosed 日志，方便判断是不是版本差异。

## 🖥️ 想更强？连电脑算力（可选）

手机芯片算不过桌面 CPU —— 让电脑替你算，手机只负责看和走：

1. 电脑上双击 `pc/start-pc-engine.bat`（只需 Python 3.8+，Stockfish 自动下载）
2. 手机控制页 → 高级设置 → 算力后端切「远程」，填电脑 IP

连接断了自动切回手机本地引擎，一局都不耽误。细节见 [pc/README.md](pc/README.md)。

## 🏗️ 它是怎么工作的

```
多邻国 App ──(LSPosed Hook：抓 FEN / Rive 画箭头 / 模拟点击)──▶ module
                                                              │ 回环 TCP 127.0.0.1:41717
                                                              ▼
伴侣 App（com.chessassist）：Stockfish 子进程 ＋ 悬浮窗 ＋ 控制页
                                                              │ 局域网 TCP :41718（可选）
                                                              ▼
                                            电脑 pc/pc_engine.py ＋ Stockfish 19
```

| 部分 | 是什么 |
|---|---|
| [module/](module) | API 102 Hook 库，由 `app` 打进同一 APK；注入多邻国进程，读取局面、画箭头与走子 |
| [app/](app) | 唯一安装包：模块入口、Stockfish 引擎、TCP 桥、悬浮窗、控制页、框架热重载服务 |
| [pc/](pc) | 电脑算力端：纯 Python 标准库，零第三方依赖，引擎自动下载 |
| [docs/](docs) | 使用手册、逆向笔记（坐标映射、`remap.json` 再生流程）、设计系统（含[浏览器原型](docs/design/prototype.html)） |

> 为什么用 TCP 而不是 ContentProvider？Android 11+ 的包可见性限制让多邻国解析不了我们的组件，回环 socket 两边都不需要可见性 —— 踩坑实录在 [docs/REVERSE_ENGINEERING.md](docs/REVERSE_ENGINEERING.md)。

## 🛠️ 自己构建

```powershell
# 1. 取 Stockfish 源码（SF19，网名即哈希验货）
tools/fetch_stockfish.sh

# 2. 构建（Gradle 9.4.1 / AGP 9.2.1，完整 JDK 17+，首次需联网）
# SDK 37（sdkmanager 包名 platforms;android-37.0）、Build Tools 36.0.0、NDK 26.1.10909125、CMake 3.22.1
sh gradlew.sh :app:assembleRelease :app:testDebugUnitTest :module:testDebugUnitTest :app:lintRelease

# 3. 检查实际 APK（签名前后均可），然后用自己的密钥签名 → dist/
python tools/check_unified_apk.py app/build/outputs/apk/release/app-release-unsigned.apk --nnue app/src/main/cpp/stockfish/src/nn-1a298aa575a0.nnue
```

输出为 `app/build/outputs/apk/release/app-release-unsigned.apk`；`module` 现在是库，不再输出独立 APK。Windows 可用 `java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain` 替代 `sh gradlew.sh`。使用远程算力时仍需同步电脑脚本。

本地引擎按系统 CPU 能力选择 ARMv8 NEON 或 ARMv8.2 DotProd；骁龙 8 Elite 正常会走 DotProd。两套代码共享一份内嵌 NNUE，并启用 ThinLTO 与预取。未增加 GPU/NPU 后端，提速幅度需在手机上测量：`python tools/bench_android_cpu.py --output cpu-bench.json`。

多邻国升级导致混淆名变了？不用慌：`tools/remap.py` 可以从新 APK 再生映射表，流程见逆向笔记。

仓库里不进 git 的东西（构建产物、Stockfish 源码、`dist/`、多邻国 APK）全部可按上述步骤再生。

## ⭐ 支持一下

如果这个项目帮到了你，或者 Hook 思路 / 引擎集成 / 抗混淆三层回退的设计对你有启发 ——
**点个 Star** 让更多人看到它。每一步折腾都记录在[更新日志](docs/USER_GUIDE.md)里，欢迎 issue 交流。

<div align="center">

[![Star History Chart](https://api.star-history.com/svg?repos=a1175815821/ChessPilot&type=Date)](https://star-history.com/#a1175815821/ChessPilot&Date)

</div>

## 📄 许可证

[GPL-3.0](LICENSE)。Stockfish 本体同为 GPL-3.0，分发二进制必须附带源码获取方式 —— 本仓库即源码（引擎取自官方 tag ＋ 构建脚本全开源）。
