# 0.6.0：单 APK、API 102 与 CPU 优化

安装包统一为 `com.chessassist`。`app` 依赖 Android 库 `module`，控制页、引擎和 Hook 入口一起更新；注入后的 Hook 仍运行在多邻国进程内，通过原来的本地 TCP 桥连接 ChessPilot 服务。进程隔离和远程电脑后端保留。

## 从旧版本迁移

1. 使用明确支持 **libxposed API 102** 的框架；旧 API 82/100/101 框架不能加载本版。
2. 用与原伴侣相同的密钥签名并覆盖安装合包，可沿用 `com.chessassist` 的设置。仓库测试 APK 使用测试密钥，其他签名的旧包不能直接覆盖。
3. 停用旧独立模块 `io.github.a1175815821.chesspilot`，启用新模块 `com.chessassist`，作用域只选 `com.duolingo`。
4. **首次迁移结束多邻国运行后重开一次**，避免旧模块与新模块同时注入。打开 ChessPilot 并启动引擎服务。

API 102 生命周期只适用于已加载的新模块。旧 API 模块不能直接热迁移到这个新包名。

## 热重载如何工作

使用官方 `io.github.libxposed:api:102.0.0`（compileOnly）和 `service:102.0.0`；APK 不包含框架 API 类。旧 Xposed 桩和 `de.robv.android.xposed` 调用全部移除。唯一入口为 `io.github.a1175815821.chesspilot.MainHook`：

```properties
# META-INF/xposed/module.prop
minApiVersion=102
targetApiVersion=102
staticScope=true
autoHotReload=true
```

更新 APK 时，支持此功能的框架会调用旧代码的 `onHotReloading`，再调用新代码的 `onHotReloaded`。普通包加载回调不会重放，所以新入口主动重新装 Hook 并接回已存在的棋盘。

- 拦截器有稳定 ID；相同目标和 ID 由 API 102 原子替换，已删除的 Hook 会解除，部分初始化失败时也清理遗留旧 Hook。
- 旧代停止接收回调，等待正在执行的模块回调退出，取消延迟绘制和点按；停止扫描线程和 TCP 读写线程，移除自建窗口。不能确认退出时拒绝重载，重开多邻国恢复。
- 跨代状态只保存 Java `HashMap`、FEN/颜色字符串、Java `ArrayList` 招法列表，以及框架/目标侧的 Context、ClassLoader、controller、state 和 Rive View。不传递旧代的 `StateBridge`、闭包、线程或 Kotlin 数据类。
- 新代核对 controller/state 的类型后恢复当前局面，重建自己的桥连接；没有已有 Application 时保留 attach Hook，等待正常初始化。
- 模块没有自建 JNI 库或原生线程；Stockfish 是 ChessPilot App 中的独立子进程，其生命周期由 App 服务管理。

控制页的「模块状态」显示框架/API 和手动请求结果。「热重载多邻国模块」仅对框架报告为 `STALE`/`FAILED` 的目标重试，已是当前版本时不会重复重载。服务 SDK 的 Binder 请求在后台线程执行。修改思考时间等设置沿用原即时生效机制，无需重载。

安装更新可能使 Android 结束 ChessPilot App 自身的服务；此时需重新点「启动引擎服务」。hot reload 针对注入多邻国的模块代码，不会让被系统停止的伴侣服务自动继续运行。框架不支持、目标退出或清理失败时，界面给出重开多邻国的提示。

## CPU 路径

Stockfish 19 编译两份搜索/NNUE 代码，但只嵌入一份 `nn-1a298aa575a0.nnue`（98,511,183 字节）：

| 路径 | 编译选项 | 选择条件 |
|---|---|---|
| ARMv8 NEON | `-march=armv8-a`, `USE_NEON=8`, `USE_POPCNT` | 默认回退 |
| NEON DotProd | `-march=armv8.2-a+dotprod`, `USE_NEON_DOTPROD` | 内核 `AT_HWCAP` 包含 `HWCAP_ASIMDDP` |

两份代码分别位于 `Stockfish_armv8` / `Stockfish_armv8_dotprod` 命名空间，沿用上游 universal 的独立初始化数组方案。分发入口按 ARMv8 编译；检测能力后才手动运行选中路径的初始化函数，通用设备不会执行 DotProd 初始化。链接器的 `DT_INIT_ARRAY` 只指向公共启动段。

两条路径均使用 `-O3`、ThinLTO、上游预取和循环展开，关闭引擎源码异常处理。ELF LOAD 段按 16 KB 对齐。优化不改变 Stockfish 搜索参数、网络或本地/远程预算。极限思考继续使用独立引擎和已完成迭代的缓存。

骁龙 8 Elite 正常应选择 DotProd；状态页「引擎」会显示从实际子进程读到的 `NEON DotProd`。系统未报告能力则回退 `ARMv8 NEON`。没有 GPU/NPU 后端，未做任何倍数承诺。

## 构建与主机检查

需要完整 JDK 17+、SDK 37（当前 sdkmanager 包名 `platforms;android-37.0`）、Build Tools 36.0.0、NDK 26.1.10909125、CMake 3.22.1。Gradle 9.4.1 发行包固定 SHA-256，AGP 9.2.1 使用内置 Kotlin 支持。

```sh
bash tools/fetch_stockfish.sh
sh gradlew.sh :app:assembleRelease :app:testDebugUnitTest :module:testDebugUnitTest :app:lintRelease
python tools/check_unified_apk.py app/build/outputs/apk/release/app-release-unsigned.apk --nnue app/src/main/cpp/stockfish/src/nn-1a298aa575a0.nnue
python -m unittest discover -s tools -v
STOCKFISH_BIN=/path/to/host/stockfish python -m unittest discover -s pc -v
```

APK 检查直接读取最终 ZIP、DEX 和 ELF，核对唯一现代入口、API 102 元数据、框架类未打包、旧 API 无残留、单个引擎文件、分发初始化段、16 KB 段对齐和 NNUE 仅一份。单元测试覆盖预测缓存、反射替代接口及回调退休屏障；这些主机检查不能代替真机的框架注入和 Rive 行为验证。

## 真机验收

1. 完成首次迁移；进入 6.105.2 的棋局，核对 FEN、黑白方向、推荐箭头、自动走棋和预测命中复用。确认框架只有一个 ChessPilot 模块生效。
2. 启动本地引擎，确认 8 Elite 状态页显示 `NEON DotProd`。
3. 保持多邻国进程运行，将同密钥、递增 `versionCode` 的下一份合包覆盖安装。记录更新前后多邻国 PID；在支持 API 102 hot reload 的框架上应保持同一 PID，出现旧代退休和新代完成日志，桥重连后当前棋盘继续上报。
4. 确认旧代 `duo-chess-bridge`/`duo-chess-bridge-send`/`duo-chess-vmscan` 线程退出，没有重复窗口或点按；重复数次更新。自动重载未触发时，用控制页按钮查看框架报告；失败时重开多邻国。
5. 在无 DotProd 的 ARM64 设备上确认可启动且显示回退路径。以下测速脚本也能在同一台支持 DotProd 的设备上强制通用路径，比较相同网络和搜索结果。

```sh
# 手机连接 adb；先停止 ChessPilot 引擎服务，保持温度和电源条件稳定
python tools/bench_android_cpu.py --depth 12 --repeats 3 --output cpu-bench.json
# 多设备可加 --serial；自动定位失败可加 --binary /data/app/.../lib/arm64/libstockfish.so
```

脚本预热后交替运行自动路径与 `--chesspilot-baseline`，使用 Stockfish 默认局面集、单线程、16 MB Hash、固定深度。核对所有运行的节点数一致，再输出 NPS 中位数和比值。温度、调度及后台负载会影响结果；测速值属于该手机当时条件，不能从桌面回归或静态构建推出 8 Elite 提速比例。

本次没有连接 Android 真机；注入、跨版本热重载及 8 Elite 性能测量属于待验证项。
