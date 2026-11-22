# ChessPilot-PC（电脑算力端 · 本仓库 `pc/` 目录）

手机 App 发 FEN 过来，这台电脑用桌面 Stockfish 19 算完把招法送回去。

## 它是什么

`pc_engine.py`：一个 TCP 服务（默认 `0.0.0.0:41718`），后面挂一个桌面
Stockfish 进程，每个连接独立，主分析与极限思考互不停止对方搜索。
纯 Python 标准库，零依赖，Python 3.8+ 直接跑。

## 快速开始（3 步）

双击 `start-pc-engine.bat`（黑窗口留着，打完再关；它闪退就说明 Python 没装或端口被占，窗口里会写原因）。

等价命令行：

```bat
cd /d 本仓库 pc 目录
py -3 pc_engine.py
```

1. 首次运行自动从官网拉 Stockfish 19 Windows 包（universal）到 `engine/`；
   没网就自己去 https://stockfishchess.org/download/ 下好，`--engine` 指过去。
2. Windows 防火墙弹框 → 允许（专用网络）。手机和电脑必须在**同一 WiFi**。
3. 看到 `listening on 0.0.0.0:41718`，把电脑的局域网 IP 填进手机
   App（`ipconfig` 看 IPv4，如 `192.168.1.5`，端口 `41718`），点"测试连接"。

自检（不经过手机，直接验证引擎链路）：

```bat
py -3 pc_engine.py --selftest
```

应输出一行 `{"t": "result", ...}` 并退出码 0。

## 参数

手机 App 里有**远程思考时间 / 远程固定深度**两个滑杆，随每条请求发过来，
**手机说了算**；下表是没带值时的默认值。线程/Hash 必须重启进程，
只能在这里配，手机改不了：

| 参数 | 默认 | 说明 |
|---|---|---|
| `--host` | `0.0.0.0` | 监听地址（只绑 `127.0.0.1` = 仅本机自测） |
| `--port` | `41718` | 监听端口（跟 hook 桥的 41717 错开） |
| `--engine` | 自动 | 引擎二进制路径（不给就递归找 `engine/` 下的 stockfish exe，没有就自动下载） |
| `--threads` | `16` | 线程数（5950X 甜点；32 线收益递减还发热） |
| `--hash` | `2048` | 内存 MB |
| `--movetime` | `3000` | 默认每步毫秒（手机没给值时用） |
| `--depth` | `0` | 默认固定深度（0 = 按时间思考） |
| `--selftest` | — | 开局冒烟测试后退出 |

`--threads` 和 `--hash` 按每个进程配置。开启极限思考时手机会使用两个连接，
即两个引擎进程；例如 `--hash 1024` 共预留约 2 GiB 置换表内存。

## 协议（给排障看的，手机侧 `RemoteEngineClient` 对接）

UTF-8 JSON，一行一个。`analyse`/`ponder`/`ponderhit`/`stop`/`ping`/`info`
进，`result`/`ponder_info`/`stopped`/`pong`/`info`/`error` 出。
每个连接一次只跑一个搜索，新分析先停止本连接的旧任务。

极限思考会持续回传 `ponder_info`，包含预测后的目标 FEN、预备应招、下一预测、
分数与深度。0.6.7 的 `ponder` 请求带有 `bounded: true`，电脑端据此执行指定的
`go depth` 或 `go movetime`。深度为 0 时使用请求的时间。手机在预算结束或提前命中时
用 `ponderhit` 取回已有结果，并在命中时复用。旧电脑端仍可连接，由手机结束预搜索。
使用新版脚本后重启电脑服务即可用上电脑端的预算控制。

真实引擎回归测试（指定已下载的 Stockfish，无第三方测试依赖）：

```sh
STOCKFISH_BIN=/path/to/stockfish python -m unittest discover -s pc -v
```

## 排障

| 现象 | 做法 |
|---|---|
| 手机"测试连接"不通 | 先确认同 WiFi + `ipconfig` 的 IP 没变（DHCP 会换）；再看本窗口有没有 `phone connected`；防火墙放行 Python |
| `bind failed` | 端口被占，换 `--port`，手机侧同步改 |
| `engine unresponsive` | 引擎进程挂了，服务会自动重启；频繁出现看内存/杀毒软件是否误杀 |
| 算得慢 | 加 `--threads`（别超物理核太多）、`--hash`（别超内存一半）；看是不是省电模式降频 |

## 许可

本脚本 MIT（随便用）。注意：它调用的 Stockfish 引擎是 **GPL-3.0**，
从官网下的二进制受 GPL 约束（自用没问题，分发连带源码义务）。
